package com.binarybrains.syncbit.ingest;

public record IngestResult(
        int rideTripRows,
        int billRows,
        int alertRows,
        int feedbackRows,
        int tripFactRows,
        long tookMillis
) {
}
