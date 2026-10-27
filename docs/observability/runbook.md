# CP8 — Runbook

Service: intelligent-agent-api behind telemed-ia-api-gateway (`/api/v1/agent`). Team: `telemed-ia-group-2`.
Each alert's `runbook_url` points to its section below. The slow-burn alerts use the section of
their fast-burn alert.

## Confirm (every alert)

1. Which route is failing or slow:
   ```promql
   sum by (route) (rate(agent_requests_total{outcome="error"}[5m])) / sum by (route) (rate(agent_requests_total[5m]))
   histogram_quantile(0.95, sum by (le, route) (rate(agent_request_duration_seconds_bucket[5m])))
   ```
2. Which dependency it is. Count the WARN events of the last 15 minutes:
   ```logql
   sum by (event) (count_over_time({service="intelligent-agent-api"} | json | level=~"WARN|ERROR" [15m]))
   ```
   | Event | Points to |
   |---|---|
   | `agent.model.timeout`, `agent.model.failed`, `agent.model.unreachable`, `agent.model.circuit.opened` | AI provider |
   | `mongodb.unavailable` | agent-db (MongoDB `intelligent_agent`, rs0) |
   | `dependency.unavailable` | AI provider or appointment-scheduling-api (see the message) |
   | `request.failed` | a bug: read `error_type` and `stack_trace` |
3. Follow one failed request. Take `traceparent` from the response, or `traceId` from the error body:
   ```logql
   {service="intelligent-agent-api"} | json | trace_id="<trace_id>"
   ```
4. Saturation: `max(tomcat_threads_busy_threads) / max(tomcat_threads_config_max_threads)`.
5. Was intelligent-agent-api, the gateway or agent-db (a Liquibase changeset) deployed in the last hour?

## Mitigations

All settings are environment variables. Change them in `.env`, then run
`docker compose --env-file .env -f deploy/compose.yml up -d`. This recreates the container: a
restart of a few seconds, with no new image.

| # | Mitigation | When | Risk |
|---|---|---|---|
| M1 | **Pause new pre-consultations:** `AGENT_PRECONSULTATIONS_PAUSED=true`. New starts get 503 `PRECONSULTATION_PAUSED`, and the portal tells the patient the appointment goes on without one. Conversations already started can still finish. | AI provider or agent-db degraded, and no quick fix. | Low: the pre-consultation is optional for the appointment. |
| M2 | **Roll back:** check out the previous release commit and run `docker compose … up --build -d`. | Errors started right after a deploy. | Medium: the last release's changes are gone until the fix. |
| M3 | **Circuit breaker to the AI provider:** it is automatic. After `AGENT_MODEL_CIRCUIT_FAILURE_THRESHOLD` (5) failures in a row it answers 503 at once for `AGENT_MODEL_CIRCUIT_OPEN_FOR` (30s). To protect threads sooner, lower the threshold or lengthen the open time. | `agent.model.*` failures and busy threads. | Medium: more fast 503s, but threads are free for other routes. |
| M4 | **Bounded agent-db waits:** `MONGODB_SERVER_SELECTION_TIMEOUT` (2s), `MONGODB_CONNECT_TIMEOUT` (2s), `MONGODB_READ_TIMEOUT` (5s). Lower the read timeout only if agent-db is stalled and threads are piling up. | `mongodb.unavailable` events, or busy threads with slow finishes. | Medium: a timeout that is too low turns slow answers into 503s. |

Turn M1 off again (`false`) when the alert has resolved.

## AgentAvailabilityFastBurn

More than 14.4 % of requests failed with 5xx, both in the last hour and in the last 5 minutes.
About 2 % of the monthly budget is already gone.

1. Confirm, steps 1–3: find which event dominates.
2. AI provider:
   - if `agent.model.circuit.opened` already appears, the breaker is shedding load (M3);
   - if failures go on for more than 15 minutes, apply **M1** and open a ticket with the provider, with
     trace ids and timestamps.
3. agent-db (`mongodb.unavailable`): check the rs0 primary and any running index build. Apply **M1**
   if it lasts more than 15 minutes.
4. `request.failed` right after a deploy: apply **M2**.

## AgentMessageLatencyFastBurn

More than 72 % of message turns took over 4 s. Patients wait, resend or leave.

1. p95 of `/messages`, and the `duration_ms` of `agent.model.responded`. Is the provider slow, or
   is it retrying (`attempt` > 1)?
2. A slow provider with no errors does not open the breaker. If it lasts, apply **M1**.
3. Busy threads near the maximum: apply **M3** with a lower threshold.

## AgentFinishLatencyFastBurn

More than 14.4 % of finishes took over 1 s in the last 10 minutes. A slow agent-db or AI provider is the
usual cause. This is the alert the incident drill asked for.

1. Follow a slow `/finish`. Compare `agent.model.responded.duration_ms` with the time to
   `preconsultation.summary.generated`. The gap is the MongoDB transaction.
2. Slow agent-db: check the rs0 primary, disk I/O, and any index build from telemed-ia-intelligent-agent-db.
   Stop or reschedule a heavy build off-peak.
3. Slow provider: as for message latency.
4. If finishes start failing (`mongodb.unavailable`), the availability alert follows. Apply **M1**.

## Escalation

| Time since page | Who | Action |
|---|---|---|
| 0 min | On-call developer, intelligent-agent-api | Acknowledge, confirm, apply M1 if patients are blocked |
| 15 min | Agent domain tech lead | Decide M2, M3 or M4. Check agent-db and the gateway |
| 30 min | Scrum master / product owner | Inform the appointment and professional teams. Decide what to tell patients |

## Verify recovery

- The alert resolves. The short window clears first, then the long one.
- `agent:error_ratio:rate5m < 0.01`, `agent:message_slow_ratio:rate5m < 0.05` and
  `agent:finish_slow_ratio:rate10m < 0.01` for 15 minutes.
- A test pre-consultation works end to end: start 201, a message 200, finish 200. One
  `PreConsultationSummaryGenerated` event with the same `traceparent` exists in `outbox_events`.
- M1 is back to `false`. Record the budget spent and open the postmortem (see [incident-drill.md](incident-drill.md)).
