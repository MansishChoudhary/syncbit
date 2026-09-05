package com.binarybrains.syncbit.api;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.binarybrains.syncbit.narrative.EvidenceService;
import com.binarybrains.syncbit.narrative.Signal;

/**
 * Decision 2: thin non-LLM drill-down. Same SQL the scan/narrative steps already used
 * (EvidenceService), zero inference cost - this endpoint never calls a model.
 */
@RestController
public class EvidenceController {

    private static final int TREND_DAYS = 7;

    private final EvidenceService evidenceService;

    public EvidenceController(EvidenceService evidenceService) {
        this.evidenceService = evidenceService;
    }

    @GetMapping("/api/signals/{id}/evidence")
    public Map<String, Object> evidence(@PathVariable("id") long signalId) {
        Signal s = evidenceService.findById(signalId);
        List<Map<String, Object>> trend = evidenceService.trend(s, TREND_DAYS);
        List<Map<String, Object>> attribution = evidenceService.attributionBreakdown(s);
        return Map.of("signal", s, "trend", trend, "attribution", attribution);
    }
}
