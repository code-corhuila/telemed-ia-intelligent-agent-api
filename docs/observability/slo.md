# CP6 — SLIs, SLOs and error budget

All windows are 30 days, rolling. The PromQL reads the metrics in [metrics.md](metrics.md). The
recording rules in `deploy/prometheus/agent-slo-rules.yml` compute the same ratios over the alert windows.

## SLO 1 — Availability

- **SLI:** requests that did not fail with 5xx, divided by all requests to `/api/v1/agent`. A 4xx is
  the caller's and counts as good.
- **Target: 99.0 %.** A turn depends on an external AI provider. When its retries are exhausted the
  API answers 503, so 99.5 % is not realistic for the MVP.

```promql
sum(rate(agent_requests_total{outcome!="error"}[30d])) / sum(rate(agent_requests_total[30d]))
```

## SLO 2 — Message turn latency

- **SLI:** `POST /conversations/{id}/messages` answered in 4 s or less, divided by all such requests.
  This is the step that waits on the AI provider.
- **Target: 95 %.** A chat answer feels acceptable up to about 4 s; above that, patients resend or leave.

```promql
sum(rate(agent_request_duration_seconds_bucket{route="/api/v1/agent/conversations/{id}/messages",le="4.0"}[30d]))
  / sum(rate(agent_request_duration_seconds_count{route="/api/v1/agent/conversations/{id}/messages"}[30d]))
```

## SLO 3 — Finish latency

Added after the incident drill (action item 1), so that a slow agent-db pages before it fails requests.

- **SLI:** `POST /conversations/{id}/finish` answered in 1 s or less, divided by all such requests.
  Finish is one AI call plus one MongoDB transaction.
- **Target: 99 %.**

```promql
sum(rate(agent_request_duration_seconds_bucket{route="/api/v1/agent/conversations/{id}/finish",le="1.0"}[30d]))
  / sum(rate(agent_request_duration_seconds_count{route="/api/v1/agent/conversations/{id}/finish"}[30d]))
```

> Always write `le="4.0"` / `le="1.0"`. The workshop's `le="4"` matches no series, so the ratio would be empty.

## SLO 4 — Summary freshness (planned)

`PreConsultationSummaryGenerated` published within 60 s, 99 %. It needs the outbox relay in
`telemed-ia-worker`, which does not exist yet. The `traceparent` stored in the event is already in place for it.

## Traffic estimate (MVP pilot)

10 general practitioners × 20 first consultations a day, each preceded by a pre-consultation.

| | Per day | Per 30 days |
|---|---|---|
| Pre-consultations | 200 | 6 000 |
| Requests (1 start + 8 messages + 1 finish) | 2 000 | 60 000 |
| Message turns | 1 600 | 48 000 |
| Finishes | 200 | 6 000 |

About 40 % of the day's traffic arrives between 07:00 and 10:00, which is about 267 requests an
hour and 27 finishes an hour.

## Error budget

| SLO | Target | Budget | In requests / 30 d | As minutes of full outage |
|---|---|---|---|---|
| Availability | 99.0 % | 1 % | 600 failed requests | 432 min |
| Message latency | 95 % ≤ 4 s | 5 % | 2 400 slow turns | 2 160 min |
| Finish latency | 99 % ≤ 1 s | 1 % | 60 slow finishes | 432 min |

Minutes = budget × 43 200 min (30 days).

## Budget policy (team telemed-ia, group 2)

| Budget consumed | Action |
|---|---|
| 50 % | Raise it in the daily standup. Review the top error routes and slow traces. Releases continue. |
| 75 % | Only bug fixes and reliability work on intelligent-agent-api. Review the AI provider timeouts and the circuit breaker settings. |
| 100 % | Feature freeze for the agent journey until the budget recovers. The next sprint is a stabilisation sprint, and a blameless postmortem is required. |
