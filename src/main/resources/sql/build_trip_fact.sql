-- Step 1: build trip_fact from staging. Pure SQL transform (decision 8/9: JdbcTemplate + SQL, no ORM).
-- Cleans the quirks confirmed in Step 0/1 (CLAUDE.md): comma-formatted numerics, "NA" strings,
-- product_type/trip_nodal independence, severity="False", marshal_rating=0 meaning "no marshal",
-- and trip_id colliding across tenants/months (every join below is keyed on
-- (tenant_id, trip_id), never trip_id alone).

WITH ride AS (
    SELECT
        regexp_replace(trip_id, ',', '', 'g')::bigint AS trip_id,
        business_unit AS tenant_id,
        office,
        product_type,
        CASE
            WHEN product_type = 'SPOT_2.0' THEN 'SPOT'
            WHEN NULLIF(trip_nodal, 'NA') IS NULL THEN 'HOME'
            WHEN trip_nodal IN ('HOME', 'NODAL', 'SHUTTLE') THEN trip_nodal
            ELSE 'HOME'
        END AS mode,
        to_date(trip_date, 'FMMonth FMDD, YYYY') AS trip_date,
        shift_type,
        trip_direction,
        vendor_id,
        actual_cab_registration,
        NULLIF(regexp_replace(actual_cab_capacity, ',', '', 'g'), '')::int AS actual_cab_capacity,
        actual_cab_fuel_type,
        NULLIF(regexp_replace(planned_km, ',', '', 'g'), '')::numeric AS planned_km,
        NULLIF(regexp_replace(traveled_km, ',', '', 'g'), '')::numeric AS traveled_km,
        to_timestamp(NULLIF(regexp_replace(planned_start_epoch, ',', '', 'g'), '')::bigint) AS planned_start_ts,
        to_timestamp(NULLIF(regexp_replace(planned_end_epoch, ',', '', 'g'), '')::bigint) AS planned_end_ts,
        to_timestamp(NULLIF(regexp_replace(actual_start_epoch, ',', '', 'g'), '')::bigint) AS actual_start_ts,
        to_timestamp(NULLIF(regexp_replace(actual_end_epoch, ',', '', 'g'), '')::bigint) AS actual_end_ts,
        NULLIF(regexp_replace(delay_minutes, ',', '', 'g'), '')::numeric AS delay_minutes,
        delay_reason,
        route_source,
        CASE lower(is_driver_nc) WHEN 'true' THEN true WHEN 'false' THEN false ELSE NULL END AS is_driver_nc,
        CASE lower(is_cab_nc) WHEN 'true' THEN true WHEN 'false' THEN false ELSE NULL END AS is_cab_nc,
        CASE lower(actual_escort) WHEN 'true' THEN true WHEN 'false' THEN false ELSE NULL END AS actual_escort,
        NULLIF(regexp_replace(plannedemployee_cnt, ',', '', 'g'), '')::int AS planned_employee_cnt,
        NULLIF(regexp_replace(actualemployee_cnt, ',', '', 'g'), '')::int AS actual_employee_cnt,
        NULLIF(regexp_replace(noshow_cnt, ',', '', 'g'), '')::int AS noshow_cnt,
        (lower(is_driver_nc) NOT IN ('true', 'false') OR lower(is_cab_nc) NOT IN ('true', 'false')) AS nc_flag_unclear
    FROM stg_ride_trip
),
ride_dedup AS (
    -- guard against any genuine duplicate (tenant_id, trip_id) within the three monthly files
    SELECT DISTINCT ON (tenant_id, trip_id) *
    FROM ride
    ORDER BY tenant_id, trip_id
),
billing_agg AS (
    -- trip_id is not always numeric: 160 rows carry the literal "OverHead" (a fixed/admin
    -- charge not tied to any trip, undocumented, found during Step 1) - exclude, don't cast.
    SELECT
        business_unit AS tenant_id,
        regexp_replace(trip_id, ',', '', 'g')::bigint AS trip_id,
        SUM(NULLIF(regexp_replace(trip_cost, ',', '', 'g'), '')::numeric) AS billed_cost,
        SUM(CASE WHEN NULLIF(regexp_replace(total_trip_km, ',', '', 'g'), '')::numeric > 0
            THEN regexp_replace(total_trip_km, ',', '', 'g')::numeric END) AS billed_km,
        MAX(contract) AS contract,
        MAX(NULLIF(slab_name, 'null')) AS slab_name
    FROM stg_bill
    WHERE trip_id ~ '^[0-9]+$'
    GROUP BY 1, 2
),
alerts_clean AS (
    SELECT
        business_unit AS tenant_id,
        regexp_replace(trip_id, ',', '', 'g')::bigint AS trip_id,
        CASE WHEN severity IN ('Sev-1', 'Sev-2', 'Sev-3') THEN severity ELSE NULL END AS severity_clean,
        event_type
    FROM stg_alerts
),
alerts_agg AS (
    SELECT
        tenant_id,
        trip_id,
        COUNT(*) AS alert_count,
        COUNT(*) FILTER (WHERE severity_clean = 'Sev-1') AS sev1_count,
        COUNT(*) FILTER (WHERE severity_clean = 'Sev-2') AS sev2_count,
        COUNT(*) FILTER (WHERE severity_clean = 'Sev-3') AS sev3_count,
        COUNT(*) FILTER (WHERE severity_clean IS NULL) AS severity_unknown_count,
        bool_or(event_type IN ('PANIC_DEVICE', 'PANIC_MOBILE', 'PANIC_FIXED_DEVICE')) AS has_sos
    FROM alerts_clean
    GROUP BY 1, 2
),
feedback_agg AS (
    SELECT
        business_unit AS tenant_id,
        regexp_replace(trip_id, ',', '', 'g')::bigint AS trip_id,
        AVG(NULLIF(route_rating, '')::numeric) AS avg_route_rating,
        AVG(NULLIF(driver_rating, '')::numeric) AS avg_driver_rating,
        AVG(NULLIF(cab_rating, '')::numeric) AS avg_cab_rating,
        AVG(NULLIF(safety_rating, '')::numeric) AS avg_safety_rating,
        AVG(NULLIF(NULLIF(marshal_rating, '0'), '')::numeric) AS avg_marshal_rating,
        COUNT(*) AS feedback_count
    FROM stg_feedback
    GROUP BY 1, 2
)
INSERT INTO trip_fact (
    trip_id, tenant_id, office, product_type, mode, trip_date, shift_type, trip_direction,
    vendor_id, actual_cab_registration, actual_cab_capacity, actual_cab_fuel_type,
    planned_km, traveled_km, detour_km,
    planned_start_ts, planned_end_ts, actual_start_ts, actual_end_ts,
    delay_minutes, delay_reason, route_source,
    is_driver_nc, is_cab_nc, actual_escort,
    planned_employee_cnt, actual_employee_cnt, noshow_cnt, fill_rate,
    billed_cost, billed_km, contract, slab_name, co2_kg,
    alert_count, sev1_count, sev2_count, sev3_count, severity_unknown_count, has_sos,
    avg_route_rating, avg_driver_rating, avg_cab_rating, avg_safety_rating, avg_marshal_rating,
    feedback_count, data_quality_flags
)
SELECT
    r.trip_id, r.tenant_id, r.office, r.product_type, r.mode, r.trip_date, r.shift_type, r.trip_direction,
    r.vendor_id, r.actual_cab_registration, r.actual_cab_capacity, r.actual_cab_fuel_type,
    r.planned_km, r.traveled_km, (r.traveled_km - r.planned_km) AS detour_km,
    r.planned_start_ts, r.planned_end_ts, r.actual_start_ts, r.actual_end_ts,
    r.delay_minutes, r.delay_reason, r.route_source,
    r.is_driver_nc, r.is_cab_nc, r.actual_escort,
    r.planned_employee_cnt, r.actual_employee_cnt, r.noshow_cnt,
    CASE WHEN r.actual_cab_capacity > 0 THEN r.actual_employee_cnt::numeric / r.actual_cab_capacity END AS fill_rate,
    b.billed_cost, b.billed_km, b.contract, b.slab_name,
    -- illustrative emission factors (kg CO2/km), not dataset-derived: Diesel 0.18, Petrol 0.19, Electric 0.05
    r.traveled_km * CASE r.actual_cab_fuel_type
        WHEN 'Diesel' THEN 0.18
        WHEN 'Petrol' THEN 0.19
        WHEN 'Electric' THEN 0.05
        ELSE 0.15
    END AS co2_kg,
    COALESCE(a.alert_count, 0), COALESCE(a.sev1_count, 0), COALESCE(a.sev2_count, 0),
    COALESCE(a.sev3_count, 0), COALESCE(a.severity_unknown_count, 0), COALESCE(a.has_sos, false),
    f.avg_route_rating, f.avg_driver_rating, f.avg_cab_rating, f.avg_safety_rating, f.avg_marshal_rating,
    COALESCE(f.feedback_count, 0),
    NULLIF(
        concat_ws(',',
            CASE WHEN b.billed_cost IS NULL THEN 'no_billing' END,
            CASE WHEN f.feedback_count IS NULL THEN 'no_feedback' END,
            CASE WHEN r.nc_flag_unclear THEN 'nc_flag_unclear' END
        ), ''
    ) AS data_quality_flags
FROM ride_dedup r
LEFT JOIN billing_agg b ON b.tenant_id = r.tenant_id AND b.trip_id = r.trip_id
LEFT JOIN alerts_agg a ON a.tenant_id = r.tenant_id AND a.trip_id = r.trip_id
LEFT JOIN feedback_agg f ON f.tenant_id = r.tenant_id AND f.trip_id = r.trip_id;
