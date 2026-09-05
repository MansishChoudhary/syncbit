package com.binarybrains.syncbit.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Runs ingest once on startup, only if trip_fact isn't already populated (decision 10: no
 * scheduler, startup + manual trigger). Keeps later dev-loop restarts fast; POST /api/ingest
 * always forces a full reload regardless of current state. @Order(1) - must run before
 * ScanRunner (@Order(2)), which needs trip_fact/benchmark_view to exist.
 */
@Component
@Order(1)
public class IngestRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(IngestRunner.class);

    private final IngestService ingestService;
    private final JdbcTemplate jdbcTemplate;

    public IngestRunner(IngestService ingestService, JdbcTemplate jdbcTemplate) {
        this.ingestService = ingestService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (alreadyPopulated()) {
            log.info("trip_fact already populated, skipping startup ingest. POST /api/ingest to force a reload.");
            return;
        }
        log.info("trip_fact is empty or missing, running ingest on startup...");
        ingestService.ingestAll();
    }

    /** True only if trip_fact has data AND the schema is current - checked via the
     * newest table schema.sql defines. Bump this to whatever table/column schema.sql
     * adds next, or force one manual POST /api/ingest after deploying such a change -
     * this check gates whether schema.sql runs at all on a warm restart, and it has
     * already broken ScanRunner once (Step 3, missing signal table) from staying stale. */
    private boolean alreadyPopulated() {
        try {
            Boolean ready = jdbcTemplate.queryForObject("""
                    SELECT (SELECT COUNT(*) FROM trip_fact) > 0
                       AND to_regclass('public.draft') IS NOT NULL
                    """, Boolean.class);
            return Boolean.TRUE.equals(ready);
        } catch (Exception e) {
            return false;
        }
    }
}
