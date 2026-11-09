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
| Spans exported to Jaeger through the platform OTel collector (`OTLP_TRACING_ENDPOINT`, default `http://otel-collector:4318/v1/traces`) | `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp`; child spans `agent.appointments.request` (`HttpAppointments`), `agent.model.request` (`GroqAgentModel`) and `agent.mongodb.transaction` (`ObservedUnitOfWork`) |
| Trace context in the outbox event | `adapter/out/persistence/mongo/MongoSummaryRepository` + `adapter/observability/CurrentTrace` |
| Recording and alerting rules, with promtool tests | `telemed-ia-infra-postgres/observability/rules/intelligent-agent.yml`, `tests/prometheus/intelligent-agent.test.yml` |
| Runbook controls | `PausableStartPreConsultation`, `CircuitBreakingAgentModel`, `MongoClientConfiguration` |

## Check it locally

```bash
mvn -B test                                     # needs Docker: the app tests run MongoDB in Testcontainers

# With the service up (see the main README):
curl -s http://localhost:9086/actuator/prometheus | grep -E '^agent_'
docker compose -f deploy/compose.yml logs intelligent-agent-api | tail -n 5   # one JSON object per line
```

The rules and their burn-rate scenarios are checked in `telemed-ia-infra-postgres` with
`tests/validate-observability.ps1` (promtool `check rules` and `test rules`).

## Not in this repository

- **Collection and alerting:** this repository has no Prometheus server, log shipper (Loki) or trace
  backend. The service exposes the data; the platform Prometheus in `telemed-ia-infra-postgres` scrapes
  `intelligent-agent-api:9086`, evaluates the rules and sends the alerts to its Alertmanager.
- **The worker and RabbitMQ hops** (outbox relay, `PreConsultationSummaryGenerated` delivery) and the
  summary-freshness SLO need `telemed-ia-worker`, which does not exist yet.
- **Trace storage:** the spans go to the collector and Jaeger of `telemed-ia-infra-postgres`; this
  repository runs neither. The `trace_id` and `span_id` of every log line are the ids of the span in Jaeger.
