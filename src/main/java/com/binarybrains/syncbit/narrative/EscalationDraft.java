package com.binarybrains.syncbit.narrative;

/** Structured output shape for the vendor escalation prompt (Spring AI entity() converter). */
public record EscalationDraft(String subject, String body) {
}
