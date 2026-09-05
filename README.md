# SyncBit

**Agentic intelligence & reporting layer for enterprise mobility** — built for the
MoveInSync Hackathon.

Large enterprises move hundreds to thousands of employees daily via home/nodal cabs and
shuttles. Transport managers spend their time assembling data, not acting on it. SyncBit
**senses** anomalies in trip data, **reasons** about what they mean against a real
reference point (prior period, baseline, SLA, or peer), and **acts** — surfacing a
proactive morning brief, writing a forwardable weekly leadership narrative, and drafting
vendor escalation emails for a human to approve. Nothing is auto-sent; nothing reaches a
screen without a reference point attached.

It runs end-to-end on a real anonymised trip-log dataset — 615,546 trips across cab,
nodal, and shuttle modes, three months, five client accounts — checked into this repo.

---

## What it covers

| Section 7 form | Where |
|---|---|
| Anomaly detection | Scan & triage scores every metric against its own baseline/SLA/peer and ranks the breaches |
| Proactive alerting | The morning brief is already populated before anyone opens it — nothing to query |
| Automated reporting & narratives | Root-cause notes on every signal, plus a weekly leadership brief |
| Automated communications | Vendor escalation emails, drafted with evidence, queued for one human click — never auto-sent |

Full architecture, the 14 locked engineering decisions, every real bug found while
building this, and the step-by-step build log live in **[`CLAUDE.md`](./CLAUDE.md)** —
read that for the "why," this file is the "how to run it."

---

## Architecture, in one paragraph

Five stages share one Postgres database. **Ingest** loads the raw CSVs into a single
denormalised `trip_fact` table. **Views** turn that into a benchmark view carrying prior
period, trailing baseline, SLA target, and peer median for every metric, in one generic
SQL join. **Scan & triage** reads that view for one date, scores candidates
(materiality × severity × persistence), and writes the top signals to a `signal` table —
zero model calls anywhere in these three stages. **Narrative & actions** is the *only*
place that calls a model (Sarvam AI, `sarvam-105b`) — it writes a root-cause note per
signal, a leadership brief per tenant per week, and a drafted escalation email, using only
numbers that SQL already computed. **API & UI** serves four static screens over REST:
morning brief, evidence drill-down, leadership brief (print-to-PDF), and an action review
queue.

```
CSV files → Ingest → trip_fact → Views → Scan & Triage → signal
                                              │
                                              ▼
                                   Narrative & Actions ⇄ Sarvam AI
                                              │
                                              ▼
                          signal.narrative · draft · leadership_brief
                                              │
                                              ▼
                                        API & UI → 4 screens
```

---

## Tech stack

- **Java 25**, **Spring Boot 4.1.1**, Maven (wrapper included — no local Maven needed)
- **PostgreSQL 16** (`postgres:15-alpine` image via `compose.yaml`), started automatically
  by Spring Boot's Docker Compose support
- **JdbcTemplate + hand-written SQL** — no JPA, no ORM, no repository abstraction
- **Spring AI** (`spring-ai-starter-model-openai`), pointed at **Sarvam AI**'s
  OpenAI-compatible endpoint (`sarvam-105b`)
- **Apache Commons CSV** for ingest
- **Static HTML + Tailwind (CDN) + vanilla JS** for the frontend — no build step, no npm

---

## Prerequisites

- **Java 25 JDK**
- **Docker Desktop**, running (Spring Boot starts/stops the Postgres container for you —
  you never run `docker compose` by hand)
