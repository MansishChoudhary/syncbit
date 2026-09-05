# SyncBit — MoveInSync Hackathon: Agentic Intelligence & Reporting Layer

## ⏱️ THE CONSTRAINT THAT OVERRIDES EVERYTHING ELSE

**One-day hackathon. 5–6 hours of implementation time, from scratch, including the
frontend.** Every choice in this file has already been filtered through that budget.

When extending this project in a future session: if a pattern you're about to reach for
(interface, strategy pattern, service layer, scheduler, abstraction layer, config-driven
generality) would not visibly pay for itself inside the remaining hours, **don't build
it — pick the blunt version and move on.** Optimize for "the simplest thing that demos
end-to-end," not for good long-term architecture. If genuinely torn, re-read the Locked
Decisions below before inventing a new approach — they were chosen for exactly this
tradeoff and should not be relitigated mid-build.

---

## Project purpose

Enterprises move hundreds–thousands of employees daily via home/nodal cabs and shuttles.
Transport managers spend their time assembling data, not acting on it. Build an agentic
layer that **senses, reasons, and acts** on the provided anonymised trip-log dataset
(cab/nodal/shuttle, vendor performance, GPS traces, delays, cost, feedback) — no live
system access, sample data only.

**Evaluation weights — use as tiebreaker for any judgment call:**

| # | Criterion | Weight | What it rewards |
|---|---|---|---|
| 1 | Business impact & experience | 35 | Reduces manager effort, leadership-ready output, shareable without rework |
| 2 | Functionality | 25 | Working, demo-able, end-to-end, on the real dataset |
| 3 | Agentic design & cost at scale | 20 | Inference cost/interaction, latency, efficiency — AI solves a real problem, doesn't decorate |
| 4 | Architecture & code quality | 20 | Deployable into an existing platform, sound judgement |

When two approaches are close, favor the one that scores higher on #1, then #2, then tie
between #3/#4 by which is cheaper to build in the remaining time.

---

## Locked decisions — do not relitigate

**Product**

1. Proactive-first, **plus an additive NL-to-SQL chat** (revised 2026-09-05) — the mandatory requirements still exclude query-only tools as a *sufficient* solution on their own, so chat is a 5th, lowest-priority screen layered on top of the proactive core, never a replacement for it. Section 7 explicitly lists "conversational agent" as a combinable solution form, and "combines two or more forms" is a good-to-have — this is deliberate scope-widening, not scope drift. See decision 11b, component C6, and the chat-specific guardrails below before touching this.
2. Thin non-LLM drill-down replaces chat — click any signal to its evidence (trend, attribution, underlying trips, DQ). Same SQL the scan used. Zero inference cost.
3. Two personas: transport manager (daily brief, alerts, drafted escalations) and transport & facilities head (weekly narrative, vendor scorecard). One data spine, differ only in aggregation window and framing.
4. Five Section-7 forms covered: anomaly detection, proactive alerting, automated reporting/narratives, automated communications, **and now conversational agent** (scoped NL-to-SQL, see decision 1/11b/C6). UI renders agent output — not claimed as an independent "dashboard" form.
5. Every metric shown to a human carries a reference point (prior period, trailing baseline, SLA, peer median) — mandatory per problem statement; bare numbers are a bug.
6. Numbers computed in SQL; LLM only judges and writes — no raw trip rows in any prompt, attribution is a query not a model task. **Chat is the one deliberate exception to "SQL is hand-written":** the LLM authors the SQL itself from a free-text question, but the rule's spirit still holds — the LLM never states a number it didn't get from executing that query, and the query is DB-executed, safety-checked, and read-only (see decision 11b).
7. Draft-and-approve for anything with external effect — escalation emails generated with evidence, queued, never auto-sent.

**Engineering**

8. One denormalized `trip_fact` table — staging tables for raw CSV load, then a wide fact table with joins resolved and derived fields precomputed. No JPA/entity graph/repository abstraction — JdbcTemplate and SQL.
9. SQL views, not a metric-layer abstraction — daily aggregate view (date × site × vendor × mode × shift) + benchmark view (joins to prior period, trailing 4-wk same-weekday baseline, SLA target config table, peer median). This view alone satisfies contextualisation, system-wide.
10. No scheduler — scan runs once on startup plus manual `POST /api/scan?date=...`. "Now" is a date parameter. Cron/queues/event buses out of scope.
11. Exactly three C4 prompts behind one adapter class: root-cause phrasing, leadership brief, vendor escalation draft. Nothing else calls a model **except the chat agent (decision 11b)**. **Built 2026-09-05 against Sarvam AI (`sarvam-105b`) via Spring AI's OpenAI-compatible client** — see C4 notes for the real reliability findings (reasoning-model timeouts, structured-output incompatibility, probabilistic empty-content failures and their fixes) before touching this again.
11b. **Chat = NL-to-SQL, added 2026-09-05, own component (C6), two LLM call sites, not folded into C4's three.** Call 1: question + allowed-table list + current tenant → a single read-only SQL statement. App-side guard before execution (belt-and-braces, not just prompt instruction): reject anything that isn't a single `SELECT`/`WITH` statement, reject any of `INSERT|UPDATE|DELETE|DROP|ALTER|TRUNCATE|GRANT|CREATE`, reject multiple statements (`;` before the end), append a `LIMIT` cap if missing. Execute inside a Postgres **read-only transaction** (`SET TRANSACTION READ ONLY`) with a short `statement_timeout` — this is a DB-enforced backstop that holds even if the regex guard misses something. Call 2: question + result rows → natural-language answer, explicitly stating "no data" rather than guessing when the result set is empty. Allowed tables/views: `trip_fact`, the Step 2 daily-aggregate and benchmark views, `signal` — never the staging tables, never `draft`. Cost note: this is 2 model calls per chat turn vs. 1 for every other agent action — an accepted, explicit tradeoff for the added capability, worth naming plainly in the deck rather than glossing over given evaluation criterion 3 (cost at scale).
12. Frontend: **five** screens, priority order — morning brief → evidence drill-down → leadership brief (print-to-PDF) → action review queue → **chat (lowest priority, added 2026-09-05)**. Angular if team is fluent, else static HTML + Tailwind from Spring `/static` calling REST. Pick speed.
13. `tenant_id` column on every table from the start, no enforcement layer — multi-tenancy is a deck story, not code.
14. Stack: Java/Spring Boot, JdbcTemplate, PostgreSQL. **Decided and running.** `compose.yaml` runs plain `postgres:15-alpine` (swapped from the original `pgvector/pgvector:pg16` scaffold on 2026-09-05 — chat is NL-to-SQL, not RAG, so vector search was never needed; see decision 1/11b). Container name `hackathon_postgres`, host port `5433` (pinned — `5432` collides with a native PostgreSQL 16 Windows service already on this dev machine, confirmed). DB `hackathon_db`, user `postgres`, password `secret` — set both in `compose.yaml`'s environment and as explicit `spring.datasource.*` properties in `application.properties` (the docker-compose service-connection auto-wiring label was removed when these were added; explicit properties are the source of truth now, not auto-detection). `spring-boot-starter-jdbc` + `commons-csv` added to `pom.xml` in Step 1. The `spring-ai-starter-vector-store-pgvector`/`spring-ai-vector-store-advisor` deps are still in `pom.xml`, still vestigial and harmless — fine to leave.

---

## Domain glossary

