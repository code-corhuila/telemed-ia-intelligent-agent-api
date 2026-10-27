# Observability — intelligent-agent-api

Week 11 workshop (logs, traces, metrics, SLOs, alerts, runbook, incident) applied to the real
service. The workshop designed it before the service existed. Where its prototype and this code
differ, these pages follow the code, and they say so.

| Page | Workshop step | What it answers |
|---|---|---|
| [journey.md](journey.md) | CP1 | Which hops a pre-consultation crosses and how the trace context travels |
| [log-contract.md](log-contract.md) | CP2 | Which fields every log line has, which events exist and what is never logged |
| [metrics.md](metrics.md) | CP4 | Which metrics exist, their labels and buckets |
| [slo.md](slo.md) | CP6 | The SLOs, their PromQL and the error budget |
| [alert-policy.md](alert-policy.md) | CP7 | The burn-rate alerts and why there are no cause-based ones |
| [runbook.md](runbook.md) | CP8 | What to do when each alert pages |
| [incident-drill.md](incident-drill.md) | CP9 | The drill, its postmortem and what was built from it |

## Where each piece lives

| Piece | Code |
|---|---|
| Trace context (`traceparent`, `X-Correlation-Id`) and `http.request.completed` | `adapter/in/http/observability/TraceContextFilter` |
| JSON log line | `app/observability/ContractLogFormatter` (`logging.structured.format.console`) |
| `agent_requests_total`, `agent_request_duration_seconds` | `app/observability/AgentRequestMetricsFilter` |
| Trace context in the outbox event | `adapter/out/persistence/mongo/MongoSummaryRepository` + `adapter/observability/CurrentTrace` |
| Recording and alerting rules, with promtool tests | `deploy/prometheus/agent-slo-rules.yml`, `agent-slo-rules.test.yml` |
| Runbook controls | `PausableStartPreConsultation`, `CircuitBreakingAgentModel`, `MongoClientConfiguration` |

## Check it locally

```bash
mvn -B test                                     # needs Docker: the app tests run MongoDB in Testcontainers

# With the service up (see the main README):
curl -s http://localhost:9086/actuator/prometheus | grep -E '^agent_'
docker compose -f deploy/compose.yml logs intelligent-agent-api | tail -n 5   # one JSON object per line

# Rules: syntax and the burn-rate scenarios.
MSYS_NO_PATHCONV=1 docker run --rm -v "$(pwd -W 2>/dev/null || pwd)/deploy/prometheus:/rules" -w /rules \
  --entrypoint promtool prom/prometheus:v3.1.0 check rules agent-slo-rules.yml
MSYS_NO_PATHCONV=1 docker run --rm -v "$(pwd -W 2>/dev/null || pwd)/deploy/prometheus:/rules" -w /rules \
  --entrypoint promtool prom/prometheus:v3.1.0 test rules agent-slo-rules.test.yml
```

`MSYS_NO_PATHCONV=1` and `pwd -W` are only needed in Git Bash on Windows; they are harmless elsewhere.

## Not in this repository

- **Collection:** this repository has no Prometheus server, log shipper (Loki) or trace backend. The
  service exposes the data, and loading `agent-slo-rules.yml` belongs to the platform's monitoring stack.
- **The worker and RabbitMQ hops** (outbox relay, `PreConsultationSummaryGenerated` delivery) and the
  summary-freshness SLO need `telemed-ia-worker`, which does not exist yet.
- **Spans:** the service propagates W3C `traceparent` and writes `trace_id`/`span_id` on every line, but
  it exports no spans (no OpenTelemetry SDK). A trace is reconstructed from logs by `trace_id`.
