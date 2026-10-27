# CP2 — Structured log contract

Every line on stdout is one JSON object written by `ContractLogFormatter`.
`LogEventsTest`, `ContractLogFormatterTest` and the adapter tests guard the events below.

## Fields on every line

| Field | Example | Source |
|---|---|---|
| `timestamp` | `2026-10-27T15:00:01.120Z` | event time, RFC 3339 UTC |
| `level` | `INFO` | logger |
| `service` | `intelligent-agent-api` | constant |
| `version` | `0.1.0` | `SERVICE_VERSION` |
| `environment` | `dev` / `qa` / `main` | `ENVIRONMENT` |
| `trace_id` | 32 hex | `traceparent`, or new |
| `span_id` | 16 hex | this request's span |
| `correlation_id` | value of `X-Correlation-Id` | same as the error envelope's `traceId` |
| `event` | `conversation.started` | explicit; `log.<LoggerName>` for lines without one (framework logs) |
| `message` | `conversation started` | short human text |
| `error_type`, `stack_trace` | — | only when an exception is attached |

Lines written outside a request (startup, shutdown) carry `trace_id`, `span_id` and `correlation_id` as `null`.

## Events

| Event | Level | Extra fields | Written by |
|---|---|---|---|
| `http.request.completed` | INFO, ERROR on 5xx | `route` (template), `method`, `status`, `duration_ms` | `TraceContextFilter` |
| `conversation.started` | INFO | `conversation_id`, `conversation_status` | `ConversationController` |
| `conversation.message.answered` | INFO | `conversation_id`, `message_chars`, `turn`, `conversation_status` | `TurnController` |
| `conversation.finish.received` | INFO | `conversation_id` | `TurnController` |
| `preconsultation.summary.generated` | INFO | `summary_id`, `conversation_id`, `outbox_event_id` | `MongoSummaryRepository` |
| `agent.model.responded` | INFO | `attempt`, `duration_ms` | `GroqAgentModel` |
| `agent.model.failed` | WARN | `status`, `provider_error_code`, `attempt`, `duration_ms` | `GroqAgentModel` |
| `agent.model.timeout` | WARN | `attempt`, `duration_ms` | `GroqAgentModel` |
| `agent.model.unreachable` | WARN | `error_type`, `attempt`, `duration_ms` | `GroqAgentModel` |
| `agent.model.circuit.opened` | WARN | `consecutive_failures`, `open_for_ms` | `CircuitBreakingAgentModel` |
| `agent.model.circuit.closed` | INFO | — | `CircuitBreakingAgentModel` |
| `request.rejected` | WARN | `error_code` (domain rule) | `ApiExceptionHandler` |
| `preconsultation.paused` | WARN | `error_code: PRECONSULTATION_PAUSED` | `ApiExceptionHandler` |
| `dependency.unavailable` | WARN | `error_code: SERVICE_UNAVAILABLE` (AI provider or appointments) | `ApiExceptionHandler` |
| `mongodb.unavailable` | WARN | `error_code: SERVICE_UNAVAILABLE`, `error_type` | `ApiExceptionHandler` |
| `request.failed` | ERROR | `error_code: INTERNAL_ERROR`, `error_type`, `stack_trace` | `ApiExceptionHandler` |

Only `request.failed` (and `http.request.completed` with a 5xx) is ERROR. A dependency that is
down is answered with a 503 and logged as WARN: it needs the runbook, not a code fix.

## Never logged

| Data | Instead |
|---|---|
| Patient messages, agent questions, summary fields | `message_chars` (length) only |
| Patient name, email, document, phone | `patient_id` is in the token, not in the lines |
| Raw paths (`/conversations/3f1c…`) | `route` template (`/conversations/{id}`) |
| `Authorization`, JWT, `GROQ_API_KEY`, `MONGODB_URI` | never; `AgentProperties.Groq.toString()` hides the key |
| Groq error bodies (they may echo the patient's words) | `status` and `provider_error_code` only |

## Example: a finish that waits on a retry (one `trace_id`)

```json
{"timestamp":"2026-10-27T15:00:01.135Z","level":"INFO","service":"intelligent-agent-api","version":"0.1.0","environment":"dev","trace_id":"4bf92f3577b34da6a3ce929d0e0e4736","span_id":"00f067aa0ba902b7","correlation_id":"c0r-4bf92f35","event":"conversation.finish.received","message":"finish request accepted","conversation_id":"3f1c2b7a-0000-4000-8000-000000000001"}
{"timestamp":"2026-10-27T15:00:21.140Z","level":"WARN","service":"intelligent-agent-api","version":"0.1.0","environment":"dev","trace_id":"4bf92f3577b34da6a3ce929d0e0e4736","span_id":"00f067aa0ba902b7","correlation_id":"c0r-4bf92f35","event":"agent.model.timeout","message":"AI provider did not answer in time","attempt":1,"duration_ms":20003}
{"timestamp":"2026-10-27T15:00:22.310Z","level":"INFO","service":"intelligent-agent-api","version":"0.1.0","environment":"dev","trace_id":"4bf92f3577b34da6a3ce929d0e0e4736","span_id":"00f067aa0ba902b7","correlation_id":"c0r-4bf92f35","event":"agent.model.responded","message":"AI provider answered","attempt":2,"duration_ms":870}
{"timestamp":"2026-10-27T15:00:22.331Z","level":"INFO","service":"intelligent-agent-api","version":"0.1.0","environment":"dev","trace_id":"4bf92f3577b34da6a3ce929d0e0e4736","span_id":"00f067aa0ba902b7","correlation_id":"c0r-4bf92f35","event":"preconsultation.summary.generated","message":"summary and its outbox event written","summary_id":"c2d4…","conversation_id":"3f1c2b7a-0000-4000-8000-000000000001","outbox_event_id":"5e6f…"}
{"timestamp":"2026-10-27T15:00:22.335Z","level":"INFO","service":"intelligent-agent-api","version":"0.1.0","environment":"dev","trace_id":"4bf92f3577b34da6a3ce929d0e0e4736","span_id":"00f067aa0ba902b7","correlation_id":"c0r-4bf92f35","event":"http.request.completed","message":"request completed","route":"/api/v1/agent/conversations/{id}/finish","method":"POST","status":200,"duration_ms":21200}
```

## Finding one request

```logql
{service="intelligent-agent-api"} | json | trace_id="<trace_id from the traceparent response header>"
{service="intelligent-agent-api"} | json | correlation_id="<traceId from the error envelope>"
```

## Differences from the workshop contract

- `duration_ms` is on `http.request.completed` and the `agent.model.*` lines, not on every end event.
- The Idempotency-Key is never logged, so the workshop's "SHA-256 prefix" rule does not apply.
- `preconsultation.summary.rejected` and `mongodb.transaction.timeout` are not events here.
  - A summary field that crosses the clinical-safety line (ADR-008) is dropped before the summary is stored, so no summary is rejected.
  - An unreachable agent-db is logged as `mongodb.unavailable`.