| Term | Meaning |
|---|---|
| OTA | On-Time Arrival % — trips/employees arriving within SLA window of scheduled time |
| Home pickup | `trip_nodal=HOME` (or `"NA"`, which the dictionary confirms means the same thing) |
| Nodal pickup | `trip_nodal=NODAL` |
| Shuttle | `trip_nodal=SHUTTLE` |
| `product_type` vs `trip_nodal` | **Confirmed independent dimensions, not one implying the other** (spot-checked all 3 months). `product_type=BUS` occurs with every `trip_nodal` value including `HOME` — "BUS → shuttle" is **not** a valid mapping, despite being the intuitive guess. Use `trip_nodal` alone for the home/nodal/shuttle mode; use `product_type` as a separate "service type" dimension |
| `SPOT_2.0` | `trip_nodal="NA"` on 100% of `SPOT_2.0` rows across all three months (confirmed) — same as a home trip. Still treated as its own mode bucket rather than folded into "home," since it's a distinct service type |
| No-show | Employee booked but did not board — `noshow_cnt` at trip grain, `is_no_show`/`boarding_status` at employee-leg grain |
| Dead mileage | Classic meaning: repositioning km with no passenger. **This dataset has no separate empty-leg field** — the closest proxy is the gap between `planned_km` and `traveled_km` (route/distance variance), which is detour or inefficiency, not true dead mileage. Label it as such in the UI, don't call it "dead mileage" without a caveat |
| Escort compliance | **This dataset has `actual_escort` (was an escort present) but no `escort_required` flag.** There is no ground truth for "should have had an escort." MVP metric is escort *presence rate*, trended/benchmarked — not a compliance gap. A `required` proxy (e.g. night shifts by `shift_type`) is a stretch-goal config rule, not a fact the data asserts |
| Fill rate / occupancy | `actualemployee_cnt` ÷ `actual_cab_capacity` (trip grain, already in `ride_data_trip` — no need to touch `emp_data`) |
| SLA | Contractual/target service level, e.g. OTA ≥ 90% |
| Materiality | How much a deviation matters in absolute terms (employees affected, ₹ cost) — filters noise so small blips don't become signals |
| Signal | One scored, triaged anomaly/event row emitted by C3 — one thing worth telling a human |
| Brief | Persona-facing narrative bundling signals + narrative (morning brief / leadership brief) |
| Scorecard | Vendor-level rollup vs SLA/peers, feeds leadership brief and escalations |
| `business_unit` | The dataset's client-account field (`vanta-Aus`, `catalyst-Sac`, `orbit-Slc`, `vanta-Sea`, `pinnacle-Slc`). **Treat as `tenant_id`** — it's the only field that plausibly plays that role |
| `stwid` | Rider/employee id. `0`/`"0"` is a placeholder for non-rider or trip-level rows — filter it out before any per-employee aggregation |

---

## The six components

**C1 — Ingest & fact build**
- In: the five raw CSVs (`ride_data_trip` ×3 monthly files, `emp_data`, `bill_data`, `alerts_data`, `trip_feedback`) → staging tables, one per file.
- Does: normalises join keys (strip commas from `trip_id`/`stwid`, cast to one type per side — `bill_data`'s `trip_id` has no commas, `emp_data`'s is already `int64`, the rest are comma-formatted strings), normalises the four different date/epoch formats, treats the literal string `"NA"` in `trip_nodal` as null explicitly (CSV/COPY import will not do this for you), then rolls `bill_data`/`alerts_data`/`trip_feedback` up to trip grain and joins onto the `ride_data_trip` hub. Computes per-trip derived fields in one pass — delay vs scheduled (already provided as `delay_minutes`), detour ratio (`traveled_km`/`planned_km`), fill rate (`actualemployee_cnt`/`actual_cab_capacity`), escort presence rate, alert counts by severity, avg feedback ratings, billed cost/km, CO2 (config emission factor × `traveled_km` by `actual_cab_fuel_type`).
- Out: `trip_fact`, one row per `trip_id`. Idempotent re-runs (re-running for a date replaces that date's rows, doesn't duplicate).
- Must NOT: call an LLM, apply business judgement, aggregate across trips. Must NOT pull in `emp_data` (1.6M employee-leg rows) unless a specific screen needs employee-level breakdown (gender/role/no-show-reason) — `ride_data_trip` already carries trip-level `plannedemployee_cnt`/`actualemployee_cnt`/`noshow_cnt`, which covers fill rate and no-show rate without the extra join. Treat `emp_data` as a stretch-goal enrichment, not an MVP dependency.

