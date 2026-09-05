package com.binarybrains.syncbit.narrative;

import java.time.LocalDate;

public record NarrativeResult(LocalDate date, int signalsExplained, int draftsCreated, int briefsWritten) {
}
