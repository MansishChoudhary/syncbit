-- Step 3: candidate breach detection for one scan date (the single ? parameter).
-- Pure SQL, zero LLM cost (decision: C3 must not call a model).
--
-- A grain x metric row qualifies as a candidate when:
--  - it's at least 2 std devs from its own trailing 4-wk same-weekday baseline, OR
--  - it's breaching its SLA target (delta_vs_sla is already sign-normalized: <0 = bad), OR
--  - it's a safety metric (sev1_rate/sos_rate) with ANY nonzero value - rare-event safety
--    metrics don't wait for statistical significance, unlike everything else.
-- sample_size >= 5 guards against noise from tiny groups (a vendor with 1-2 trips that day).
SELECT
    bv.tenant_id,
    bv.office,
    bv.vendor_id,
    bv.mode,
    bv.shift_type,
    bv.metric_name,
    bv.metric_value,
    bv.sample_size,
    bv.z_score_vs_baseline,
    bv.baseline_avg,
    bv.sla_target_value,
    bv.sla_direction,
    bv.delta_vs_sla,
    bv.peer_median,
    bv.delta_vs_peer_pct,
    da.dq_flag_rate,
    COALESCE(persistence.days_breached, 0) AS persistence_days
FROM benchmark_view bv
JOIN daily_aggregate da
    ON da.tenant_id = bv.tenant_id AND da.trip_date = bv.trip_date AND da.office = bv.office
   AND da.vendor_id = bv.vendor_id AND da.mode = bv.mode AND da.shift_type = bv.shift_type
LEFT JOIN LATERAL (
    SELECT COUNT(*) AS days_breached
    FROM benchmark_view bv2
    WHERE bv2.tenant_id = bv.tenant_id AND bv2.office = bv.office AND bv2.vendor_id = bv.vendor_id
      AND bv2.mode = bv.mode AND bv2.shift_type = bv.shift_type AND bv2.metric_name = bv.metric_name
      AND bv2.trip_date IN (bv.trip_date - 1, bv.trip_date - 2, bv.trip_date - 3)
      AND (ABS(COALESCE(bv2.z_score_vs_baseline, 0)) >= 2 OR bv2.delta_vs_sla < 0)
) persistence ON true
WHERE bv.trip_date = ?
  AND bv.sample_size >= 5
  AND (
    ABS(COALESCE(bv.z_score_vs_baseline, 0)) >= 2
    OR bv.delta_vs_sla < 0
    OR (bv.metric_name IN ('sev1_rate', 'sos_rate') AND bv.metric_value > 0)
  );
