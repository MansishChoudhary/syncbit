package com.binarybrains.syncbit.narrative;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.stereotype.Component;

/**
 * C4's one adapter class (decision 11) - the only place that calls a model, for exactly
 * three prompts: root-cause phrasing, leadership brief, vendor escalation draft. Every
 * number handed to these prompts already came from a SQL query (decision 6) - this class
 * only judges and writes, never computes.
 */
@Component
public class LlmAdapter {

    private final ChatClient chatClient;

    public LlmAdapter(ChatClient.Builder chatClientBuilder) {
        // Empty advisor list strips Spring AI's auto-configured ToolCallingAdvisor.
        // Confirmed via direct DB inspection: sarvam-105b sometimes emits literal
        // <tool_call>/<arg_key>/<arg_value> XML tags as its answer instead of prose,
        // even though this app registers zero tools - its training apparently reacts to
        // whatever tool-calling scaffolding that advisor adds to the request regardless.
        this.chatClient = chatClientBuilder.defaultAdvisors(List.<Advisor>of()).build();
    }

    public String explainSignal(Signal s, List<Map<String, Object>> trend, List<Map<String, Object>> attribution) {
        String system = """
                You are a transport operations analyst writing a one-paragraph root-cause note for a
                transport manager. Use ONLY the numbers given below - never invent, estimate, or
                recompute a figure. If the data doesn't explain the cause, say so plainly instead of
                guessing. Be concise (2-4 sentences), factual, and specific to this vendor/metric.
                """;
        String user = """
                Signal: vendor "%s" (office %s, mode %s, shift %s) on %s.
                Metric: %s = %s.
                Reference (%s): %s. Deviation: %s.
                %s
                Safety-flagged: %s.

                Trend (last 7 days, this exact vendor/metric):
                %s

                Attribution breakdown (delay_reason, this vendor/date):
                %s

                Write the root-cause note now.
                """.formatted(
                s.entityId(), s.office(), s.mode(), s.shiftType(), s.date(),
                s.metricName(), fmtBd(s.observedValue()),
                s.referenceType(), fmtBd(s.referenceValue()), fmtBd(s.deviationMagnitude()),
                persistencePhrase(s.persistenceDays()), s.safetyFlag(),
                renderRows(trend), renderRows(attribution));

        return chatClient.prompt().system(system).user(user).call().content();
    }

    public String writeLeadershipBrief(String tenantId, LocalDate weekEnding, Map<String, Object> rollup, List<Signal> signals) {
        String system = """
                You are writing a weekly leadership brief for a transport & facilities head. Use ONLY
                the numbers given below - never invent a figure. Cover cost, safety, and experience in
                a few short paragraphs a leader could forward as-is. Name the specific vendors/metrics
                behind the top issues. Be direct, not promotional.
                """;
        String user = """
                Tenant: %s. Week ending %s.

                Weekly rollup:
                %s

                Signals raised this period (most material first):
                %s

                Write the leadership brief now.
                """.formatted(tenantId, weekEnding, renderMap(rollup), renderSignals(signals));

        return chatClient.prompt().system(system).user(user).call().content();
    }

