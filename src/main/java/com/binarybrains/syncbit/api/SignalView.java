package com.binarybrains.syncbit.api;

import java.math.BigDecimal;
import java.time.LocalDate;

/** C5 read model for the morning brief screen - a signal plus its C4 narrative. */
public record SignalView(
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
        boolean safetyFlag,
        BigDecimal dataQualityConfidence,
        String status,
        String narrative
) {
}
