# CP9 — Incident drill and blameless postmortem

The week 11 drill ran on paper, before the service existed. This page keeps its scenario and lessons,
corrects what the code does differently, and says which action items were built.

## Scenario

- **Hop:** `/finish` → MongoDB transaction (`preconsultation_summaries` + `outbox_events`).
- **Failure:** agent-db answers in 3 s for 25 % of operations, for 40 minutes, from 08:00 (morning peak).
  The cause is a Liquibase index build on the rs0 primary.
- **Drill assumption:** a 2 s MongoDB timeout. Every slow operation became a 503.

## What happened in the drill

| Time | Event |
|---|---|
| 08:00 | The index build saturates disk I/O. 25 % of operations take 3 s, time out at 2 s and answer 503. |
| 08:35 | The 1 h error ratio crosses 14.4 %. |
| 08:40 | `AgentAvailabilityFastBurn` pages, just as the build finishes. |
| 08:50 | The recent-changes check links the errors to the migration. |
| 09:05 | Recovery verified; incident closed. |

- **Impact:** 178 requests in 40 minutes, of which 44 failed. That is 7.3 % of the monthly availability
  budget, or 32 budget minutes. No clinical data was lost, because the transaction aborted.
- **Root cause:** a heavy index build ran on the primary during peak hours.
- **Contributing factors:**
  - migrations run at deploy time;
  - only availability was alerted on;
  - there was no way to pause the pre-consultation.

## The same failure with the real controls

| | Drill (paper) | Now |
|---|---|---|
| agent-db timeout | 2 s, assumed | `MONGODB_READ_TIMEOUT` = 5 s. A 3 s answer is slow but succeeds |
| What patients see | 25 % of finishes fail | 25 % of finishes take over 3 s and complete |
| First page | `AgentAvailabilityFastBurn` at about minute 40 | `AgentFinishLatencyFastBurn` within about 4 minutes, when at least 5 finishes land in 10 minutes ([see the limit](alert-policy.md#known-limit-the-finish-guard-at-pilot-traffic)) |
| If agent-db stops answering | 503 after 2 s | 503 `SERVICE_UNAVAILABLE` after at most 2 s of server selection, logged as `mongodb.unavailable` |
| Pause option | none | M1 `AGENT_PRECONSULTATIONS_PAUSED`; the portal tells the patient the appointment goes on |

`MongoFailFastTest` checks the fast 503, and the promtool scenario "finish latency fast burn pages
within 10 minutes of a slow dependency" checks the page.

## Action items

| # | Item | Status |
|---|---|---|
| 1 | Latency burn-rate alert on `/messages` and `/finish` that pages in under 10 minutes, with `severity`, `team`, `summary` and `runbook_url` | Built: `AgentMessageLatency*`, `AgentFinishLatencyFastBurn`. The pilot-traffic guard is still open |
| 2 | Let the patient skip the pre-consultation when the agent is degraded | Built: M1 pause and the portal message. Turning it on or off needs a **restart** (seconds), not a redeploy; no restart at all would need a config store the platform does not have |
| 3 | Heavy Liquibase index builds in telemed-ia-intelligent-agent-db only off-peak (CI check plus a migration window in the release checklist) | Open: it belongs to the -db repository |
| — | Circuit breaker to the AI provider, and bounded agent-db waits (runbook M3, M4) | Built |
