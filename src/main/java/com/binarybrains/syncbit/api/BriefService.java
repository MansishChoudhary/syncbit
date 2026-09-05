package com.binarybrains.syncbit.api;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * C5: thin read queries for the morning brief and leadership brief screens - no business
 * logic, just projections over what C3/C4 already computed (decision: C5 must not
 * contain business logic).
 */
@Service
public class BriefService {

    private final JdbcTemplate jdbcTemplate;

    public BriefService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<SignalView> morningBrief(LocalDate date, String tenantId) {
        String sql = """
                SELECT signal_id, tenant_id, date, entity_type, entity_id, office, mode, shift_type,
                       metric_name, observed_value, reference_type, reference_value, deviation_magnitude,
                       persistence_days, final_score, safety_flag, data_quality_confidence, status, narrative
                FROM signal
                WHERE date = ? %s
                ORDER BY safety_flag DESC, final_score DESC
                """.formatted(tenantId == null ? "" : "AND tenant_id = ?");
        Object[] params = tenantId == null ? new Object[]{date} : new Object[]{date, tenantId};
        return jdbcTemplate.query(sql, (rs, rowNum) -> new SignalView(
                rs.getLong("signal_id"), rs.getString("tenant_id"), rs.getObject("date", LocalDate.class),
                rs.getString("entity_type"), rs.getString("entity_id"), rs.getString("office"),
                rs.getString("mode"), rs.getString("shift_type"), rs.getString("metric_name"),
                rs.getBigDecimal("observed_value"), rs.getString("reference_type"),
                rs.getBigDecimal("reference_value"), rs.getBigDecimal("deviation_magnitude"),
                rs.getInt("persistence_days"), rs.getBigDecimal("final_score"), rs.getBoolean("safety_flag"),
                rs.getBigDecimal("data_quality_confidence"), rs.getString("status"), rs.getString("narrative")),
                params);
    }

    public Map<String, Object> leadershipBrief(String tenantId, LocalDate weekEnding) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT tenant_id, week_ending::text AS week_ending, narrative, evidence_ref, created_at
                FROM leadership_brief WHERE tenant_id = ? AND week_ending = ?
                """, tenantId, weekEnding);
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    public List<String> tenantsWithBrief(LocalDate weekEnding) {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT tenant_id FROM leadership_brief WHERE week_ending = ? ORDER BY tenant_id",
                String.class, weekEnding);
    }
}
