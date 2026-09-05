package com.binarybrains.syncbit.ingest;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * C1 (ingest & fact build) per CLAUDE.md decision 8: staging tables loaded raw from the
 * five source CSVs, then a single SQL transform builds trip_fact. No JPA, no ORM.
 * Idempotent: every run drops and recreates all tables, so re-running never duplicates rows.
 * emp_data is intentionally not loaded — trip-level headcounts on ride_data_trip already
 * cover fill rate / no-show rate (see CLAUDE.md, C1 non-goals).
 */
@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);
    private static final int BATCH_SIZE = 5000;

    private final JdbcTemplate jdbcTemplate;

    public IngestService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public IngestResult ingestAll() {
        Instant start = Instant.now();
        applyDdl("schema.sql");

        int ride = loadRideTrip();
        int bill = loadCsv("data/bill_data.csv", "bill_data.csv",
                "INSERT INTO stg_bill (business_unit, office, vendor, cycle_start, cycle_end, trip_id, contract, slab_name, total_trip_km, trip_cost, source_file) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                r -> new Object[]{
                        r.get("business_unit"), r.get("office"), r.get("vendor"), r.get("cycle_start"),
                        r.get("cycle_end"), r.get("trip_id"), r.get("contract"), r.get("slab_name"),
                        r.get("total_trip_km"), r.get("trip_cost")
                });
        int alerts = loadCsv("data/alerts_data.csv", "alerts_data.csv",
                "INSERT INTO stg_alerts (business_unit, trip_id, stwid, event_id, event_type, start_time, acknowledge_time, state_text, severity, source, source_file) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                r -> new Object[]{
                        r.get("business_unit"), r.get("trip_id"), r.get("stwid"), r.get("event_id"),
                        r.get("event_type"), r.get("start_time"), r.get("acknowledge_time"),
                        r.get("state_text"), r.get("severity"), r.get("source")
                });
        int feedback = loadCsv("data/trip_feedback.csv", "trip_feedback.csv",
                "INSERT INTO stg_feedback (business_unit, trip_id, trip_type, trip_date, stwid, route_rating, driver_rating, cab_rating, safety_rating, marshal_rating, creation_time, source_file) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                r -> new Object[]{
                        r.get("business_unit"), r.get("trip_id"), r.get("trip_type"), r.get("trip_date"),
                        r.get("stwid"), r.get("route_rating"), r.get("driver_rating"), r.get("cab_rating"),
                        r.get("safety_rating"), r.get("marshal_rating"), r.get("creation_time")
                });

        log.info("Staging loaded: ride_trip={}, bill={}, alerts={}, feedback={}", ride, bill, alerts, feedback);

        applyDdl("sql/build_trip_fact.sql");
        int tripFactCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM trip_fact", Integer.class);

        applyDdl("sql/create_views.sql");
        // Counting the materialized daily_aggregate is cheap; counting benchmark_view
        // unfiltered is not - it forces the peer-median window function and baseline
        // LATERAL joins across every row with no date filter, unlike any real query
        // against it. Confirmed expensive (~100s) and not representative - don't do it.
        int dailyAggregateRowCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM daily_aggregate", Integer.class);
        log.info("Views built: daily_aggregate ({} rows, materialized) / daily_metric / benchmark_view ready", dailyAggregateRowCount);

        long tookMs = Instant.now().toEpochMilli() - start.toEpochMilli();
        IngestResult result = new IngestResult(ride, bill, alerts, feedback, tripFactCount, tookMs);
        log.info("Ingest complete: {}", result);
        return result;
    }

    private int loadRideTrip() {
        String insertSql = "INSERT INTO stg_ride_trip (business_unit, office, product_type, trip_date, shift_type, trip_id, trip_direction, actual_escort, vendor_id, planned_cab_registration, actual_cab_registration, actual_cab_capacity, planned_km, traveled_km, planned_start_epoch, planned_end_epoch, actual_start_epoch, actual_end_epoch, delay_reason, delay_minutes, route_source, actual_cab_fuel_type, is_driver_nc, is_cab_nc, trip_nodal, plannedemployee_cnt, actualemployee_cnt, noshow_cnt, source_file) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        Function<CSVRecord, Object[]> mapper = r -> new Object[]{
                r.get("business_unit"), r.get("office"), r.get("product_type"), r.get("trip_date"),
                r.get("shift_type"), r.get("trip_id"), r.get("trip_direction"), r.get("actual_escort"),
                r.get("vendor_id"), r.get("planned_cab_registration"), r.get("actual_cab_registration"),
                r.get("actual_cab_capacity"), r.get("planned_km"), r.get("traveled_km"),
                r.get("planned_start_epoch"), r.get("planned_end_epoch"), r.get("actual_start_epoch"),
                r.get("actual_end_epoch"), r.get("delay_reason"), r.get("delay_minutes"),
                r.get("route_source"), r.get("actual_cab_fuel_type"), r.get("is_driver_nc"),
                r.get("is_cab_nc"), r.get("trip_nodal"), r.get("plannedemployee_cnt"),
                r.get("actualemployee_cnt"), r.get("noshow_cnt")
        };
        int may = loadCsv("data/Ride_data _trip-may_2026.csv", "Ride_data _trip-may_2026.csv", insertSql, mapper);
        int june = loadCsv("data/Ride_data _trip-June_2026.csv", "Ride_data _trip-June_2026.csv", insertSql, mapper);
        int july = loadCsv("data/Ride_data _trip-July_2026.csv", "Ride_data _trip-July_2026.csv", insertSql, mapper);
        return may + june + july;
    }

    private int loadCsv(String classpathPath, String sourceFileLabel, String insertSql, Function<CSVRecord, Object[]> mapper) {
        Resource resource = new ClassPathResource(classpathPath);
        int total = 0;
        try (InputStream in = resource.getInputStream();
             Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
             CSVParser parser = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build().parse(reader)) {

            List<Object[]> batch = new ArrayList<>(BATCH_SIZE);
            for (CSVRecord record : parser) {
                Object[] cols = mapper.apply(record);
                Object[] withSource = new Object[cols.length + 1];
                System.arraycopy(cols, 0, withSource, 0, cols.length);
                withSource[cols.length] = sourceFileLabel;
                batch.add(withSource);
                if (batch.size() >= BATCH_SIZE) {
                    jdbcTemplate.batchUpdate(insertSql, batch);
                    total += batch.size();
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                jdbcTemplate.batchUpdate(insertSql, batch);
                total += batch.size();
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load " + classpathPath, e);
        }
        log.info("Loaded {} rows from {}", total, sourceFileLabel);
        return total;
    }

    /** Executes a classpath .sql file. Splits on ";" for schema.sql (many DDL statements);
     * build_trip_fact.sql is one statement so the split is a no-op there. */
    private void applyDdl(String classpathPath) {
        String sql = readClasspathResource(classpathPath);
        for (String statement : sql.split(";")) {
            String trimmed = statement.trim();
            if (!trimmed.isEmpty()) {
                jdbcTemplate.execute(trimmed);
            }
        }
    }

    private String readClasspathResource(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + path, e);
        }
    }
}
