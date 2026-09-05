package com.binarybrains.syncbit.narrative;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * C4 orchestration: gathers evidence (EvidenceService, pure SQL), calls the model
 * (LlmAdapter, the only place that does), persists results. Never computes a number
 * itself (decision 6) - only ever passes through what SQL already computed.
 */
@Service
public class NarrativeService {

    private static final Logger log = LoggerFactory.getLogger(NarrativeService.class);
    private static final int TREND_DAYS = 7;

    private final EvidenceService evidenceService;
    private final LlmAdapter llmAdapter;
    private final JdbcTemplate jdbcTemplate;

    public NarrativeService(EvidenceService evidenceService, LlmAdapter llmAdapter, JdbcTemplate jdbcTemplate) {
        this.evidenceService = evidenceService;
        this.llmAdapter = llmAdapter;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Runs all three prompts for one scan date. force=true regenerates root-cause notes
     * and the leadership brief (safe, no human workflow attached); escalation drafts are
     * NEVER regenerated once they exist for a signal, force or not - a human may have
     * already approved/rejected one, and silently overwriting that would destroy real
     * workflow state (decision 7). */
    public NarrativeResult narrateAll(LocalDate date, boolean force) {
        int explained = explainSignals(date, force);
        int drafted = draftEscalations(date);
        int briefs = writeLeadershipBriefs(date, force);
        NarrativeResult result = new NarrativeResult(date, explained, drafted, briefs);
        log.info("Narrative complete: {}", result);
        return result;
    }

    private int explainSignals(LocalDate date, boolean force) {
        // "narrative IS NULL" (not also checking for '') is deliberate and load-bearing:
        // an empty-string result is NEVER persisted below, so NULL always means "not
        // successfully explained yet, retry it" - see the empty-content note on
        // draftEscalation in LlmAdapter for why this provider needs that distinction.
        String sql = force
                ? "SELECT signal_id FROM signal WHERE date = ?"
                : "SELECT signal_id FROM signal WHERE date = ? AND narrative IS NULL";
        List<Long> ids = jdbcTemplate.queryForList(sql, Long.class, date);
        log.info("Explaining {} signal(s)...", ids.size());
        int done = 0;
        for (Long id : ids) {
            try {
                Signal s = evidenceService.findById(id);
                List<Map<String, Object>> trend = evidenceService.trend(s, TREND_DAYS);
                List<Map<String, Object>> attribution = evidenceService.attributionBreakdown(s);
                long t0 = System.currentTimeMillis();
                String narrative = llmAdapter.explainSignal(s, trend, attribution);
                log.info("Explained signal {} ({}/{}) in {}ms", id, done + 1, ids.size(), System.currentTimeMillis() - t0);
                if (narrative == null || narrative.isBlank()) {
                    log.warn("Skipping signal {}: LLM returned empty root-cause note. Stays NULL, will retry.", id);
                    continue;
                }
                jdbcTemplate.update("UPDATE signal SET narrative = ? WHERE signal_id = ?", narrative, id);
                done++;
            } catch (Exception e) {
                // A single transient failure (network blip, provider hiccup) must not
                // abort the whole batch - confirmed this happens (StreamResetException
                // mid-run) and previously killed every remaining item silently.
                log.warn("Signal {} failed with an exception, skipping (will retry next run): {}", id, e.toString());
            }
        }
        return done;
    }

    private int draftEscalations(LocalDate date) {
        List<Signal> all = evidenceService.allSignalsForDate(date);
        // one draft per (tenant, vendor): all safety-flagged signals, plus the top
        // non-safety signal per tenant - never regenerate if a draft already exists.
        Map<String, Signal> topNonSafetyPerTenant = new java.util.LinkedHashMap<>();
        List<Signal> candidates = new java.util.ArrayList<>();
        for (Signal s : all) {
            if (s.safetyFlag()) {
                candidates.add(s);
            } else {
                topNonSafetyPerTenant.merge(s.tenantId(), s,
                        (a, b) -> a.finalScore().compareTo(b.finalScore()) >= 0 ? a : b);
            }
        }
        candidates.addAll(topNonSafetyPerTenant.values());
        log.info("Drafting escalations for {} candidate signal(s)...", candidates.size());

        int drafted = 0;
        for (Signal s : candidates) {
            try {
                Integer existing = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM draft WHERE signal_id = ?", Integer.class, s.signalId());
                if (existing != null && existing > 0) {
                    continue;
                }
                List<Map<String, Object>> trend = evidenceService.trend(s, TREND_DAYS);
                List<Map<String, Object>> attribution = evidenceService.attributionBreakdown(s);
                long t0 = System.currentTimeMillis();
                EscalationDraft draft = llmAdapter.draftEscalation(s, trend, attribution);
                log.info("Drafted escalation for signal {} in {}ms", s.signalId(), System.currentTimeMillis() - t0);
                if (draft.body() == null || draft.body().isBlank()) {
                    // Confirmed empirically: this provider intermittently returns empty
                    // content on this prompt even with a generous token budget - never
                    // persist a blank draft into the human review queue (decision 7). Skip
                    // and leave signal_id without a draft row so a later retry (manual
                    // POST /api/narrate) can fill it in - "existing" above only guards
                    // against re-drafting a signal that already has a real draft.
                    log.warn("Skipping signal {}: LLM returned empty escalation draft. Retry via POST /api/narrate.", s.signalId());
                    continue;
                }
                String evidenceRef = "signal:%d".formatted(s.signalId());
                jdbcTemplate.update("""
                        INSERT INTO draft (tenant_id, signal_id, recipient_vendor_id, subject, body, evidence_ref, status)
                        VALUES (?,?,?,?,?,?,'draft')
                        """,
                        s.tenantId(), s.signalId(), s.entityId(), draft.subject(), draft.body(), evidenceRef);
                drafted++;
            } catch (Exception e) {
                log.warn("Draft for signal {} failed with an exception, skipping (will retry next run): {}",
                        s.signalId(), e.toString());
            }
        }
        return drafted;
    }

    private int writeLeadershipBriefs(LocalDate weekEnding, boolean force) {
        LocalDate weekStart = weekEnding.minusDays(6);
        List<String> tenants = jdbcTemplate.queryForList(
                "SELECT DISTINCT tenant_id FROM signal WHERE date BETWEEN ? AND ?",
                String.class, weekStart, weekEnding);

        int written = 0;
        for (String tenantId : tenants) {
            try {
                if (!force) {
                    // "narrative != ''" matters: an empty result is never persisted
                    // below, so this correctly treats a past failed attempt as "not
                    // really done."
                    Integer existing = jdbcTemplate.queryForObject("""
                            SELECT COUNT(*) FROM leadership_brief
                            WHERE tenant_id = ? AND week_ending = ? AND narrative != ''
                            """, Integer.class, tenantId, weekEnding);
                    if (existing != null && existing > 0) {
                        continue;
                    }
                }
                Map<String, Object> rollup = evidenceService.tenantRollup(tenantId, weekStart, weekEnding);
                List<Signal> signals = evidenceService.signalsForRange(tenantId, weekStart, weekEnding);
                long t0 = System.currentTimeMillis();
                String narrative = llmAdapter.writeLeadershipBrief(tenantId, weekEnding, rollup, signals);
                log.info("Wrote leadership brief for {} in {}ms", tenantId, System.currentTimeMillis() - t0);
                if (narrative == null || narrative.isBlank()) {
                    log.warn("Skipping leadership brief for {}: LLM returned empty content. Retry via POST /api/narrate.", tenantId);
                    continue;
                }
                String evidenceRef = "tenant:%s:week:%s".formatted(tenantId, weekEnding);
                jdbcTemplate.update("""
                        INSERT INTO leadership_brief (tenant_id, week_ending, narrative, evidence_ref)
                        VALUES (?,?,?,?)
                        ON CONFLICT (tenant_id, week_ending) DO UPDATE SET narrative = EXCLUDED.narrative, evidence_ref = EXCLUDED.evidence_ref
                        """,
                        tenantId, weekEnding, narrative, evidenceRef);
                written++;
            } catch (Exception e) {
                log.warn("Leadership brief for {} failed with an exception, skipping (will retry next run): {}",
                        tenantId, e.toString());
            }
        }
        return written;
    }
}
