package com.binarybrains.syncbit.api;

import java.time.LocalDateTime;

public record DraftView(
        long draftId,
        String tenantId,
        long signalId,
        String recipientVendorId,
        String subject,
        String body,
        String evidenceRef,
        String status,
        LocalDateTime createdAt,
        String approvedBy,
        LocalDateTime approvedAt
) {
}
