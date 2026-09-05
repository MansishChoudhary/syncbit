package com.binarybrains.syncbit.scan;

import java.math.BigDecimal;

/** One grain x metric row from sql/scan_candidates.sql - a breach worth scoring. */
record Candidate(
        String tenantId,
        String office,
        String vendorId,
        String mode,
        String shiftType,
        String metricName,
        BigDecimal metricValue,
        int sampleSize,
        BigDecimal zScoreVsBaseline,
        BigDecimal baselineAvg,
        BigDecimal slaTargetValue,
        String slaDirection,
        BigDecimal deltaVsSla,
        BigDecimal peerMedian,
        BigDecimal deltaVsPeerPct,
        BigDecimal dqFlagRate,
        int persistenceDays
) {
    boolean isSafety() {
        return "sev1_rate".equals(metricName) || "sos_rate".equals(metricName);
    }
}
