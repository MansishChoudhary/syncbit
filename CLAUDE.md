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
| Home pickup | Cab picks up employee directly from residence — `product_type=CAB`, `trip_nodal` = `HOME`/`NA` |
| Nodal pickup | Employees converge on a fixed node; one cab serves multiple from it — `trip_nodal=NODAL` |
| Shuttle | Fixed-route, fixed-schedule, multi-stop vehicle — `product_type=BUS` and/or `trip_nodal=SHUTTLE` |
| `SPOT_2.0` | A fourth mode value present in the real data, not in the classic home/nodal/shuttle taxonomy — on-demand/micro-transit, best guess. Keep as its own bucket rather than forcing it into one of the three; don't guess harder than the data supports |
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

## The five components

**C1 — Ingest & fact build**
- In: the five raw CSVs (`ride_data_trip` ×3 monthly files, `emp_data`, `bill_data`, `alerts_data`, `trip_feedback`) → staging tables, one per file.
- Does: normalises join keys (strip commas from `trip_id`/`stwid`, cast to one type per side — `bill_data`'s `trip_id` has no commas, `emp_data`'s is already `int64`, the rest are comma-formatted strings), normalises the four different date/epoch formats, treats the literal string `"NA"` in `trip_nodal` as null explicitly (CSV/COPY import will not do this for you), then rolls `bill_data`/`alerts_data`/`trip_feedback` up to trip grain and joins onto the `ride_data_trip` hub. Computes per-trip derived fields in one pass — delay vs scheduled (already provided as `delay_minutes`), detour ratio (`traveled_km`/`planned_km`), fill rate (`actualemployee_cnt`/`actual_cab_capacity`), escort presence rate, alert counts by severity, avg feedback ratings, billed cost/km, CO2 (config emission factor × `traveled_km` by `actual_cab_fuel_type`).
- Out: `trip_fact`, one row per `trip_id`. Idempotent re-runs (re-running for a date replaces that date's rows, doesn't duplicate).
- Must NOT: call an LLM, apply business judgement, aggregate across trips. Must NOT pull in `emp_data` (1.6M employee-leg rows) unless a specific screen needs employee-level breakdown (gender/role/no-show-reason) — `ride_data_trip` already carries trip-level `plannedemployee_cnt`/`actualemployee_cnt`/`noshow_cnt`, which covers fill rate and no-show rate without the extra join. Treat `emp_data` as a stretch-goal enrichment, not an MVP dependency.

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
| Safety/alert rate | Alert count ÷ trip count, by `event_type` and `severity`; Sev-1 volume spikes are the clearest proactive-alert trigger in this dataset | `alerts_data`, rolled up to trip/day/vendor grain (drop the stray `"False"` in `severity`, filter `stwid="0"` for per-rider views) |
| Cost per trip / per km | `trip_cost` (strip commas) ÷ trip count or ÷ `total_trip_km` | `bill_data`, aggregated by `trip_id` — **row count (620,942) exceeds trip count (615,549), so this is a `SUM`/`GROUP BY trip_id`, not a 1:1 join**; also decide how to treat `total_trip_km=0` rows (excluded from denominator, not silently kept) |
| CO2 (sustainability) | `traveled_km` × emission factor by `actual_cab_fuel_type` | `ride_data_trip`; emission factors are our own config, not dataset-derived — label as illustrative in UI |
| CSAT / feedback | Avg of `route_rating`/`driver_rating`/`cab_rating`/`safety_rating`/`marshal_rating` (0–5) | `trip_feedback`, aggregated by `trip_id`; confirm in Step 1 whether `0` means "genuinely rated zero" or "unrated" before averaging — it changes the mean materially |

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
**Known quirks to design around, not discover mid-build:** `trip_id` is comma-formatted in
every file except `bill_data` (plain numeric string) and `emp_data` (clean `int64`) —
normalise before any join; `trip_nodal` uses the literal string `"NA"` for non-nodal
trips, which a raw CSV import will not treat as null; date/epoch formats differ per file
(see dictionary point 3–4); `is_driver_nc`/`is_cab_nc`/`planned_km` dtypes drift between
the three monthly `ride_data_trip` files; `alerts_data.severity` has a stray literal
`"False"`; `emp_data.planned_km`/`traveled_km` go negative (physically invalid).

**Staging tables** — one per source file, columns as-is (all text/varchar is fine at this
stage), plus a `source_file`/`load_batch` column for traceability. Load raw, clean in the
transform step, not during load.

**`trip_fact`** — one row per `trip_id` (grain matches the `ride_data_trip` hub — see C1).
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
| 0 | 0:00–0:20 | Dataset recon is mostly done (see Table & Signal Shapes and Metric Definitions above, sourced from `data/Dictionary/`). Spend this slot spot-checking the dictionary against a few real rows per file (already done once — repeat if the file changes), confirming the `business_unit`→tenant and mode-taxonomy assumptions below, and deciding the escort/CO2 config values. No app code. | You can state, in one sentence per file, what it contains and how it joins — and confirm the sample rows you looked at match the dictionary's claims |
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

**Still open — confirm at kickoff, not mid-build:**
1. `business_unit` as `tenant_id` — the only field that plausibly plays that role. Near-
   certainly right; confirm in the first few minutes rather than assuming silently.
2. Mode taxonomy mapping — working assumption: `product_type=BUS` → shuttle,
   `trip_nodal=NODAL` → nodal, `trip_nodal=HOME`/`"NA"` → home. `product_type=SPOT_2.0`
   doesn't fit the three-way taxonomy cleanly; keep it as its own mode bucket rather than
   forcing a guess (see glossary).
3. Escort-*required* proxy (stretch goal only) — if time permits a "compliance" framing
   rather than a bare presence rate, the candidate rule is shift-time-based (e.g. shifts
   starting before 06:00 or after 21:00 via `shift_type`). This is our config rule, not a
   dataset fact — must be labeled as such anywhere it's shown.
4. `trip_feedback` rating of `0` — genuine zero score or "unrated"? Confirm before
   averaging; if ambiguous, exclude zeros from the CSAT denominator and say so in the DQ
   notes rather than silently including them.
5. Emission factors for CO2 (by `actual_cab_fuel_type`) and the OTA SLA threshold (minutes
   late before a trip is "not on time") are our own config values, not in the dataset —
   pick reasonable defaults in Step 0/1 and label them illustrative in the UI.

**LLM/infra:**
6. `pom.xml` already wires Spring AI's OpenAI model starter. Confirm an OpenAI API key
   will be available on hackathon day; if the team intends to use a different provider
   (e.g. Anthropic), that's a `pom.xml` change to make in Step 1, not a later scramble.
7. Confirm Angular fluency across the team now, not on the day — decision 12 depends on it.

**Non-blocking:**
8. Demo date: pick the "simulated now" date in advance from the dataset, ideally one with
   a real, visible incident (a vendor/office with a delay or Sev-1 alert spike) so the demo
   has a genuine story rather than a flat baseline. May–July 2026 is the full range —
   scan for a standout day/vendor during Step 0.
