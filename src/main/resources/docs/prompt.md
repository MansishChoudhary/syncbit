# ROLE & MODE

You are helping me plan a hackathon project before any code is written.

**HARD CONSTRAINT — PLANNING ONLY.** In this session you must NOT:
- write or scaffold any source code, config, build files, SQL, or directory structure
- create, modify, or delete any file except `CLAUDE.md` at the repo root
- run build tools, package managers, or generators

Your only deliverable is a `CLAUDE.md` at the repo root. It becomes the persistent
context file for every future session on this repo. If you feel the urge to write code
to illustrate a point: don't. Describe shapes and responsibilities in prose.

---

# THE SINGLE MOST IMPORTANT CONSTRAINT

**This is a ONE-DAY hackathon. Implementation time is 5 to 6 hours, from scratch,
including the frontend.**

Every recommendation you make must be filtered through that. Where you would normally
suggest an interface, a strategy pattern, a service layer or a scheduler, ask whether it
survives a six-hour budget — and if it doesn't, say so and pick the blunt version. A
future session reading this file must come away biased toward the simplest thing that
demos, not toward good long-term architecture.

State this constraint prominently near the top of the file so it is impossible to miss.

---

# PROJECT CONTEXT

Submission for the MoveInSync hackathon. Full problem statement at
`docs/problem-statement.pdf` — read it in full before writing anything.

Build an **agentic intelligence and reporting layer for enterprise mobility**. Large
enterprises move hundreds to thousands of employees daily via home/nodal pickup cabs and
fixed-route shuttles. Transport managers spend their time assembling data rather than
acting on it. The data is rich; the insight is missing; the actions are manual.

We are given an anonymised sample dataset only — trip logs across cab, nodal and shuttle
modes, with vendor performance, GPS traces, delay records, cost data and employee
feedback. No live system access.

Evaluation weights, which should visibly shape every recommendation and serve as the
tiebreaker for any future decision:
- 35 — Business impact & experience (reduces manager effort; output lands; leadership-ready)
- 25 — Functionality (working, demo-able, end to end, on the provided dataset)
- 20 — Agentic design & cost at scale (inference cost per interaction, latency, efficiency)
- 20 — Architecture & code quality (deployable into an existing platform)

---

# LOCKED DECISIONS — record these as settled, one line of rationale each

Do not relitigate. If you believe one is genuinely wrong, put it in a "Concerns" section
at the end of your response (not in the file) — do not silently change it.

## Product

1. **Proactive-first, no free-text chatbot.** The system senses, reasons and acts on its
   own. No NL Q&A surface. The mandatory requirements exclude "query-only tools"; the
   good-to-have list explicitly prefers proactive triggers over on-demand responses.

2. **A thin non-LLM drill-down replaces chat.** Any signal can be clicked through to its
   evidence — trend vs baseline, attribution breakdown, underlying trips, data-quality
   summary. Same SQL the scan used. Zero inference cost.

3. **Two personas:** transport manager (daily brief, event alerts, drafted vendor
   escalations) and transport & facilities head (weekly leadership narrative, vendor
   scorecard). One data spine; they differ only in aggregation window and framing.

4. **Four Section-7 forms covered:** anomaly detection, proactive alerting, automated
   reporting & narratives, automated communications. The UI renders agent output; it is
   not claimed as an independent "dashboard" form.

5. **Every metric that reaches a human carries a reference point** — prior period,
   trailing same-weekday baseline, SLA target, or peer median. Bare numbers are a bug.
   Mandatory per the problem statement.

6. **Numbers are computed in SQL; the LLM only judges and writes.** No raw trip rows in
   any prompt. Attribution is a SQL query, not a model task. Rule to enforce: no figure
   appears in output unless it came from a query result.

7. **Draft-and-approve for anything with external effect.** Vendor escalation emails are
   generated with evidence attached and queued for human approval. Never auto-sent.

## Engineering, sized for six hours

8. **One denormalized `trip_fact` table.** Staging tables for raw CSV load, then a single
   wide fact table with every join resolved and every derived field precomputed. No JPA,
   no entity graph, no repository abstraction — JdbcTemplate and SQL.

9. **SQL views, not a metric-layer abstraction.** A daily aggregate view over
   date × site × vendor × mode × shift, and a benchmark view that joins each row to its
   prior period, trailing same-weekday baseline, SLA target (small config table) and peer
   median. That benchmark view alone satisfies the contextualisation requirement
   system-wide. No Java metric registry, no tool-call interfaces.

10. **No scheduler.** A scan runs once on application startup plus a manual
    `POST /api/scan?date=...` trigger. A simulated "now" is just a date parameter. Cron,
    job queues and event buses are out of scope for the timebox.

11. **Exactly three LLM prompts**, behind one small adapter class: root-cause phrasing,
    leadership brief, vendor escalation draft. Nothing else calls a model.

12. **Frontend is four screens, priority-ordered:** morning brief → evidence drill-down →
    leadership brief (print to PDF) → action review queue. Angular if the team is fluent;
    otherwise static HTML + Tailwind served from Spring's `/static`, calling REST
    endpoints. Both are acceptable — pick speed.

13. **Tenant column, not tenant enforcement.** `tenant_id` on every table from the start,
    but no enforcement layer. Multi-tenancy is a design story for the deck, not code.