**C2 — Aggregate & benchmark views**
- In: `trip_fact` (+ `sla_target` config table).
- Does: `daily_aggregate` (wide, 20 columns, grain tenant×date×office×vendor×mode×shift) → unpivoted into `daily_metric` (long, 11 headline metrics) → `benchmark_view` (one generic self-join adding prior period, trailing 4-wk same-weekday baseline, SLA target, peer median, and sign-normalized deltas to every metric at once). Rebuilt fresh every ingest via `sql/create_views.sql`, called right after `trip_fact` is built.
- Out: three SQL views (plain, not materialized — 1.3M-row `benchmark_view` output queries fine unmaterialized at this dataset's scale, per-query, no perf issue hit).
- Must NOT: contain Java business logic — pure SQL. No LLM.

**C3 — Scan & triage (built, verified 2026-09-05)**
- In: `benchmark_view`/`daily_aggregate` for a given date (`sql/scan_candidates.sql`).
- Does: candidate = `|z_score_vs_baseline| >= 2` OR `delta_vs_sla < 0` OR (`sev1_rate`/`sos_rate` metric with any nonzero value — safety doesn't wait for statistical significance). `sample_size >= 5` guards tiny groups. Java (`ScanService`) scores each candidate: `severity` = max(`|z|/3`, `|delta_vs_sla|/target`, capped at 1; safety candidates floor at 1.0), `materiality` = `min(sample_size,200)/200`, `persistence` = `min(trailing-3-day breach count,3)/3`, `final_score = materiality × severity × (1 + persistence)`. Gates non-safety candidates on `data_quality_confidence = 1 - dq_flag_rate` (< 0.5 excluded). Dedups non-safety candidates to one per `(tenant_id, vendor_id)` (highest `final_score` wins). Ranks, takes top 8 + all safety-flagged (which skip both the confidence gate and the top-N cap). Reference point per signal: `sla` if breaching a target, else `baseline`, else `peer`.
- Out: rows in `signal` table (persona hardcoded `'manager'` for now — see scope note below).
- Must NOT: call an LLM. Zero inference cost by design (confirmed: pure SQL + Java arithmetic).
- Idempotent by construction: `scan()` always `DELETE FROM signal WHERE date = ?` before reinserting.
- **Scope cut, deliberate:** all signals are `persona='manager'`. The head's weekly brief (Step 4) is expected to roll up/summarize manager signals over a week rather than needing its own separately-scanned signal set — consistent with decision 3 ("one data spine, differ only in aggregation window and framing"). Revisit only if the head's brief turns out to need office/tenant-wide signals that vendor-level scan output can't answer.
- **Real bug found and fixed:** `sla_target` originally included `escort_presence_rate` at 0.80 — but `escort_presence_rate = 0` on 72.3% of grain-groups (median 0). That target made ~72% of vendors "fail" identically, flooding the top-8 with homogeneous low-information signals (confirmed empirically: 6 of 8 ranked signals were all `escort_presence_rate` breaches before the fix). Removed that SLA row entirely — baseline/peer comparison (already available for every metric) is the right tool here, not a fixed target, since there's no `escort_required` ground truth (see glossary). After removing it, the signal set became genuinely diverse (cost anomalies, no-show breaches, a delay improvement, safety events).
- **Real perf bug found and fixed:** first scan took **206 seconds**. Root cause: `benchmark_view` (already 2 internal `LATERAL` joins) was a plain view over `trip_fact`'s 615K rows, and `scan_candidates.sql` added a 3rd `LATERAL` join referencing it — every reference re-ran the full `GROUP BY` aggregation from scratch. Fixed by making `daily_aggregate` a `MATERIALIZED VIEW` (120,713 rows, rebuilt once per ingest) instead of a plain view — everything downstream now scans a small precomputed table instead of re-aggregating. Result: **~2–5 seconds per scan**, a ~40–100x improvement. `CLAUDE.md`'s own C2 note ("materialized views if perf demands it — unlikely at sample-dataset scale") turned out to be wrong at this exact join depth; don't assume "small dataset" means "no materialization needed" once views reference each other through multiple `LATERAL`/window-function layers.
- **Bootstrapping gap found and fixed:** `IngestRunner`'s skip-check only looked at `trip_fact` row count, not schema currency. Adding the `signal` table in this step broke on the very next restart (schema.sql only runs inside `ingestAll()`, which the skip-check bypasses) — `ScanRunner` crashed the whole app on `relation "signal" does not exist`. Fixed by checking `to_regclass('public.signal') IS NOT NULL` too, not just row count. **Guardrail for future steps:** any time a new table is added to `schema.sql`, this check needs the new table added to its "is schema current" condition, or force one manual `POST /api/ingest` after deploying the change and don't rely on the auto-skip logic that day.

**C4 — Narrative & actions (built, verified 2026-09-05)**
- In: `signal` rows + SQL evidence — `EvidenceService` provides `trend` (7-day metric history from `benchmark_view`), `attributionBreakdown` (delay_reason counts from `trip_fact`, a SQL aggregate, not raw rows — decision 6), and `tenantRollup`/`signalsForRange` for the leadership brief. Never raw trip rows in a prompt.
- Does: `LlmAdapter` (the one adapter class) — three prompts, all plain free-form `.content()` calls (not Spring AI's `entity()` structured-output converter, which this provider doesn't support — confirmed, see below). `NarrativeService` orchestrates: `explainSignals` (writes to `signal.narrative`), `draftEscalations` (safety-flagged signals + top non-safety signal per tenant, into `draft`), `writeLeadershipBriefs` (one per tenant with signals in the trailing week, into `leadership_brief`).
- Out: root-cause text on `signal.narrative`, rows in `draft` (status `draft`, never sent), rows in `leadership_brief`.
- Must NOT: compute any number itself (verified — every figure in generated text traces to a SQL-supplied value). Must NOT auto-send anything external (verified — `draft.status` starts and stays `'draft'`, no send path exists in code).
- **Provider used: Sarvam AI** (`sarvam-105b`) via Spring AI's OpenAI-compatible client (`spring.ai.openai.base-url=https://api.sarvam.ai/v1` — note the SDK's own `baseUrl` already includes `/v1`, don't double it). Key read from `SARVAM_API_KEY` env var, never hardcoded in the tracked `application.properties`.
- **Real findings from getting this working, all load-bearing for future sessions:**
  1. `sarvam-105b` is a reasoning model with a non-standard `reasoning_content` field — confirmed via direct `curl` test it burned 1,176 completion tokens on a trivial "say hello" prompt. Default 60s timeout × 3 retries compounds into ~2min before failing; set `spring.ai.openai.chat.timeout=180s` and `max-retries=1`.
  2. Spring AI's `entity()` structured-output converter (used for the escalation draft's subject/body split) failed immediately against this provider — it doesn't support the underlying `response_format` mechanism. Fixed by using plain `.content()` + manual parsing for everything, matching what already worked.
  3. Escalation drafts came back with **empty content on 100% of early attempts**, even after switching to plain-text output. Root-caused via a direct API call that exposed the model's `reasoning_content`: it got stuck deliberating over ambiguous prompt phrasing ("breaching on 0 of the last 3 days" reads as self-contradictory when persistence is 0), burning nearly its entire token budget on reasoning before an answer. Fixed the phrasing (`LlmAdapter.persistencePhrase` — distinguishes "first time flagged" from "breaching for N consecutive days") and raised `spring.ai.openai.chat.options.max-tokens` to 8192. Success rate went from 0/5 to ~4/5 per run.
  4. **Even after both fixes, this specific model is still probabilistically unreliable** (~80% success per attempt, not 100%) — accepted as a real, documented characteristic of this provider/model choice rather than something worth further tuning cycles. Mitigated with a resilience rule, not a prompt trick: `NarrativeService.draftEscalations` never persists an empty draft into the human review queue (decision 7's approval workflow must never show broken entries) — it skips and logs a warning, and the existing "skip if a draft already exists for this signal" check means a later retry (manual `POST /api/narrate`) naturally fills in only what's missing.
  5. `ScanRunner` originally ran unconditionally on every startup, deleting and reinserting `signal` rows — but `draft.signal_id` has an FK to `signal` with no `ON DELETE` clause, so this would eventually crash with a live foreign-key violation once a draft existed (and in the meantime, silently discarded narrative text on every restart). Fixed by making `ScanRunner` skip if signals already exist for the target date, mirroring `IngestRunner`'s pattern. The FK is deliberately left as `RESTRICT` (not `CASCADE`) — a manual force-rescan after drafts exist should fail loudly rather than silently destroy human review state.
  6. `IngestRunner`'s schema-currency check needed bumping again (now checks `draft`, not `signal`) — see the recurring guardrail this pattern created, noted under Guardrails below.

**C5 — API & UI (built, verified 2026-09-05)**
- In: signals, evidence queries, briefs, drafts, chat messages via REST. New `api` package: `BriefController`/`BriefService` (morning + leadership brief reads), `EvidenceController` (thin wrapper over `EvidenceService` — reused as-is from C4, no new SQL), `DraftController`/`DraftService` (list + approve/reject, status-only, no send path anywhere in the codebase), `MetaController` (latest date + tenant list, so the UI never hardcodes either).
- Does: four screens built (chat, the 5th/lowest-priority one, deferred — Step 4b not built yet, consistent with the cut list). Static HTML + Tailwind-via-CDN + vanilla JS in `src/main/resources/static/` (`index.html` morning brief, `evidence.html` drill-down, `leadership.html` print-to-PDF via `window.print()` + `@media print`, `drafts.html` action queue). No build step, no npm — matches decision 12's "pick speed."
- Out: rendered UI, approved/rejected draft state changes.
- Must NOT: contain business logic — thin rendering over C1–C4/C6 outputs (verified: all four controllers just query/project, no scoring/business rules).
- **Verified in an actual browser (claude-in-chrome), not just curl — this caught bugs curl alone would have missed:**
  1. **Date serialization bug**: `queryForList`'s generic `Map<String,Object>` path returns `java.sql.Date` for DATE columns, which Jackson serializes as a UTC instant via the JVM's default timezone (IST here) — every displayed date was off by one day (confirmed: DB had 07-27..07-31, API returned 07-26..07-30). Fixed by casting to `::text` in SQL wherever a date crosses into a generic-Map query result (`EvidenceService.trend`, `BriefService.leadershipBrief`) — sidesteps the whole `java.sql.Date`/Jackson pitfall entirely rather than fighting Jackson config.
  2. **Frontend formatting bug**: a broken ternary ran the `attribution` table's string column (`delay_reason`) through a numeric formatter, rendering "NaN" instead of "NODELAY"/"EMPLOYEE". Fixed by only formatting actual `typeof v === 'number'` values, passing everything else through as-is.
  3. **A much bigger one — empty-content persistence was not just an escalation-draft problem**: found 7 of 11 signal narratives and 1 of 3 leadership briefs were silently empty strings in the DB. The Step 4 resilience fix only covered `draftEscalations`; `explainSignals` and `writeLeadershipBriefs` had no equivalent guard, so past failed attempts got persisted as `''`, and since the retry filter only checked `IS NULL`, an empty string permanently locked that item out of retry (`'' IS NOT NULL` in SQL). Fixed the same resilience pattern (skip persisting on empty, let `IS NULL`/`!= ''` checks naturally retry) consistently across all three C4 write paths.
  4. **`BigDecimal` precision leaking into generated text — two rounds.** Round one: certain zero-scale values from Postgres NUMERIC computations render as `"0E-20"` via Java's default `toString()`, landing verbatim in prompts and draft/narrative text ("against a reference baseline of 0E-20"). Round two (found afterward, by a human reading actual rendered narrative text in the UI, not by any automated check): a genuine non-zero repeating decimal from Postgres division (e.g. 1/9 for a rate) carries ~20 real decimal digits, and the model echoed it verbatim too ("sev1_rate on 2026-07-31 was 0.11111111111111111111"). `LlmAdapter.fmtBd()` now rounds to 4 decimal places FIRST (`setScale(4, RoundingMode.HALF_UP)`), then zero-checks the *rounded* value, then `stripTrailingZeros().toPlainString()` — checking zero-ness before rounding would reintroduce the `0E-x` bug for a value that's non-zero but rounds down to zero at 4 decimals. The frontend's `Number()`-based JS formatting already rounds/masks both of these in the UI's stat lines, which is exactly why neither was caught until reading the raw generated narrative paragraph, not the numbers displayed next to it. **Lesson reinforced: the stat line and the prose sentence below it can disagree — check both.**
  5. **No per-item exception handling — one transient failure could abort an entire batch**: a live `okhttp3.internal.http2.StreamResetException` (transient network blip talking to Sarvam) on one draft call propagated all the way up and killed the whole `POST /api/narrate` request, silently abandoning every remaining item in that run. Wrapped each item in `explainSignals`/`draftEscalations`/`writeLeadershipBriefs` in its own try-catch — one failure now logs and continues instead of sinking the batch. This is a real production-quality gap that would have been easy to ship undetected (it only surfaces under real network conditions, not local testing).
  6. **`sarvam-105b` occasionally emits literal `<tool_call>`/`<arg_key>`/`<arg_value>` XML tags as its answer instead of prose** — non-empty, so it slipped past the empty-content check entirely and was caught only by actually reading the rendered narrative text in a browser. Root cause: Spring AI's `ChatClient.Builder` auto-configures a `ToolCallingAdvisor` into the default advisor chain even with zero tools registered, and this model's training reacts to whatever tool-calling scaffolding that advisor adds to the request. Fixed by building the `ChatClient` with `defaultAdvisors(List.of())`, stripping the advisor chain entirely. **This is the single most important guardrail from Step 5: emptiness is not the only failure shape to check for with this model — always spot-check actual rendered text, not just "did a string come back."**

**C6 — Conversational query agent (added 2026-09-05, decision 1/11b)**
- In: a free-text question from a manager/head, plus the asker's current `tenant_id`.
- Does: NL→SQL (call 1, constrained to `SELECT`/`WITH`, an explicit table allowlist, and the asker's tenant), executes it read-only with a statement timeout and row cap, then SQL-result→NL (call 2) to phrase the answer. Shows the underlying SQL and result table alongside the answer (same "show your work" spirit as decision 2's drill-down).
- Out: a chat answer + its SQL + its result rows, rendered in the chat screen. Nothing persisted, nothing sent externally.
- Must NOT: execute anything but a single read-only `SELECT`/`WITH` (enforced by the DB read-only transaction, not just the prompt). Must NOT query staging tables or `draft`. Must NOT invent a number when the query returns nothing — say so. Must NOT become the primary interface — it's additive and first on the cut list (decision 1).

**Where the real architecture would differ** (for the deck, not the build): a scheduler
(cron/event-driven ingest) replacing startup+manual trigger; a metric-definition registry
replacing hardcoded views once metric count grows past a handful; enforced row-level
tenant isolation (RLS or a filtering interceptor) replacing the bare `tenant_id` column;
model tiering (cheap/small model for routine narratives, larger model reserved for
judgement-heavy escalation drafts) to control cost at scale; a real message queue for
draft approval/send instead of in-process state.

---

## Metric definitions

Confirmed against the real dataset (`src/main/resources/data/`, dictionaries in its
`Dictionary/` subfolder — see Table Shapes below for the five source files). Dimensions
for all: `trip_date`, `office`, `vendor_id`/`vendor`, mode (`product_type`/`trip_nodal`),
`shift_type`, `business_unit` (tenant).

| Metric | Definition | Source |
|---|---|---|
| OTA % | Trips with `delay_minutes` ≤ SLA threshold ÷ total trips | `ride_data_trip.delay_minutes` (strip commas) |
| Delay minutes | `delay_minutes`, avg/median; `delay_reason` gives the breakdown for attribution (`NODELAY`/`TRAFFIC`/`DRIVER`/`EMPLOYEE`) for free | `ride_data_trip` |
| No-show rate | `noshow_cnt` ÷ `plannedemployee_cnt` | `ride_data_trip` (trip grain — no `emp_data` join needed) |
| Fill rate / occupancy | `actualemployee_cnt` ÷ `actual_cab_capacity` | `ride_data_trip` |
| Route/distance variance ("dead mileage" proxy) | `traveled_km` − `planned_km` (or the ratio); **not true dead mileage** — see glossary. Label accordingly in UI | `ride_data_trip` |
| Escort presence rate | `actual_escort = true` ÷ total trips, trended/benchmarked | `ride_data_trip.actual_escort` — no `escort_required` field exists; do not call this "compliance" |
| Non-compliance rate | `is_driver_nc` / `is_cab_nc` true ÷ total trips | `ride_data_trip` (reconcile dtype drift — bool in Jun/Jul, object in May) |
| Safety/alert rate | Alert count ÷ trip count, by `event_type` and `severity`; Sev-1 volume spikes are the clearest proactive-alert trigger in this dataset | `alerts_data`, rolled up to trip/day/vendor grain. Filter `stwid="0"` for per-rider views (42.9% of alerts are trip-level, confirmed). **`severity="False"` is not a rare stray value — it's 15,037/51,699 rows (~29%), always paired with `state_text=CLOSED` and `source=MOBILE`.** Treat it as "severity unrecorded" (map to null), not as a value to drop the row for — the alert itself (e.g. a real `DEVICE_NOT_REACHABLE` event) is still valid signal |
| Cost per trip | `trip_cost` (strip commas), `SUM`/`GROUP BY trip_id` | `bill_data` — row count (620,942) exceeds distinct `trip_id` (613,784, matches dictionary), confirmed **not** a 1:1 join |
| Cost per km | `trip_cost` ÷ `total_trip_km`, **only where `total_trip_km > 0`** | `bill_data` — confirmed **248,191/620,942 rows (~40%) have `total_trip_km=0`**, far more than "a meaningful share." Cost-per-trip is the reliable primary cost metric; cost-per-km is a secondary cut over the ~60% with real distance, not a headline number |
| CO2 (sustainability) | `traveled_km` × emission factor by `actual_cab_fuel_type` | `ride_data_trip`; emission factors are our own config, not dataset-derived — label as illustrative in UI |
| CSAT / feedback | Avg of `route_rating`/`driver_rating`/`cab_rating`/`safety_rating` (0–5); marshal handled separately | `trip_feedback`, aggregated by `trip_id`. Confirmed: route/driver/cab/safety ratings are essentially never 0 (2 rows each out of 512,873 — average them as-is). **`marshal_rating=0` is 473,692/512,873 rows (92.4%)** — this is "no marshal on this leg," not a real zero score. Exclude `marshal_rating=0` from the marshal CSAT average entirely (average only over the ~7.6% of legs that had one — this is also your escort/marshal-experience metric for the trips where `actual_escort=true`) |

**Dropped from the original plan, now that the real schema is known:** GPS coverage % and
raw-GPS-trace-derived speeding events. The dataset does not include raw GPS pings —
`alerts_data.event_type = OVER_SPEEDING` (and `VEHICLE_STOPPAGE`, `DEVICE_NOT_REACHABLE`)
are the closest signal, already pre-derived by MoveInSync. Treat them as event counts, not
something to recompute from a trace we don't have. Roster-match rate is also dropped —
there is no roster file; `plannedemployee_cnt` vs `actualemployee_cnt` already covers the
planned-vs-actual gap this metric was meant to capture.

Long-term this table belongs in `docs/metrics.md` — keep it here for now so it loads with
everything else in one file.

---

## Table & signal shapes (prose — no DDL, decide exact types during Step 1)

**Source data** lives in `src/main/resources/data/` — five files, full per-file
dictionaries in its `Dictionary/` subfolder (read `Dictionary/README.md` first, it's the
map): `Ride_data _trip-{may,June,July}_2026.csv` (one row = one trip, ~615K rows across
three months — the hub), `emp_Data.csv` (one row = one employee's leg, 1.64M rows),
`bill_data.csv` (one row = one billed line item, 621K rows), `alerts_data.csv` (one row =
one safety/compliance alert, 52K rows), `trip_feedback.csv` (one row = one rider's rating
of one leg, 513K rows). All five join on `trip_id`; the per-rider files also carry `stwid`.
**Known quirks, confirmed against the real files (not just the dictionary) — design
around these, don't discover them mid-build:**
- `trip_id` is comma-formatted in every file except `bill_data` (plain numeric string)
  and `emp_data` (clean `int64`) — normalise before any join.
- `trip_nodal` uses the literal string `"NA"` for home trips, which a raw CSV import will
  not treat as null. `product_type=SPOT_2.0` is consistently `trip_nodal="NA"` too (100%
  across all three months) — same as any other home trip, nothing unusual there.
- `product_type=BUS` does **not** imply shuttle mode — confirmed it occurs with every
  `trip_nodal` value. Use `trip_nodal` alone for home/nodal/shuttle.
- Date/epoch formats differ per file (dictionary point 3–4).
- `is_driver_nc`/`is_cab_nc`/`planned_km` dtypes drift between the three monthly
  `ride_data_trip` files (confirmed: May has 4 rows with a genuinely empty
  `is_driver_nc`/`is_cab_nc` field, correctly treated as unknown/null; July's `planned_km`
  has at least one comma-formatted value, e.g. `"1,092.56"` — strip commas from
  `planned_km` too, not just `traveled_km`, when casting to numeric).
- **`bill_data.trip_id` is not always numeric** — 160 rows carry the literal string
  `"OverHead"` instead of a trip id (an administrative/fixed charge not tied to any trip,
  undocumented in the dictionary). Filter these out before casting to a join key; they
  don't belong to any `trip_fact` row. `bill_data.slab_name` also uses the literal string
  `"null"` (four characters) rather than a true empty field on some rows — convert to a
  real null.
- **A methodology note for future Step-0-style spot-checks on this data:** a naive
  `awk -F','`/FPAT-based split silently drops zero-length unquoted fields, which shifts
  every later column left by one for that row and produces phantom-looking values
  (this cost real time in Step 1 chasing a "RENTLZ in delay_minutes" ghost that was
  purely a parsing-tool bug, not a data issue). Use a real CSV parser (Commons CSV in
  Java, `Import-Csv` in PowerShell, or gawk's `FPAT="([^,]*)|(\"[^,]*\")"` — note the `*`,
  not `+`) for anything beyond a quick grep.
- `alerts_data.severity="False"` is **systematic, not a rare stray** — 15,037/51,699 rows
  (~29%), always co-occurring with `state_text=CLOSED` and `source=MOBILE`. Map to null
  ("severity unrecorded"), keep the alert row.
- `emp_data.planned_km`/`traveled_km` going negative is real but rare — confirmed only 1
  and 43 rows respectively out of 1,637,906. A simple `WHERE ... >= 0` filter or clip is
  sufficient; don't over-engineer this one.
- `bill_data.total_trip_km = 0` on ~40% of rows (248,191/620,942), and `bill_data` has
  more rows (620,942) than distinct `trip_id` (613,784) — it needs a `SUM`/`GROUP BY`
  before joining to `trip_fact`, never a plain 1:1 join.
- `trip_feedback.marshal_rating = 0` on 92.4% of rows (473,692/512,873) — means "no
  marshal," not a real zero; exclude from the marshal CSAT average (see Metric
  Definitions). The other four rating columns are clean (2 zero-rows each, negligible).
- `business_unit` confirmed identical across all five files (same 5 values, no case
  variants, no strays) — the tenant-id assumption is solid.
- `fill_rate` (`actual_employee_cnt / actual_cab_capacity`) exceeds 1.0 on 1,494 trips —
  more riders boarded than the cab's stated capacity. Minor, real, not investigated
  further — display as-is or cap for charting, don't silently drop these trips.

**Staging tables** — one per source file, columns as-is (all text/varchar is fine at this
stage), plus a `source_file`/`load_batch` column for traceability. Load raw, clean in the
transform step, not during load.

**`trip_fact`** — one row per trip, grain matches the `ride_data_trip` hub (see C1).
**Key is `(tenant_id, trip_id)`, not `trip_id` alone** — confirmed during Step 1 that
`trip_id` collides across tenants/months (the same numeric `trip_id` is reused by
different `business_unit`s in different months; `(business_unit, trip_id)` is collision-
free across all 615,546 rows, verified exhaustively). Every join from staging into
`trip_fact` (billing, alerts, feedback) must match on both columns — joining on `trip_id`
alone silently attributes one tenant's cost/alerts/feedback to a different tenant's trip.
Core fields carried straight from `ride_data_trip`: trip_id, tenant_id (=`business_unit`),
office, product_type, trip_nodal (mode refinement), trip_date, shift_type, trip_direction,
vendor_id, actual_cab_registration, actual_cab_capacity, actual_cab_fuel_type, planned_km,
traveled_km, planned/actual start/end timestamps (parsed from epoch), delay_minutes,
delay_reason, route_source, is_driver_nc, is_cab_nc, actual_escort,
plannedemployee_cnt, actualemployee_cnt, noshow_cnt.
Derived/joined-in fields: fill_rate, detour_ratio, escort_presence (=actual_escort, see
glossary caveat), co2_kg, billed_cost (SUM from `bill_data`), billed_km (SUM
`total_trip_km`), contract, slab_name, alert_count, sev1_count/sev2_count/sev3_count,
has_sos_alert, avg_route_rating, avg_driver_rating, avg_cab_rating, avg_safety_rating,
avg_marshal_rating, feedback_count, data_quality_flag (unmatched_billing /
zero_km_billed / stray_severity_value / dtype_drift_row / etc.), source_row_ref
(traceability for drill-down back to staging).

**`daily_aggregate`** (built, verified) — grain `tenant_id × trip_date × office ×
vendor_id × mode × shift_type`, over `trip_fact`. Columns: `trip_count`,
`avg_delay_minutes`, `median_delay_minutes`, `ota_pct` (on-time = `delay_minutes <= 15`,
illustrative threshold), `no_show_rate`, `avg_fill_rate`, `avg_detour_km`, `cost_total`,
`cost_per_trip`, `escort_presence_rate`, `noncompliance_rate`, `alert_rate`, `sev1_rate`,
`sos_rate`, `avg_co2_kg`, `avg_route_rating`, `avg_driver_rating`, `avg_cab_rating`,
`avg_safety_rating`, `avg_marshal_rating`, `dq_flag_rate`. (Superseded the original sketch
— dropped `speeding_event_rate`/`gps_coverage_pct`, added what `trip_fact` actually has.)

**`daily_metric`** (built, internal plumbing, not a "third deliverable" view) — unpivots
`daily_aggregate`'s 11 headline rate/average metrics into one row per
`(grain, metric_name, metric_value)`. Exists purely so `benchmark_view` can do one generic
self-join instead of repeating prior/baseline/sla/peer columns per metric — add a metric
to the `UNION ALL` here and it's benchmarked for free. The 11 metrics: `ota_pct`,
`avg_delay_minutes`, `no_show_rate`, `avg_fill_rate`, `escort_presence_rate`,
`noncompliance_rate`, `alert_rate`, `sev1_rate`, `cost_per_trip`, `avg_co2_kg`,
`avg_safety_rating`. (Route/driver/cab/marshal ratings stay in `daily_aggregate` for
drill-down display but aren't separately benchmarked — a scoped cut, not an oversight.)

**`benchmark_view`** (built, verified) — one row per `(grain, metric_name)`, joining
`daily_metric` to itself for `prior_value` (trip_date − 1, same grain), `baseline_avg`/
`baseline_stddev` (trailing 4-wk same-weekday, i.e. trip_date − {7,14,21,28}), `sla_target`
(by `metric_name`, optionally scoped by `tenant_id`/`mode`), and `peer_median`
(`PERCENTILE_CONT(0.5)` across vendors sharing the same tenant+office+mode+shift+date).
Also computes `delta_vs_prior`, `pct_change_vs_prior`, `z_score_vs_baseline`,
`delta_vs_sla` (sign-normalized: negative always means "breaching," regardless of
direction), `delta_vs_peer_pct`. **Verified finding:** ~25% of grain-groups have only one
vendor, so their peer group is size 1 (`peer_median` = self, `delta_vs_peer_pct` = 0) —
not a bug, just uninformative; Step 3's scan should not treat a peer-deviation signal as
meaningful when the peer group is size 1.

**`sla_target`** (config table, built) — `metric_name`, `tenant_id` (nullable = all),
`mode` (nullable = all), `target_value`, `direction` (`higher_better`/`lower_better`).
Seeded with 6 illustrative targets (not dataset-derived, labeled as such in UI): `ota_pct`
0.90, `avg_delay_minutes` 15, `no_show_rate` 0.05, `escort_presence_rate` 0.80,
`noncompliance_rate` 0.02, `avg_safety_rating` 4.5. Rate metrics are fractions (0–1)
throughout, not percentages — stay consistent with this in any new code. **Known
limitation, not hit yet:** the `sla_target` join assumes at most one matching row per
`(metric_name, tenant_id, mode)` combination — adding an overlapping override row (e.g.
both a global and a tenant-specific row for the same metric) will silently fan out
`benchmark_view` via the `LEFT JOIN`. Fine with the current seed data; revisit if per-
tenant/mode SLA overrides are added later.

**`signal`** (built, verified) — `signal_id`, `tenant_id`, `date`, `persona` (always
`'manager'` for now, see C3 scope note), `entity_type` (always `'vendor'` for now — MVP is
vendor-centric, matching decision 7's escalation target), `entity_id` (=`vendor_id`),
`metric_name`, `observed_value`, `reference_type` (`sla`/`baseline`/`peer`),
`reference_value`, `deviation_magnitude`, `materiality_score`, `severity_score`,
`persistence_days`, `final_score`, `safety_flag` (bypasses both the confidence gate and
the top-8 cap), `data_quality_confidence`, `status` (`new`/`reviewed`/`dismissed`),
`created_at`. **Not dropped on ingest re-run** (unlike everything else) — `CREATE TABLE
IF NOT EXISTS` in `schema.sql`, since C1 (ingest) and C3 (scan) are decoupled lifecycles;
scan's own idempotency is a `DELETE FROM signal WHERE date=?` before each reinsert.

**`draft`** (review queue, built) — draft_id, tenant_id, signal_id (fk to `signal`,
`RESTRICT` on delete — deliberate, see C4 notes), recipient_vendor_id, subject (generated
in Java, not asked of the LLM — see C4), body, evidence_ref (`signal:<id>`), status
(draft/approved/rejected — no "sent" state, sending is out of scope), created_at,
approved_by, approved_at. `CREATE TABLE IF NOT EXISTS`, not dropped on ingest re-run
(same decoupled-lifecycle treatment as `signal`).

**`leadership_brief`** (built) — brief_id, tenant_id, week_ending, narrative, evidence_ref
(`tenant:<id>:week:<date>`), created_at. `UNIQUE (tenant_id, week_ending)` — regenerating
overwrites via `ON CONFLICT DO UPDATE` (safe, no human-approval workflow attached to this
one, unlike `draft`). `CREATE TABLE IF NOT EXISTS`, not dropped on ingest re-run.

Long-term this belongs in `docs/data-model.md` — keep it here for now.

---

## Guardrails for future sessions

- **SQL-computes-numbers rule:** no figure in any output unless it came from a query
  result. If you're about to have the LLM compute or estimate a number, stop — write the
  query instead.
- **Reference-point rule:** no metric reaches a UI screen without a reference point
  attached (prior period / baseline / SLA / peer). Enforced by construction if all display
  data flows through the benchmark view.
- **Draft-and-approve rule:** nothing with an external effect (email, message) leaves the
  system without a human clicking approve. No "send" button exists that skips review.
- **LLM call boundary:** only C4's three prompts and C6's two chat call sites call a
  model. If you find yourself adding a call from C1/C2/C3/C5, or a sixth call site
  anywhere, stop and reconsider — that's scope creep against decision 11/11b.
- **Schema-currency check rule:** `IngestRunner.alreadyPopulated()` checks for the
  existence of whatever table `schema.sql` most recently added (currently `draft`) as a
  proxy for "the schema is fully up to date." This has already needed bumping twice
  (Step 3 added `signal`, Step 4 added `draft`) — every time a new table is added to
  `schema.sql`, update this check to reference it, or a warm restart will silently skip
  applying the new DDL and whatever depends on it will crash instead.
- **LLM-output verification rule:** "the call didn't throw and returned a non-empty
  string" is not sufficient proof an LLM call succeeded — confirmed `sarvam-105b` can
  return non-empty garbage (literal `<tool_call>` XML tags) that passes every emptiness
  check. Before trusting new LLM-generated content in this codebase, read the actual
  rendered text (in a browser or via a direct DB/API check), not just its length.
- **Chat SQL-safety rule (decision 11b):** chat-generated SQL must pass the statement-type
  guard (single `SELECT`/`WITH` only, no write keywords, no multiple statements) *and*
  execute inside a Postgres read-only transaction with a short `statement_timeout`. The
  DB-level read-only transaction is the real backstop — treat the regex guard as a
  fast-fail convenience, not the actual security boundary. Never let chat query staging
  tables or `draft`; never let it skip the tenant filter.
- **Definition of done for a step:** a named verification the human performs (see build
  order below), not "it compiles" or "the endpoint returns 200." E.g. "open the morning
  brief screen and see at least one signal with a non-null reference point."
- **Join-key normalisation rule:** every join on `trip_id` (and `stwid`) must go through
  the same strip-commas-and-cast step, applied per source file (formats differ — see
  Table Shapes). Do this once in the staging→fact transform, not ad hoc per query.
- **`"NA"`-is-null rule:** `trip_nodal="NA"` (and any other literal `"NA"`/`""` string
  found during Step 0 spot-checks) must be converted to a real SQL null during ingest — a
  raw CSV `COPY` will not do this for you.

---

## Build order (wall-clock budgets, 6h total)

| Step | Time | Work | Verification |
|---|---|---|---|
| 0 | ✅ done 2026-09-05 | Dataset recon complete — dictionary spot-checked against real rows in all five files (see Table & Signal Shapes and Metric Definitions above). Found and corrected one wrong assumption (mode taxonomy) and one dictionary understatement (`severity="False"` is ~29% of alerts, not a rare stray). Only remaining config decisions (escort-required proxy, CO2/SLA config values) are stretch-goal/cosmetic — do not block Step 1 on them. | Confirmed: file grains, join keys and their per-file formats, `business_unit`→tenant, mode taxonomy (`trip_nodal` not `product_type`), which DQ quirks are common vs negligible |
| 1 | ✅ done 2026-09-05 | Skeleton + ingest + `trip_fact`. Added `spring-boot-starter-jdbc` + `commons-csv` to pom. `IngestService` loads the 4 non-emp_data CSVs into staging, then one SQL transform (`sql/build_trip_fact.sql`) builds `trip_fact`. Runs on startup (skipped if already populated) and via `POST /api/ingest` (always forces a full drop/reload). Found and fixed a real bug along the way: `trip_id` is not globally unique (collides across tenants/months) — `trip_fact`'s key is `(tenant_id, trip_id)`, every staging join matches both columns. | Verified: `POST /api/ingest` run twice produces identical `tripFactRows=615546` both times; mode/tenant counts sum exactly to that total; `with_billing=614800` matches `615546 − (no_billing flags)` exactly |
| 2 | ✅ done 2026-09-05 | Aggregate + benchmark views. Added `sla_target` table + seed (6 metrics) to `schema.sql`; new `sql/create_views.sql` builds `daily_aggregate` (wide, 20 cols) → `daily_metric` (long, 11 metrics, unpivoted) → `benchmark_view` (one generic self-join for prior/baseline/sla/peer). `IngestService.ingestAll()` runs it right after `trip_fact`. | Verified: queried `benchmark_view` for a real grain/date — all 11 metrics present, `prior_value`/`baseline_avg`/`z_score_vs_baseline` populated, `sla_target_value`/`delta_vs_sla` populated for the 6 metrics with a target and null for the other 5 (correct, not a bug), `peer_median` populated. Found and accepted one real limitation: ~25% of grain-groups have a single-vendor peer group (noted for Step 3, not fixed — checked, not caused by the shift-level grain, roughly the same either way) |
| 3 | ✅ done 2026-09-05 | Scan + triage → ranked signals. New `scan` package: `ScanService` (scoring/dedup/ranking), `ScanRunner` (`@Order(2)`, startup scan for `MAX(trip_date)`), `ScanController` (`POST /api/scan?date=`). `sql/scan_candidates.sql` for candidate detection. Found and fixed a real perf bug (206s → ~3s, see C3 notes — `daily_aggregate` had to become a materialized view), a real calibration bug (bad `escort_presence_rate` SLA target flooding signals with noise), and a real bootstrapping gap (`IngestRunner`'s skip-check didn't account for schema currency, crashing `ScanRunner` on the first restart after adding the `signal` table). | Verified: startup scan for 2026-07-31 produced 11 signals (3 safety + 8 ranked) — in the 3–15 target range; re-running `POST /api/scan` for the same date is idempotent (delete-then-reinsert); signal set is genuinely diverse across metrics after the SLA fix, not dominated by one homogeneous breach |
| 4 | ✅ done 2026-09-05 | The three C4 LLM prompts behind one adapter, against Sarvam AI (`sarvam-105b`). New `narrative` package: `EvidenceService`, `LlmAdapter`, `NarrativeService`, `NarrativeRunner` (`@Order(3)`, resilient to LLM failures), `NarrativeController` (`POST /api/narrate?date=&force=`). Schema additions: `signal.narrative`/`office`/`mode`/`shift_type` columns, `draft` table, `leadership_brief` table. Found and fixed 6 real issues along the way — see C4 notes (reasoning-model timeouts, structured-output incompatibility, ambiguous persistence phrasing causing probabilistic empty responses, the ScanRunner/FK interaction, the recurring schema-currency check). | Verified end-to-end with real generated output: 11/11 root-cause notes on real signals (grounded in SQL numbers, e.g. correctly says "the data does not explain the high sev1_rate" rather than guessing), 3/3 leadership briefs (checked one in full — correctly cites exact costs/trip counts/vendor names/deviations), 5/5 escalation drafts eventually non-empty (checked one in full — grounded, correct trend citation, no fabricated numbers). App survives a missing/invalid API key without crashing (confirmed twice, including a real 401 from a placeholder key) |
| 4b | 3:45–4:20 (added 2026-09-05; **this addition makes 6h a stretch — see cut list, chat is first to go**) | C6 chat agent: NL→SQL call, read-only-transaction + statement-timeout + regex guard before execution, SQL-result→NL call, table allowlist | Ask it a real question ("which vendor had the most delays in June") and get back a correct answer plus the SQL and result rows it used; try to make it write (e.g. ask it to "delete the alerts") and confirm the DB-level read-only transaction rejects it, not just the prompt |
| 5 | ✅ done 2026-09-05 (chat deferred) | Frontend, four of five screens (chat deferred, consistent with the cut list — Step 4b/C6 not built). New `api` package (5 controllers/services) + 4 static HTML pages in `src/main/resources/static/`, Tailwind via CDN, vanilla JS, no build step. Found and fixed 6 real bugs via actual browser testing (claude-in-chrome) — see C5 notes: a date-serialization timezone bug, a frontend NaN-formatting bug, empty-narrative persistence gaps in 2 of 3 C4 write paths (not just drafts), a `BigDecimal` scientific-notation leak into prompts, missing per-item exception handling that let one transient network error abort an entire narrate batch, and `sarvam-105b` emitting literal `<tool_call>` XML tags (non-empty, so it passed the emptiness check — only caught by reading real rendered text). | Verified end-to-end in a real browser, not just curl: Morning Brief loads with real narrated signals and correct badges; Evidence drill-down shows correct 7-day trend with today's row highlighted and a correct attribution table; Leadership Brief renders stat tiles + forwardable prose citing exact numbers, print button present; Action Queue lists real drafts, Approve/Reject verified interactively (draft disappeared from Pending, appeared under Approved). Final state: 11/11 signals, 5/5 drafts, 3/3 briefs all non-empty with zero garbage-pattern matches across the whole dataset |
| 6 | 5:20–5:40 | Data-quality surfacing + polish | A DQ-flagged row visibly shows its flag somewhere in the UI, not silently dropped |
| 7 | 5:40–6:05 | Code freeze; rehearse demo 3x | Demo runs start-to-finish 3 times without a manual DB fix mid-run |

---

## Cut list (drop from the top when behind)

1. **Chat / C6 (added 2026-09-05) — cut first, before anything else on this list.** It's additive by design (decision 1); dropping it costs nothing mandatory. If Step 4b is running long or Step 2/3 overran into its slot, skip it entirely rather than shipping a half-guarded NL-to-SQL feature — an unguarded chat that can be tricked into a write or a runaway query is worse than no chat.
2. Action-queue UI (keep drafts in a table/log if needed, cut the review screen)
3. Charts → plain tables
4. PDF export → browser print only
5. Peer-median benchmark (keep prior-period + SLA, drop peer comparison)
6. Tenant enforcement (already just a column — nothing to cut here, it was never built)
7. DQ suppression logic (keep DQ *display*, drop the suppression/confidence gating)

**— mandatory-requirement line: cutting below this breaks eval requirements —**

8. Drafted escalation (this is the "acts" proof — cut last, only if truly out of time)

**Never cut:** benchmarked signals, morning brief, leadership narrative — these three
together prove sense→reason→act plus contextualisation, which is the mandatory bar.

---

## Session protocol

1. Read this file in full before touching code.
2. Plan for two minutes: which files, which responsibilities, nothing more. Get human
   approval before writing code.
3. Implement the step.
4. Run the named verification for that step out loud — don't mark a step done on "it
   compiles."
5. Commit with the step name (e.g. `git commit -m "Step 2: aggregate + benchmark views"`).
6. `/clear` before starting the next step.
7. Never leave two things broken at once. If a step isn't working after 15 minutes,
   revert and simplify rather than debugging deeper into it.

---

## Demo script (6 minutes, in order)

1. **0:00–0:30** — Open directly on the populated morning brief (simulated "now" =
   pre-chosen demo date). Never open on a blank dashboard or someone typing a question —
   the system has already acted.
2. **0:30–1:30** — Click a signal → drill-down evidence: trend vs baseline, attribution
   breakdown, underlying trips. Say explicitly: "this is SQL, zero inference cost."
3. **1:30–2:30** — Show a second, differently-typed signal (e.g. safety/escort, not just
   OTA) to prove breadth.
4. **2:30–3:30** — Action review queue: a drafted vendor escalation, evidence attached,
   human clicks Approve. Say explicitly: "never auto-sent."
5. **3:30–5:00** — Switch to transport & facilities head: weekly leadership brief +
   vendor scorecard, print-to-PDF.
6. **5:00–5:45** — Close on the deployability story: tenant column, LLM-call-site cost
   surface, where a scheduler/model-tiering would slot in for production.
7. **5:45–6:00 (optional, only if C6 shipped)** — One live chat question as a bonus beat,
   e.g. "how does vendor X compare to peers this month" — show the answer, its SQL, and
   its result rows. Skip this beat entirely rather than stretch for it; the 6-minute script
   above is complete and mandatory-requirement-covering without it.

---

## Out of scope

Production auth/security, real vendor integrations, a full historical data pipeline, a
scheduler/cron, live system access, tenant enforcement (column only), any LLM call
outside the three C4 prompts and the two C6 chat call sites. Chat is scoped to
read-only NL-to-SQL over an explicit table allowlist (decision 11b/C6) — it is not a
general-purpose assistant, doesn't hold conversation history across turns (each question
is independent — no multi-turn memory in v1), and doesn't write anything.

---

## Open questions & assumptions

**Resolved by the dataset (`src/main/resources/data/`, added after initial planning):**
- Dataset location, file layout, and grain — see Table & Signal Shapes above. Five CSVs,
  three months (May–July 2026), five shared `business_unit` values.
- Trip grain — `trip_fact` is one row per `trip_id`, matching the `ride_data_trip` hub.
  `emp_data` (per-employee-leg, 1.6M rows) is a stretch-goal enrichment, not required for
  MVP fill-rate/no-show metrics, which already exist at trip grain.
- Escort compliance and roster-match — **neither exists as the problem statement's
  domain language implied.** No `escort_required` field (only `actual_escort` presence) and
  no roster file. Metric definitions above adjust accordingly: escort *presence* rate
  instead of compliance; roster-match dropped, planned-vs-actual headcount used instead.
- GPS trace format — **there is no raw GPS trace file.** GPS-derived signal comes
  pre-distilled as `alerts_data` events (`OVER_SPEEDING`, `VEHICLE_STOPPAGE`,
  `DEVICE_NOT_REACHABLE`) plus trip-level `planned_km`/`traveled_km`. GPS coverage % is
  dropped as a metric — there's no ping-level data to compute it from.
- Cost data granularity — `bill_data` is line-item grain, `SUM`/`GROUP BY trip_id` before
  joining to `trip_fact` (row count exceeds trip count, so it is not a 1:1 join).

**Confirmed by Step 0 spot-checks (2026-09-05), no longer open:**
- `business_unit` as `tenant_id` — confirmed identical set of 5 values across all five
  files, no strays. Safe to treat as tenant with no further check.
- Mode taxonomy — **the original working assumption was wrong and has been corrected**:
  `product_type` and `trip_nodal` are independent; `trip_nodal` alone drives home/nodal/
  shuttle; `SPOT_2.0` is its own bucket (see glossary and Table Shapes for the confirmed
  crosstab).
- `trip_feedback` rating of `0` — resolved per-column, not uniformly: route/driver/cab/
  safety are clean (2 zero-rows out of 512,873, negligible); `marshal_rating=0` is 92.4%
  of rows and means "no marshal," excluded from that one average (see Metric Definitions).
- `alerts_data.severity="False"` — confirmed systematic (~29% of rows, not a rare stray),
  root-caused to real rows (not a CSV-parsing artifact on our side), always paired with
  `state_text=CLOSED`/`source=MOBILE`. Treat as null severity, keep the alert.
- Cost-per-km reliability — confirmed unreliable as a headline metric (~40% of `bill_data`
  rows have `total_trip_km=0`); cost-per-trip is the primary cost metric instead.

**Still open — confirm at kickoff, not mid-build:**
1. Escort-*required* proxy (stretch goal only) — if time permits a "compliance" framing
   rather than a bare presence rate, the candidate rule is shift-time-based (e.g. shifts
   starting before 06:00 or after 21:00 via `shift_type`). This is our config rule, not a
   dataset fact — must be labeled as such anywhere it's shown.
2. Emission factors for CO2 (by `actual_cab_fuel_type`) and the OTA SLA threshold (minutes
   late before a trip is "not on time") are our own config values, not in the dataset —
   pick reasonable defaults in Step 0/1 and label them illustrative in the UI.

**LLM/infra — resolved 2026-09-05:**
6. ~~Confirm an OpenAI API key...~~ **Using Sarvam AI (`sarvam-105b`) via Spring AI's
   OpenAI-compatible client**, no `pom.xml` change needed (same `spring-ai-starter-model-
   openai` dependency, just pointed at a different `base-url`). Key lives in the
   `SARVAM_API_KEY` env var, never in a tracked file. See C4's build-order notes for the
   real reliability characteristics of this specific model before assuming it's a drop-in
   swap for anything else.
7. Confirm Angular fluency across the team now, not on the day — decision 12 depends on it.

**Non-blocking:**
8. Demo date: pick the "simulated now" date in advance from the dataset, ideally one with
   a real, visible incident (a vendor/office with a delay or Sev-1 alert spike) so the demo
   has a genuine story rather than a flat baseline. May–July 2026 is the full range —
   scan for a standout day/vendor during Step 0.

**Chat / C6 (added 2026-09-05):**
9. Final table allowlist for chat SQL — proposed: `trip_fact`, the Step 2 daily-aggregate
   and benchmark views, `signal`. Confirm once Step 2's views exist and are named.
10. Tenant scoping in chat is prompt-level convention (tell the model the asker's
    `tenant_id`, instruct it to filter by it), consistent with decision 13's existing
    stance (column exists, no hard enforcement layer) — not a new gap, just the same
    already-accepted tradeoff applied to a new surface. Worth a one-line callout in the
    deck alongside the existing tenant-enforcement story.
11. Reference-point rule (decision 5) is relaxed for chat by necessity — an ad hoc
    question like "how many trips did vendor X run in June" has no natural baseline to
    attach. This is an intentional, scoped exception; don't let it creep back into C3/C4's
    signal-driven output, which must still always carry a reference point.
