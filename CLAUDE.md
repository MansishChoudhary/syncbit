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

1. Proactive-first, no free-text chatbot — mandatory requirements exclude query-only tools; good-to-have list prefers proactive triggers.
2. Thin non-LLM drill-down replaces chat — click any signal to its evidence (trend, attribution, underlying trips, DQ). Same SQL the scan used. Zero inference cost.
3. Two personas: transport manager (daily brief, alerts, drafted escalations) and transport & facilities head (weekly narrative, vendor scorecard). One data spine, differ only in aggregation window and framing.
4. Four Section-7 forms covered: anomaly detection, proactive alerting, automated reporting/narratives, automated communications. UI renders agent output — not claimed as an independent "dashboard" form.
5. Every metric shown to a human carries a reference point (prior period, trailing baseline, SLA, peer median) — mandatory per problem statement; bare numbers are a bug.
6. Numbers computed in SQL; LLM only judges and writes — no raw trip rows in any prompt, attribution is a query not a model task.
7. Draft-and-approve for anything with external effect — escalation emails generated with evidence, queued, never auto-sent.

**Engineering**

8. One denormalized `trip_fact` table — staging tables for raw CSV load, then a wide fact table with joins resolved and derived fields precomputed. No JPA/entity graph/repository abstraction — JdbcTemplate and SQL.
9. SQL views, not a metric-layer abstraction — daily aggregate view (date × site × vendor × mode × shift) + benchmark view (joins to prior period, trailing 4-wk same-weekday baseline, SLA target config table, peer median). This view alone satisfies contextualisation, system-wide.
10. No scheduler — scan runs once on startup plus manual `POST /api/scan?date=...`. "Now" is a date parameter. Cron/queues/event buses out of scope.
11. Exactly three LLM prompts behind one adapter class: root-cause phrasing, leadership brief, vendor escalation draft. Nothing else calls a model.
12. Frontend: four screens, priority order — morning brief → evidence drill-down → leadership brief (print-to-PDF) → action review queue. Angular if team is fluent, else static HTML + Tailwind from Spring `/static` calling REST. Pick speed.
13. `tenant_id` column on every table from the start, no enforcement layer — multi-tenancy is a deck story, not code.
14. Stack: Java/Spring Boot, JdbcTemplate, PostgreSQL. **Already decided by existing scaffold** — `compose.yaml` runs `pgvector/pgvector:pg16` via Spring Boot Docker Compose support, `pom.xml` already has `spring-boot-starter-webmvc`, Postgres driver, Spring AI (OpenAI model starter + pgvector store), Java 25, Spring Boot 4.1.1. **Gap to close in Step 1:** `spring-boot-starter-jdbc` is not yet in `pom.xml` — add it. The pgvector vector-store dependency is vestigial (no RAG/chatbot per decision 1) — harmless, leave it, don't spend time removing it.

---

## Domain glossary

| Term | Meaning |
|---|---|
| OTA | On-Time Arrival % — trips/employees arriving within SLA window of scheduled time |
| Home pickup | Cab picks up employee directly from residence |
| Nodal pickup | Employees converge on a fixed node; one cab serves multiple employees from that node |
| Shuttle | Fixed-route, fixed-schedule, multi-stop vehicle, higher occupancy, lower personalization |
| No-show | Employee booked but did not board |
| Dead mileage | Distance driven without a revenue passenger (repositioning, empty legs) |
| Escort compliance | For safety-mandated (e.g. night-shift) trips, presence of required security escort vs required |
| Fill rate / occupancy | Actual riders ÷ capacity (or ÷ planned riders) |
| SLA | Contractual/target service level, e.g. OTA ≥ 90% |
| Materiality | How much a deviation matters in absolute terms (employees affected, ₹ cost) — filters noise so small blips don't become signals |
| Signal | One scored, triaged anomaly/event row emitted by C3 — one thing worth telling a human |
| Brief | Persona-facing narrative bundling signals + narrative (morning brief / leadership brief) |
| Scorecard | Vendor-level rollup vs SLA/peers, feeds leadership brief and escalations |

