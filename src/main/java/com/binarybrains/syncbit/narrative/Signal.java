package com.binarybrains.syncbit.narrative;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Maps one row of the signal table (written by C3's ScanService). */
public record Signal(
        long signalId,
        String tenantId,
        LocalDate date,
        String entityType,
        String entityId,
        String office,
        String mode,
        String shiftType,
        String metricName,
        BigDecimal observedValue,
        String referenceType,
        BigDecimal referenceValue,
        BigDecimal deviationMagnitude,
        int persistenceDays,
        BigDecimal finalScore,
        boolean safetyFlag
) {
}
