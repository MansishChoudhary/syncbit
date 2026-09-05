package com.binarybrains.syncbit.narrative;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Pure SQL evidence gathering for C4's prompts - decision 6 (numbers computed in SQL,
 * no raw trip rows... beyond a small labeled sample for grounding). Never calls a model.
 */
@Service
public class EvidenceService {

    private final JdbcTemplate jdbcTemplate;

    public EvidenceService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Attribution breakdown by delay_reason - a SQL aggregate (counts/averages), not raw
     * trip rows (decision 6: no raw trip rows in any prompt). Grounds "why" without
     * handing the model individual trips to (mis)summarize itself. */
    public List<Map<String, Object>> attributionBreakdown(Signal s) {
        return jdbcTemplate.queryForList("""
                SELECT delay_reason, COUNT(*) AS trip_count, SUM(noshow_cnt) AS noshows,
                       ROUND(AVG(delay_minutes)::numeric, 1) AS avg_delay_minutes,
                       COUNT(*) FILTER (WHERE alert_count > 0) AS trips_with_alerts
                FROM trip_fact
                WHERE tenant_id = ? AND vendor_id = ? AND office = ? AND mode = ? AND shift_type = ? AND trip_date = ?
                GROUP BY delay_reason
                ORDER BY trip_count DESC
                """,
                s.tenantId(), s.entityId(), s.office(), s.mode(), s.shiftType(), s.date());
    }

    /** The same metric for the same vendor grain over the trailing N days, from
     * benchmark_view - lets the narrative describe a trend, not just one day's number. */
    public List<Map<String, Object>> trend(Signal s, int days) {
        // trip_date cast to text: queryForList's generic Map path serializes a raw DATE
        // as java.sql.Date, which Jackson turns into a UTC instant via the JVM's default
        // timezone - confirmed this shifted every displayed date back by one day (IST
        // offset). Casting in SQL sidesteps the whole java.sql.Date/Jackson pitfall.
        return jdbcTemplate.queryForList("""
                SELECT trip_date::text AS trip_date, metric_value
                FROM benchmark_view
                WHERE tenant_id = ? AND vendor_id = ? AND office = ? AND mode = ? AND shift_type = ?
                  AND metric_name = ? AND trip_date BETWEEN ? AND ?
                ORDER BY trip_date
                """,
                s.tenantId(), s.entityId(), s.office(), s.mode(), s.shiftType(), s.metricName(),
                s.date().minusDays(days - 1L), s.date());
    }

    /** Tenant-wide rollup over a date range, for the leadership brief - totals only,
     * every number here is a plain SQL aggregate. */
    public Map<String, Object> tenantRollup(String tenantId, LocalDate start, LocalDate end) {
        return jdbcTemplate.queryForMap("""
                SELECT
                    SUM(trip_count) AS total_trips,
                    ROUND(AVG(ota_pct)::numeric, 4) AS avg_ota_pct,
                    ROUND(SUM(cost_total)::numeric, 2) AS total_cost,
                    ROUND(AVG(alert_rate)::numeric, 4) AS avg_alert_rate,
                    ROUND(AVG(sev1_rate)::numeric, 4) AS avg_sev1_rate,
                    ROUND(AVG(no_show_rate)::numeric, 4) AS avg_no_show_rate,
                    ROUND(AVG(escort_presence_rate)::numeric, 4) AS avg_escort_presence_rate
                FROM daily_aggregate
                WHERE tenant_id = ? AND trip_date BETWEEN ? AND ?
                """, tenantId, start, end);
    }

    /** All signals raised for a tenant in a date range, most material first - the
     * leadership brief's headline material. */
    public List<Signal> signalsForRange(String tenantId, LocalDate start, LocalDate end) {
        return jdbcTemplate.query("""
                SELECT signal_id, tenant_id, date, entity_type, entity_id, office, mode, shift_type,
                       metric_name, observed_value, reference_type, reference_value, deviation_magnitude,
                       persistence_days, final_score, safety_flag
                FROM signal
                WHERE tenant_id = ? AND date BETWEEN ? AND ?
                ORDER BY safety_flag DESC, final_score DESC
                """,
                (rs, rowNum) -> new Signal(
                        rs.getLong("signal_id"), rs.getString("tenant_id"), rs.getObject("date", LocalDate.class),
                        rs.getString("entity_type"), rs.getString("entity_id"), rs.getString("office"),
                        rs.getString("mode"), rs.getString("shift_type"), rs.getString("metric_name"),
                        rs.getBigDecimal("observed_value"), rs.getString("reference_type"),
                        rs.getBigDecimal("reference_value"), rs.getBigDecimal("deviation_magnitude"),
                        rs.getInt("persistence_days"), rs.getBigDecimal("final_score"), rs.getBoolean("safety_flag")),
                tenantId, start, end);
    }

    /** All signals across all tenants for one date, most material first. */
    public List<Signal> allSignalsForDate(LocalDate date) {
        return jdbcTemplate.query("""
                SELECT signal_id, tenant_id, date, entity_type, entity_id, office, mode, shift_type,
                       metric_name, observed_value, reference_type, reference_value, deviation_magnitude,
                       persistence_days, final_score, safety_flag
                FROM signal
                WHERE date = ?
                ORDER BY safety_flag DESC, final_score DESC
                """,
                (rs, rowNum) -> new Signal(
                        rs.getLong("signal_id"), rs.getString("tenant_id"), rs.getObject("date", LocalDate.class),
                        rs.getString("entity_type"), rs.getString("entity_id"), rs.getString("office"),
                        rs.getString("mode"), rs.getString("shift_type"), rs.getString("metric_name"),
                        rs.getBigDecimal("observed_value"), rs.getString("reference_type"),
                        rs.getBigDecimal("reference_value"), rs.getBigDecimal("deviation_magnitude"),
                        rs.getInt("persistence_days"), rs.getBigDecimal("final_score"), rs.getBoolean("safety_flag")),
                date);
    }

    public Signal findById(long signalId) {
        return jdbcTemplate.queryForObject("""
                SELECT signal_id, tenant_id, date, entity_type, entity_id, office, mode, shift_type,
                       metric_name, observed_value, reference_type, reference_value, deviation_magnitude,
                       persistence_days, final_score, safety_flag
                FROM signal WHERE signal_id = ?
                """,
                (rs, rowNum) -> new Signal(
                        rs.getLong("signal_id"), rs.getString("tenant_id"), rs.getObject("date", LocalDate.class),
                        rs.getString("entity_type"), rs.getString("entity_id"), rs.getString("office"),
                        rs.getString("mode"), rs.getString("shift_type"), rs.getString("metric_name"),
                        rs.getBigDecimal("observed_value"), rs.getString("reference_type"),
                        rs.getBigDecimal("reference_value"), rs.getBigDecimal("deviation_magnitude"),
                        rs.getInt("persistence_days"), rs.getBigDecimal("final_score"), rs.getBoolean("safety_flag")),
                signalId);
    }
}