---

## The five components

**C1 — Ingest & fact build**
- In: raw CSVs → staging tables.
- Does: resolves joins (trips/vendors/drivers/routes/employees/shifts), computes per-trip derived fields in one pass — arrival delta vs scheduled, detour ratio, occupancy, dead km, escort compliance, speeding events, CO2, GPS coverage %, roster-matched flag, feedback attach.
- Out: `trip_fact`. Idempotent re-runs (re-running for a date replaces that date's rows, doesn't duplicate).
- Must NOT: call an LLM, apply business judgement, aggregate across trips.

**C2 — Aggregate & benchmark views**
- In: `trip_fact`.
- Does: daily aggregate view over date × site × vendor × mode × shift; benchmark view adds prior period, trailing 4-wk same-weekday baseline, SLA target, peer median, deviation figures (z-score, delta vs SLA, % change).
- Out: two SQL views (or materialized views if perf demands it — unlikely at sample-dataset scale).
- Must NOT: contain Java business logic — pure SQL. No LLM.

**C3 — Scan & triage**
- In: benchmark view for a given date.
- Does: emits candidate signals on threshold/z-score breach, scores materiality × severity × persistence, dedups, gates on data-quality confidence, writes top N per persona to `signal` table. Safety/compliance signals bypass ranking (always surface). Aggressive suppression is a designed feature, not a shortfall — a system emitting 40 signals/day has failed.
- Out: rows in `signal` table.
- Must NOT: call an LLM. Zero inference cost by design.

**C4 — Narrative & actions**
- In: `signal` rows + their SQL evidence (attribution breakdown as query results, not raw rows).
- Does: the only component calling a model. Exactly three prompts (root-cause phrasing, leadership brief, vendor escalation draft), one adapter class.
- Out: alert copy, leadership brief text, drafted escalation emails into a review queue (draft status, never sent).
- Must NOT: compute any number itself. Must NOT auto-send anything external.

**C5 — API & UI**
- In: signals, evidence queries, briefs, drafts via REST.
- Does: four screens per decision 12. Print-to-PDF for leadership brief (browser print, not a PDF library).
- Out: rendered UI, approved/rejected draft state changes.
- Must NOT: contain business logic — thin rendering over C1–C4 outputs.

**Where the real architecture would differ** (for the deck, not the build): a scheduler
(cron/event-driven ingest) replacing startup+manual trigger; a metric-definition registry
replacing hardcoded views once metric count grows past a handful; enforced row-level
tenant isolation (RLS or a filtering interceptor) replacing the bare `tenant_id` column;
model tiering (cheap/small model for routine narratives, larger model reserved for
judgement-heavy escalation drafts) to control cost at scale; a real message queue for
draft approval/send instead of in-process state.

---

## Metric definitions

Dimensions for all: date, site, vendor, mode, shift, tenant. **⚠ = depends on a dataset
field not yet confirmed to exist — verify in Step 0, cut the metric if absent.**

| Metric | Definition | ⚠ |
|---|---|---|
| OTA % | Trips arriving within SLA window of scheduled time ÷ total trips | needs scheduled + actual arrival timestamps |
| Delay minutes | actual_arrival − scheduled_arrival, avg/median | same as above |
| No-show rate | No-show trips ÷ booked trips | needs booking vs boarded flag ⚠ |
| Dead mileage % | Non-revenue km ÷ total km | needs GPS trace + route km ⚠ |
| Fill rate / occupancy | Actual occupants ÷ capacity | needs vehicle capacity field ⚠ |
| Escort compliance % | Escort present ÷ escort required | needs escort_required + escort_present flags ⚠⚠ (may not exist in sample data — have a fallback story) |
| Speeding events | Count of GPS points/segments over speed threshold | needs GPS trace granularity ⚠ |
| Cost per trip / per km | Total cost ÷ count | needs cost data grain — per trip vs per vendor invoice ⚠ |
| CO2 (sustainability) | Distance × emission factor by vehicle type | emission factors are our own config, not dataset-derived — label as illustrative in UI |
| GPS coverage % | % of trip duration/distance with valid pings | needs GPS trace completeness ⚠ |
| Roster-match rate | % of trips matched to expected roster/booking | needs a roster file — may not exist ⚠⚠ |
| Feedback / CSAT | Avg rating or sentiment | needs feedback field type (numeric vs free text) ⚠ |

