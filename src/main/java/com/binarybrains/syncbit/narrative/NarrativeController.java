package com.binarybrains.syncbit.narrative;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Manual re-run trigger (decision 10). force=true (default) regenerates root-cause
 * notes and the leadership brief; escalation drafts are never regenerated once they
 * exist for a signal (decision 7 - protects human approve/reject state). */
@RestController
public class NarrativeController {

    private final NarrativeService narrativeService;

    public NarrativeController(NarrativeService narrativeService) {
        this.narrativeService = narrativeService;
    }

    @PostMapping("/api/narrate")
    public NarrativeResult narrate(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "true") boolean force) {
        return narrativeService.narrateAll(date, force);
    }
}
