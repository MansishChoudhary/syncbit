package com.binarybrains.syncbit.scan;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * C3 (scan & triage) per CLAUDE.md: reads benchmark_view for one date, scores candidate
 * breaches on materiality x severity x persistence, gates on data-quality confidence,
 * dedups to one signal per vendor per day, ranks, and writes the top N + all safety
 * signals (which bypass both the confidence gate and the ranking cap) to the signal
 * table. Zero LLM cost - pure SQL query + Java scoring/ranking, no model call.
 */
@Service
public class ScanService {

    private static final Logger log = LoggerFactory.getLogger(ScanService.class);
    private static final int TOP_N = 8;
    private static final double MIN_CONFIDENCE = 0.5;

    private final JdbcTemplate jdbcTemplate;
    private final String candidatesSql;

    public ScanService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.candidatesSql = readClasspathResource("sql/scan_candidates.sql").trim().replaceAll(";\\s*$", "");
    }

    public ScanResult scan(LocalDate date) {
        Instant start = Instant.now();

        jdbcTemplate.update("DELETE FROM signal WHERE date = ?", date);

        List<Candidate> candidates = jdbcTemplate.query(candidatesSql,
                ps -> ps.setObject(1, date),
                (rs, rowNum) -> mapCandidate(rs));

        List<ScoredSignal> safety = new ArrayList<>();
        List<ScoredSignal> normal = new ArrayList<>();
        for (Candidate c : candidates) {
            ScoredSignal scored = score(c);
            if (c.isSafety()) {
                safety.add(scored);
            } else if (scored.dataQualityConfidence() >= MIN_CONFIDENCE) {
                normal.add(scored);
            }
        }

        // Dedup: one signal per vendor per day for non-safety candidates - keep the
        // highest-scoring metric, don't emit near-duplicate signals about the same vendor.
        Map<String, ScoredSignal> byVendor = new LinkedHashMap<>();
        for (ScoredSignal s : normal) {
            String key = s.candidate().tenantId() + "|" + s.candidate().vendorId();
            byVendor.merge(key, s, (a, b) -> a.finalScore() >= b.finalScore() ? a : b);
        }

        List<ScoredSignal> ranked = byVendor.values().stream()
                .sorted(Comparator.comparingDouble(ScoredSignal::finalScore).reversed())
                .limit(TOP_N)
                .toList();

        List<ScoredSignal> toInsert = new ArrayList<>();
        toInsert.addAll(safety);
        toInsert.addAll(ranked);
        toInsert.forEach(s -> insert(date, s));

        long tookMs = Instant.now().toEpochMilli() - start.toEpochMilli();
        ScanResult result = new ScanResult(date, candidates.size(), safety.size(), ranked.size(), toInsert.size(), tookMs);
        log.info("Scan complete: {}", result);
        return result;
    }

    private ScoredSignal score(Candidate c) {
        double severityFromZ = c.zScoreVsBaseline() == null ? 0
                : Math.min(Math.abs(c.zScoreVsBaseline().doubleValue()) / 3.0, 1.0);
        double severityFromSla = (c.slaTargetValue() == null || c.deltaVsSla() == null || c.slaTargetValue().doubleValue() == 0) ? 0
                : Math.min(Math.abs(c.deltaVsSla().doubleValue()) / Math.abs(c.slaTargetValue().doubleValue()), 1.0);
        double severity = Math.max(severityFromZ, severityFromSla);
        if (c.isSafety() && severity == 0) {
            // any nonzero safety event matters regardless of statistical magnitude
            severity = 1.0;
        }

        double materiality = Math.min(c.sampleSize(), 200) / 200.0;
        double persistenceBonus = Math.min(c.persistenceDays(), 3) / 3.0;
        double confidence = 1.0 - (c.dqFlagRate() == null ? 0.0 : c.dqFlagRate().doubleValue());
        double finalScore = materiality * severity * (1 + persistenceBonus);

        String referenceType;
        BigDecimal referenceValue;
        BigDecimal deviationMagnitude;
        if (c.deltaVsSla() != null && c.deltaVsSla().doubleValue() < 0) {
            referenceType = "sla";
            referenceValue = c.slaTargetValue();
            deviationMagnitude = c.deltaVsSla();
        } else if (c.baselineAvg() != null) {
            referenceType = "baseline";
            referenceValue = c.baselineAvg();
            deviationMagnitude = c.zScoreVsBaseline();
        } else if (c.peerMedian() != null) {
            referenceType = "peer";
            referenceValue = c.peerMedian();
            deviationMagnitude = c.deltaVsPeerPct();
        } else {
            referenceType = null;
            referenceValue = null;
            deviationMagnitude = null;
        }

        return new ScoredSignal(c, materiality, severity, persistenceBonus, finalScore, confidence,
                referenceType, referenceValue, deviationMagnitude);
    }

    private void insert(LocalDate date, ScoredSignal s) {
        Candidate c = s.candidate();
        jdbcTemplate.update("""
                INSERT INTO signal (tenant_id, date, persona, entity_type, entity_id, office, mode, shift_type,
                    metric_name, observed_value, reference_type, reference_value, deviation_magnitude,
                    materiality_score, severity_score, persistence_days, final_score,
                    safety_flag, data_quality_confidence, status)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                c.tenantId(), date, "manager", "vendor", c.vendorId(), c.office(), c.mode(), c.shiftType(),
                c.metricName(), c.metricValue(), s.referenceType(), s.referenceValue(), s.deviationMagnitude(),
                s.materiality(), s.severity(), c.persistenceDays(), s.finalScore(),
                c.isSafety(), s.dataQualityConfidence(), "new");
    }

    private Candidate mapCandidate(ResultSet rs) throws SQLException {
        return new Candidate(
                rs.getString("tenant_id"),
                rs.getString("office"),
                rs.getString("vendor_id"),
                rs.getString("mode"),
                rs.getString("shift_type"),
                rs.getString("metric_name"),
                rs.getBigDecimal("metric_value"),
                rs.getInt("sample_size"),
                rs.getBigDecimal("z_score_vs_baseline"),
                rs.getBigDecimal("baseline_avg"),
                rs.getBigDecimal("sla_target_value"),
                rs.getString("sla_direction"),
                rs.getBigDecimal("delta_vs_sla"),
                rs.getBigDecimal("peer_median"),
                rs.getBigDecimal("delta_vs_peer_pct"),
                rs.getBigDecimal("dq_flag_rate"),
                rs.getInt("persistence_days"));
    }

    private String readClasspathResource(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + path, e);
        }
    }
}