Long-term this table belongs in `docs/metrics.md` — keep it here for now so it loads with
everything else in one file.

---

## Table & signal shapes (prose — no DDL, decide exact types during Step 1)

**`trip_fact`** — one row per trip-leg. **Open grain question (resolve in Step 0):** if
nodal/shuttle trips carry multiple employees, decide whether the fact table is per-trip
(with an employee count) or per-employee-leg (with a trip_id grouping key). Per-employee-
leg is usually right when no-show/feedback/roster-match are employee-level facts.
Fields: trip_id, tenant_id, date, site_id, mode (cab/nodal/shuttle), vendor_id, driver_id,
employee_id, shift, scheduled_pickup_time, actual_pickup_time, scheduled_arrival_time,
actual_arrival_time, delay_minutes, planned_distance_km, actual_distance_km, dead_km,
capacity, occupancy, fill_rate, escort_required, escort_present, gps_coverage_pct,
speeding_event_count, cost, co2_kg, no_show_flag, roster_matched_flag, feedback_score,
data_quality_flag (missing_gps / unmatched_roster / etc.), source_row_ref (traceability
for drill-down back to staging).

**Daily aggregate view** — grain date × site × vendor × mode × shift. Aggregates:
trip_count, avg/median delay, OTA%, no_show_rate, avg_fill_rate, dead_km_total,
cost_total, cost_per_trip, escort_compliance_pct, speeding_event_rate, gps_coverage_pct,
feedback_avg.

**Benchmark view** — joins daily aggregate to: prior_period (same grain, previous
day/week), trailing_4wk_same_weekday_baseline (avg + stddev of same weekday, last 4
weeks), sla_target (from config table), peer_median (median across vendors/sites, same
date+mode+shift). Computes delta_vs_prior, z-score vs baseline, delta_vs_sla,
delta_vs_peer_pct.

**`sla_target` (config table)** — metric_name, mode (nullable = applies to all), target_value,
direction (higher_better/lower_better), tenant_id.

**`signal`** — signal_id, tenant_id, date, persona (manager/head), entity_type
(vendor/site/route/shift), entity_id, metric_name, observed_value, reference_type,
reference_value, deviation_magnitude, materiality_score, severity, persistence_days,
safety_flag (bypasses ranking), data_quality_confidence, status (new/reviewed/dismissed),
created_at.

**`draft`** (review queue) — draft_id, tenant_id, signal_id (fk), recipient_vendor_id,
subject, body, evidence_ref, status (draft/approved/rejected — no "sent" state, sending is
out of scope), created_at, approved_by, approved_at.

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
- **LLM call boundary:** only C4's three prompts call a model. If you find yourself adding
  a fourth prompt or calling a model from C1/C2/C3/C5, stop and reconsider — that's scope
  creep against decision 11.
- **Definition of done for a step:** a named verification the human performs (see build
  order below), not "it compiles" or "the endpoint returns 200." E.g. "open the morning
  brief screen and see at least one signal with a non-null reference point."

---

## Build order (wall-clock budgets, 6h total)

