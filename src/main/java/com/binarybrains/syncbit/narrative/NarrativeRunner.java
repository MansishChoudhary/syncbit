package com.binarybrains.syncbit.narrative;

import java.time.LocalDate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Runs narrative generation once on startup for the most recent scanned date
 * (decision 10: no scheduler). @Order(3) - after IngestRunner/ScanRunner so signals
 * exist. Deliberately resilient: a missing/invalid LLM API key is an expected state on
 * a dev machine (confirmed: none configured as of Step 4) and must not crash startup -
 * everything built in Steps 1-3 (ingest, views, scan) needs to keep working with zero
 * narrative text if the key isn't there yet.
 */
@Component
@Order(3)
public class NarrativeRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(NarrativeRunner.class);

    private final NarrativeService narrativeService;
    private final JdbcTemplate jdbcTemplate;

    public NarrativeRunner(NarrativeService narrativeService, JdbcTemplate jdbcTemplate) {
        this.narrativeService = narrativeService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        LocalDate maxDate = jdbcTemplate.queryForObject("SELECT MAX(date) FROM signal", LocalDate.class);
        if (maxDate == null) {
            log.warn("No signals exist yet, skipping startup narrative generation.");
            return;
        }
        try {
            log.info("Running startup narrative generation for {}...", maxDate);
            narrativeService.narrateAll(maxDate, false);
        } catch (Exception e) {
            log.warn("Narrative generation failed (likely no valid LLM API key configured yet) - "
                    + "continuing startup without narrative text. POST /api/narrate?date={} to retry "
                    + "once a key is set. Cause: {}", maxDate, e.getMessage());
        }
    }
}