    public EscalationDraft draftEscalation(Signal s, List<Map<String, Object>> trend, List<Map<String, Object>> attribution) {
        // Subject is generated in Java, not asked of the model. Confirmed empirically:
        // asking this model for a rigid multi-field format (SUBJECT:/BODY:, and before
        // that Spring AI's entity() structured-output converter) reliably came back with
        // EMPTY content on all 5/5 attempts regardless of timeout or max-tokens - the
        // model seems to spend its whole reasoning budget deliberating over the format
        // and never emits a final answer. A single free-form prose ask (same shape as
        // explainSignal, which works reliably) sidesteps the issue entirely.
        String system = """
                You draft the BODY TEXT ONLY of a vendor escalation email for a transport manager -
                no subject line, no "Subject:" prefix. Use ONLY the numbers given below - never invent
                a figure. Professional, firm, specific tone. State the issue, the evidence, and ask for
                a corrective action plan with a date. This is a DRAFT for human review before sending -
                do not include a signature block or claim it was already sent.
                """;
        String user = """
                Vendor: %s. Office %s, mode %s, shift %s. Date: %s.
                Metric: %s = %s.
                Reference (%s): %s. Deviation: %s.
                %s

                Trend (last 7 days):
                %s

                Attribution breakdown (delay_reason):
                %s

                Write the email body now.
                """.formatted(
                s.entityId(), s.office(), s.mode(), s.shiftType(), s.date(),
                s.metricName(), fmtBd(s.observedValue()),
                s.referenceType(), fmtBd(s.referenceValue()), fmtBd(s.deviationMagnitude()),
                persistencePhrase(s.persistenceDays()), renderRows(trend), renderRows(attribution));

        String body = chatClient.prompt().system(system).user(user).call().content();
        String subject = "Action needed: %s at %s on %s".formatted(s.metricName(), s.entityId(), s.date());
        return new EscalationDraft(subject, body == null ? "" : body.trim());
    }

    /** Unambiguous persistence phrasing. "breaching on 0 of the last 3 days" reads as
     * self-contradictory when a signal is being raised at all (confirmed via direct API
     * testing: this specific phrasing sent one reasoning model into extended, sometimes
     * budget-exhausting deliberation trying to resolve the apparent contradiction). */
    private String persistencePhrase(int persistenceDays) {
        return persistenceDays <= 0
                ? "Persistence: first time this has been flagged (no breach in the prior 3 days)."
                : "Persistence: breaching for %d consecutive day(s) including today.".formatted(persistenceDays + 1);
    }

    /** BigDecimal.toString() can render as ugly scientific notation ("0E-20") for
     * certain zero-scale values coming out of Postgres NUMERIC computations - confirmed
     * this literal string was landing verbatim in generated prompts and draft text
     * ("...against a reference baseline of 0E-20"). Normalize before it ever reaches a
     * prompt. */
    private String fmtBd(BigDecimal bd) {
        if (bd == null) {
            return "—";
        }
        // Round FIRST, then zero-check the rounded value - Postgres division (e.g.
        // 1/9 for a rate) can produce ~20 decimal digits of real precision, and the
        // model was echoing it verbatim into narrative text ("...was
        // 0.11111111111111111111..."). Checking zero-ness before rounding would also
        // reintroduce the BigDecimal.ZERO.stripTrailingZeros() "0E-x" bug for a value
        // that's non-zero but rounds down to zero at this scale.
        BigDecimal rounded = bd.setScale(4, RoundingMode.HALF_UP);
        if (rounded.compareTo(BigDecimal.ZERO) == 0) {
            return "0";
        }
        return rounded.stripTrailingZeros().toPlainString();
    }

    /** Same normalization applied to a raw SQL result value - trend/attribution rows are
     * Map<String,Object> straight from JdbcTemplate, so BigDecimal cells need the same
     * fix; non-numeric cells (e.g. delay_reason) pass through untouched. */
    private Object fmtVal(Object v) {
        return v instanceof BigDecimal bd ? fmtBd(bd) : v;
    }

    private String renderRows(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return "(none)";
        }
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> row : rows) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            row.forEach((k, v) -> normalized.put(k, fmtVal(v)));
            sb.append(normalized).append("\n");
        }
        return sb.toString();
    }

    private String renderMap(Map<String, Object> map) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        map.forEach((k, v) -> normalized.put(k, fmtVal(v)));
        return normalized.toString();
    }

    private String renderSignals(List<Signal> signals) {
        if (signals.isEmpty()) {
            return "(none)";
        }
        StringBuilder sb = new StringBuilder();
        for (Signal s : signals) {
            sb.append("- %s: %s = %s (vs %s %s), deviation %s, persistence %d day(s)%s%n".formatted(
                    s.entityId(), s.metricName(), fmtBd(s.observedValue()), s.referenceType(), fmtBd(s.referenceValue()),
                    fmtBd(s.deviationMagnitude()), s.persistenceDays(), s.safetyFlag() ? " [SAFETY]" : ""));
        }
        return sb.toString();
    }
}