| Step | Time | Work | Verification |
|---|---|---|---|
| 0 | 0:00–0:30 | Dataset recon: schemas, row counts, null rates, join integrity, resolve `trip_fact` grain question. No app code. Update Open Questions below with real column names. | You can state, in one sentence per file, what each raw file contains and how it joins to the others |
| 1 | 0:30–1:30 | Skeleton + ingest + `trip_fact`. Add `spring-boot-starter-jdbc` to pom. | Re-running ingest for the same date produces the same row count in `trip_fact` (idempotency) |
| 2 | 1:30–2:15 | Aggregate + benchmark views | A query against the benchmark view for one date/vendor returns a non-null value for every reference-point column |
| 3 | 2:15–3:00 | Scan + triage → ranked signals; startup run + manual `POST /api/scan?date=` | Startup produces a bounded, non-zero, non-huge signal count (e.g. 3–15) for a known date |
| 4 | 3:00–3:45 | The three LLM prompts behind one adapter | Each of the three prompts produces output for one real signal, grounded only in SQL-supplied numbers |
| 5 | 3:45–5:00 | Frontend, four screens in priority order | Morning brief screen loads with real data before drill-down is attempted; each screen ships before the next is started |
| 6 | 5:00–5:30 | Data-quality surfacing + polish | A DQ-flagged row visibly shows its flag somewhere in the UI, not silently dropped |
| 7 | 5:30–6:00 | Code freeze; rehearse demo 3x | Demo runs start-to-finish 3 times without a manual DB fix mid-run |

---

## Cut list (drop from the top when behind)

1. Action-queue UI (keep drafts in a table/log if needed, cut the review screen)
2. Charts → plain tables
3. PDF export → browser print only
4. Peer-median benchmark (keep prior-period + SLA, drop peer comparison)
5. Tenant enforcement (already just a column — nothing to cut here, it was never built)
6. DQ suppression logic (keep DQ *display*, drop the suppression/confidence gating)

**— mandatory-requirement line: cutting below this breaks eval requirements —**

7. Drafted escalation (this is the "acts" proof — cut last, only if truly out of time)

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
6. **5:00–6:00** — Close on the deployability story: tenant column, three-prompt cost
   surface, where a scheduler/model-tiering would slot in for production.

---

## Out of scope

Production auth/security, real vendor integrations, a full historical data pipeline,
free-text chat/conversational Q&A, a scheduler/cron, live system access, tenant
enforcement (column only), any LLM call outside the three defined prompts.

---

## Open questions & assumptions

**Blocking — resolve before/at Step 0:**
1. **No dataset files exist in this repo as of writing.** `src/main/resources/docs/`
   contains only `problem_statement.pdf` and `prompt.md` — no CSVs. Get the dataset and
   its file layout before Step 0 starts, or Step 0's 30-minute budget is spent waiting,
   not analyzing.
2. Confirm trip grain: per-trip (with an employee/occupant count) or per-employee-leg?
   Determines `trip_fact` grain (see Table Shapes above).
3. Confirm whether escort compliance and roster-match fields exist in the sample dataset
   at all — both are named in the problem statement's domain but may not be present.
   Have a fallback (drop the metric, or synthesize a config-driven placeholder clearly
   labeled as such) ready either way.
4. Confirm cost data granularity (per trip? per vendor invoice/period, needing
   allocation?) — changes whether cost-per-trip is a direct column or a derived split.
5. Confirm GPS trace format/granularity (points per trip? sampling interval?) — determines
   whether dead-mileage, speeding events, and GPS coverage % are cheap SQL or need
   pre-aggregation in C1.

**LLM/infra:**
6. `pom.xml` already wires Spring AI's OpenAI model starter. Confirm an OpenAI API key
   will be available on hackathon day; if the team intends to use a different provider
   (e.g. Anthropic), that's a `pom.xml` change to make in Step 1, not a later scramble.
7. Confirm Angular fluency across the team now, not on the day — decision 12 depends on it.

**Non-blocking:**
8. Demo date: pick the "simulated now" date in advance from the dataset, ideally one with
   a real, visible incident (a vendor dip, a safety flag) so the demo has a genuine story
   rather than a flat baseline.
