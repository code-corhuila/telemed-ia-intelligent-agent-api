# CP4 — Golden signals and metric catalog

Scraped from `http://<host>:9086/actuator/prometheus`, the management port, which is never
behind the gateway. `MetricsEndpointTest` guards the names, labels and buckets.

## intelligent-agent-api

| Signal | Metric | Type | Labels | PromQL |
|---|---|---|---|---|
| Traffic | `agent_requests_total` | counter | `route`, `method`, `outcome` | `sum by (route) (rate(agent_requests_total[5m]))` |
| Errors | `agent_requests_total` | counter | `outcome="error"` (5xx) | `sum(rate(agent_requests_total{outcome="error"}[5m])) / sum(rate(agent_requests_total[5m]))` |
| Latency | `agent_request_duration_seconds` | histogram | `route`, `method` | `histogram_quantile(0.95, sum by (le) (rate(agent_request_duration_seconds_bucket{route="/api/v1/agent/conversations/{id}/messages"}[5m])))` |
| Saturation | `tomcat_threads_busy_threads`, `tomcat_threads_config_max_threads` | gauge | `name` | `max(tomcat_threads_busy_threads) / max(tomcat_threads_config_max_threads)` |

Only `/api/v1/agent/**` is counted. Health, metrics and anything else are not.

`outcome` is `success` (< 400), `client_error` (4xx) or `error` (5xx). A 4xx is the caller's
mistake (for example, a message on a finished conversation) and does not burn the availability budget.

## Labels and cardinality

| Label | Values | Series |
|---|---|---|
| `route` | `/api/v1/agent/conversations`, `/api/v1/agent/conversations/{id}`, `…/{id}/messages`, `…/{id}/finish`, `/api/v1/agent/preconsultations`, `unmatched` | ≤ 6 |
| `method` | `GET`, `POST` | 2 |
| `outcome` | `success`, `client_error`, `error` | 3 |

`agent_requests_total` therefore has at most 36 series, which is safe. `route` is Spring's
`BEST_MATCHING_PATTERN`, never the raw path. A request that matches no handler (404, or one
refused by security first) is `unmatched`.

**Never a label:** `conversation_id`, `patient_id`, raw URLs, message text. They would create
one series per patient and put health data into the monitoring system. Use the logs and their
`trace_id` instead.

## Buckets

`agent_request_duration_seconds` publishes `le` = 0.1, 0.25, 0.5, 1.0, 2.0, 4.0, 8.0 and `+Inf`.

- Start and finish are MongoDB writes, under 0.5 s. Finish also calls the AI provider for the
  summary, so its SLO uses 1.0 s.
- A message waits on the AI provider. A chat answer is acceptable up to about 4 s, and its SLO uses 4.0 s.
- 8 s marks the slow tail before the portal and gateway timeouts.

> Prometheus writes these labels as `le="1.0"` and `le="4.0"`, not `le="1"` and `le="4"`. The workshop's
> PromQL used the short form, which matches no series. `MetricsEndpointTest.publishesTheBucketsTheSloRulesRead`
> fails if the exported form ever changes.

## Planned, outside this service

| Hop | Metric | Owner |
|---|---|---|
| gateway | `gateway_requests_total`, `gateway_request_duration_seconds` | telemed-ia-api-gateway |
| outbox relay | `agent_outbox_publish_delay_seconds`, `agent_outbox_pending_events` | telemed-ia-worker (does not exist yet) |