14. **Stack:** Java / Spring Boot, JdbcTemplate, PostgreSQL (or H2 if it saves setup
    time — say which you'd pick and why). Deviate only with a stated reason.

---

# ARCHITECTURE TO DOCUMENT — five components, not ten

Document each with its responsibility, inputs, outputs, and what it must NOT do. Sharpen
and expand this; don't just restate it.

- **C1 Ingest & fact build.** Load raw CSVs to staging. Resolve joins across
  trips/vendors/drivers/routes/employees/shifts. Compute per-trip derived fields in one
  pass: arrival delta vs scheduled, detour ratio, occupancy, dead km, escort compliance,
  speeding events, CO2, GPS coverage %, roster-matched flag, feedback attach. Emits
  `trip_fact`. Idempotent re-runs.

- **C2 Aggregate & benchmark views.** Daily aggregate view over the dimension set; a
  benchmark view adding prior period, trailing 4-week same-weekday baseline, SLA target,
  peer median, and deviation figures (z-score, delta vs SLA, % change). Pure SQL.

- **C3 Scan & triage.** Reads the benchmark view for a date. Emits candidate signals on
  threshold or z-score breach. Scores on materiality × severity × persistence, dedups,
  gates on data-quality confidence, and writes the top N per persona to a `signal` table.
  Safety/compliance signals bypass ranking. Zero LLM cost. Aggressive suppression is a
  designed feature — a system that emits 40 signals a day has failed.

- **C4 Narrative & actions.** The only component that calls a model. Three prompts (see
  locked decision 11). Attribution breakdown is supplied to the prompt as a SQL result.
  Produces alert copy, the leadership brief, and drafted escalation emails into a review
  queue.

- **C5 API & UI.** REST endpoints over signals, evidence, brief and drafts. Four screens
  as above. Print-to-PDF for the leadership brief.

Include a short note on where the *real* architecture would differ (scheduler, metric
layer, tenant enforcement, model tiering) so the deck can tell a credible deployability
story without us having built it.

---

# WHAT `CLAUDE.md` MUST CONTAIN

Write for a future session with no memory of this conversation.

1. **The six-hour constraint**, stated first, with the "pick the blunt version" instruction.
2. **Project purpose & the four evaluation weights**, usable as a tiebreaker.
3. **Locked decisions** — the fourteen above, one line of rationale each.
4. **Domain glossary** — OTA, home vs nodal vs shuttle, no-show, dead mileage, escort
   compliance, fill rate, SLA, materiality, signal, brief, scorecard. Short and precise.
5. **The five components** — responsibility, inputs, outputs, and explicit non-goals.
6. **Metric definitions** — the KPI list with plain-English definitions and sliceable
   dimensions. Mark any depending on dataset fields we have not confirmed exist.
7. **Table & signal shapes** — field lists with types and meaning. Prose, no DDL.
8. **Guardrails for future sessions** — the SQL-computes-numbers rule, the
   reference-point rule, the draft-and-approve rule, where LLM calls are and are not
   allowed, and what "done" means for a step (a named verification, not "it compiles").
9. **The seven-step build order** with wall-clock budgets, each ending in a named
   verification the human performs. Base it on this, adjusting if you see a better order:
    - Step 0 (0:00–0:30) Dataset recon — schemas, row counts, null rates, join integrity.
      No app code. Update this file's assumptions section with the real column names.
    - Step 1 (0:30–1:30) Skeleton + ingest + `trip_fact`.
    - Step 2 (1:30–2:15) Aggregate + benchmark views.
    - Step 3 (2:15–3:00) Scan + triage producing ranked signals; startup run + manual trigger.
    - Step 4 (3:00–3:45) The three LLM prompts.
    - Step 5 (3:45–5:00) Frontend, four screens in priority order.
    - Step 6 (5:00–5:30) Data-quality surfacing and polish.
    - Step 7 (5:30–6:00) Code freeze; rehearse the demo three times.
10. **Cut list, ordered** — what to drop when behind, with a marked line showing where
    the mandatory requirements would start to break. Suggested order: action-queue UI →
    charts to tables → PDF export to browser print → peer-median benchmark → tenant
    enforcement → DQ suppression (keep display) → [line] → drafted escalation, which is
    the "acts" proof and should be last to go. Never cut: benchmarked signals, morning
    brief, leadership narrative.
11. **Session protocol** — how a future session should work: read this file, plan in two
    minutes (files and responsibilities only), get approval, implement, run the named
    verification, commit with the step name, `/clear` before the next step. Never leave
    two things broken at once; if a step isn't working in 15 minutes, revert and simplify.
12. **Demo script** — six minutes, in order, opening with the system having already acted
    unprompted. Never open with a dashboard or with someone typing a question.
13. **Out of scope** — production auth/security, real vendor integrations, historical
    pipeline, free-text chat, scheduler, live system access.
14. **Open questions & assumptions.**

---

# HOW TO HANDLE UNCERTAINTY

The dataset schema is not yet confirmed. Do NOT invent column names, file layouts or
field types and present them as fact. Mark every assumption inline as an assumption and
collect them all into section 14, so Step 0 becomes a checklist of things to confirm.

End your response (outside the file) with:
- questions you need answered before the hackathon starts
- any real concerns about the locked decisions
- the two or three steps in the build order most likely to overrun, and the pre-work I
  could do beforehand to de-risk them

---

# STYLE

Dense and factual. Short declarative sentences. Tables where they help. No filler, no
motivational framing. Assume a competent engineer who has never seen this project and has
six hours. Every section should be actionable without asking me anything already written
down.

Keep the file tight — it loads into context on every future session, so length is a tax.
If metric definitions or table shapes get long, note that they could move to
`docs/metrics.md` and `docs/data-model.md` later, but keep everything in one file for now.

Read `docs/problem-statement.pdf` first. Then write the file. Nothing else.