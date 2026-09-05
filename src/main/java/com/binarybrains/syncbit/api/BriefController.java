package com.binarybrains.syncbit.api;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.binarybrains.syncbit.narrative.EvidenceService;

@RestController
public class BriefController {

    private final BriefService briefService;
    private final EvidenceService evidenceService;

    public BriefController(BriefService briefService, EvidenceService evidenceService) {
        this.briefService = briefService;
        this.evidenceService = evidenceService;
    }

    @GetMapping("/api/briefs/morning")
    public List<SignalView> morning(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) String tenantId) {
        return briefService.morningBrief(date, tenantId);
    }

    @GetMapping("/api/briefs/leadership")
    public Map<String, Object> leadership(
            @RequestParam String tenantId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekEnding) {
        Map<String, Object> brief = briefService.leadershipBrief(tenantId, weekEnding);
        Map<String, Object> rollup = evidenceService.tenantRollup(tenantId, weekEnding.minusDays(6), weekEnding);
        return Map.of("brief", brief, "rollup", rollup);
    }

    @GetMapping("/api/briefs/leadership/tenants")
    public List<String> tenantsWithBrief(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekEnding) {
        return briefService.tenantsWithBrief(weekEnding);
    }
}
