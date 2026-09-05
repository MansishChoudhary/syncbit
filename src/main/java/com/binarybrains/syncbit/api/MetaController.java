package com.binarybrains.syncbit.api;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Lets the frontend discover sensible defaults (latest scanned date, tenants) instead
 * of hardcoding them - decision 10's "now is a date parameter" applies to the UI too. */
@RestController
public class MetaController {

    private final JdbcTemplate jdbcTemplate;

    public MetaController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/api/meta")
    public Map<String, Object> meta() {
        LocalDate latestSignalDate = jdbcTemplate.queryForObject("SELECT MAX(date) FROM signal", LocalDate.class);
        List<String> tenants = jdbcTemplate.queryForList(
                "SELECT DISTINCT tenant_id FROM signal ORDER BY tenant_id", String.class);
        return Map.of(
                "latestSignalDate", latestSignalDate == null ? "" : latestSignalDate.toString(),
                "tenants", tenants);
    }
}
