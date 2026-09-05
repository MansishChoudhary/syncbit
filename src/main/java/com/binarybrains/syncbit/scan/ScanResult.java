package com.binarybrains.syncbit.scan;

import java.time.LocalDate;

public record ScanResult(
        LocalDate date,
        int candidateCount,
        int safetySignalCount,
        int rankedSignalCount,
        int totalSignalCount,
        long tookMillis
) {
}
