package com.binarybrains.syncbit.scan;

import java.time.LocalDate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Runs a scan once on startup (decision 10: no scheduler, startup + manual trigger).
 * Defaults to the most recent date in trip_fact, since that date has the most trailing
 * history available for baseline/persistence. Must run after IngestRunner (@Order(1)) so
 * trip_fact/benchmark_view exist - see @Order(2) here.
 *
 * Skips if signals already exist for that date, mirroring IngestRunner's pattern - keeps
 * dev-loop restarts fast/cheap. This also avoids a real bug found in Step 4: ScanService
 * deletes existing signal rows for the date before reinserting, but draft rows FK-reference
 * signal_id with no ON DELETE clause - once a draft exists for a signal, an unconditional
 * re-scan on every restart would eventually hit a live foreign-key violation (or, before
 * that, silently discard narrative text/drafts generated for the old signal rows).
 * POST /api/scan still always forces a full delete+reinsert on request.
 */
@Component
@Order(2)
public class ScanRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ScanRunner.class);

    private final ScanService scanService;
    private final JdbcTemplate jdbcTemplate;

    public ScanRunner(ScanService scanService, JdbcTemplate jdbcTemplate) {
        this.scanService = scanService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        LocalDate maxDate = jdbcTemplate.queryForObject("SELECT MAX(trip_date) FROM trip_fact", LocalDate.class);
        if (maxDate == null) {
            log.warn("trip_fact has no rows, skipping startup scan.");
            return;
        }
        Integer existing = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM signal WHERE date = ?", Integer.class, maxDate);
        if (existing != null && existing > 0) {
            log.info("Signals already exist for {}, skipping startup scan. POST /api/scan?date={} to force a rescan.",
                    maxDate, maxDate);
            return;
        }
        log.info("Running startup scan for {} (most recent date in trip_fact)...", maxDate);
        scanService.scan(maxDate);
    }
}
