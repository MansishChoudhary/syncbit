package com.binarybrains.syncbit.scan;

import java.math.BigDecimal;

/** A candidate after scoring, with its chosen reference point - what actually gets
 * written to the signal table. */
record ScoredSignal(
        Candidate candidate,
        double materiality,
        double severity,
        double persistenceBonus,
        double finalScore,
        double dataQualityConfidence,
        String referenceType,
        BigDecimal referenceValue,
        BigDecimal deviationMagnitude
) {
}