- A **Sarvam AI API key** — narrative generation, leadership briefs, and escalation drafts
  need one. Without it, ingest/scan/the UI all still work fully; only the LLM-written text
  is skipped (the app degrades gracefully, it does not crash — see `CLAUDE.md`'s C4 notes)

No local PostgreSQL, no Node, no Maven install required.

---

## Setup

1. Clone the repo and open a terminal at the project root.
2. Set your Sarvam AI key as an environment variable — **never put it in
   `application.properties`**, that file is tracked by git:

   ```powershell
   # PowerShell
   $env:SARVAM_API_KEY = "sk_..."
   ```
   ```bash
   # bash / macOS / Linux
   export SARVAM_API_KEY="sk_..."
   ```

3. Make sure Docker Desktop is running.

---

## Run it

```powershell
# Windows
.\mvnw.cmd spring-boot:run
```
```bash
# macOS / Linux
./mvnw spring-boot:run
```

First run takes **~2 minutes**: Spring Boot pulls/starts the Postgres container, then the
app automatically loads all five CSVs (~1.8M rows), builds `trip_fact` and the benchmark
views, scans the most recent date in the dataset, and (if `SARVAM_API_KEY` is set)
generates narratives, drafts, and leadership briefs for it. Every subsequent restart skips
all of that and boots in a few seconds, since nothing needs to be redone.

Once it's up, open **http://localhost:8091/index.html** — that's the morning brief, and
it's already populated. Nothing to query, nothing to type.

| Screen | URL |
|---|---|
| Morning brief | `http://localhost:8091/index.html` |
| Evidence drill-down | `http://localhost:8091/evidence.html?id=<signalId>` (linked from the morning brief) |
| Leadership brief (print-to-PDF) | `http://localhost:8091/leadership.html` |
| Action review queue | `http://localhost:8091/drafts.html` |

---

## Manually re-running a stage

Nothing here uses a scheduler — "now" is a date parameter, and every stage also has a
manual REST trigger:

| Endpoint | What it does |
|---|---|
| `POST /api/ingest` | Force a full reload of all five CSVs → rebuilds `trip_fact` + views (~90s) |
| `POST /api/scan?date=YYYY-MM-DD` | Force a re-scan of one date → rebuilds `signal` rows for it (~2–5s) |
| `POST /api/narrate?date=YYYY-MM-DD&force=true` | Regenerate narratives + leadership briefs for a date (escalation drafts never regenerate once created — approvals must never be silently overwritten) |

Example, using `curl`:

```bash
curl -X POST "http://localhost:8091/api/scan?date=2026-07-31"
```

---

## Project structure

```
src/main/java/com/binarybrains/syncbit/
  ingest/      C1 — CSV → staging → trip_fact (IngestService, IngestRunner, IngestController)
  scan/        C3 — benchmark_view → scored signal rows (ScanService, ScanRunner, ScanController)
  narrative/   C4 — the only package that calls a model (LlmAdapter, NarrativeService, EvidenceService)
  api/         C5 — REST reads for the four screens (BriefController, EvidenceController,
               DraftController, MetaController)

src/main/resources/
  schema.sql           all table DDL, hand-written, no migrations tool
  sql/                 the SQL transforms (build_trip_fact.sql, create_views.sql, scan_candidates.sql)
  static/              the four frontend screens (plain HTML/CSS/JS, no build step)
  data/                the sample dataset (5 CSVs) + a Dictionary/ subfolder documenting every column
```

---

## The dataset

`src/main/resources/data/` — five CSVs (`ride_data_trip` ×3 monthly files, `bill_data`,
`alerts_data`, `trip_feedback`), covering May–July 2026 across five client accounts
(treated as tenants). `Dictionary/README.md` in that folder is the map; every column,
join key, and data-quality quirk (comma-formatted numbers, stray literal strings,
`trip_id` colliding across tenants, and more) is documented there and cross-referenced
in `CLAUDE.md`.

`emp_data.csv` (1.6M employee-leg rows) ships in the same folder but is intentionally not
loaded — `ride_data_trip`'s own trip-level headcounts already cover fill rate and
no-show rate without the extra join.

---

## Known limitations (by design, not oversight)

- **No production auth/security.** Not in scope for this build.
- **`tenant_id` is a column, not an enforcement layer.** Every table carries it; nothing
  yet stops a query from crossing tenants. A real deployment adds row-level security.
- **No scheduler.** Ingest/scan run on startup plus manual REST triggers — no cron, no
  event bus.
- **One model for every prompt.** At real volume, routine narratives would move to a
  cheaper model, reserving a larger one for judgement-heavy escalation drafts.
- **Chat (NL-to-SQL) is designed, not built.** Two independent safety gates were
  specified for it — see `CLAUDE.md` decision 11b — but it was cut first, on purpose, to
  protect the mandatory proactive core.

See `CLAUDE.md` for the full, dated list of every decision, every real bug found while
building this, and why each tradeoff was made.
