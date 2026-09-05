package com.binarybrains.syncbit.ingest;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Manual re-run trigger (decision 10) — always does a full drop/reload/rebuild. */
@RestController
public class IngestController {

    private final IngestService ingestService;

    public IngestController(IngestService ingestService) {
        this.ingestService = ingestService;
    }

    @PostMapping("/api/ingest")
    public IngestResult ingest() {
        return ingestService.ingestAll();
    }
}
