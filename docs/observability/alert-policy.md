# CP7 — Burn-rate alert policy

Rules: `deploy/prometheus/agent-slo-rules.yml`. Scenarios: `agent-slo-rules.test.yml` (run them with
promtool, see [README.md](README.md)). Every alert has `severity`, `team`, `service`, `slo`,
`summary`, `description` and a `runbook_url` that points to [runbook.md](runbook.md).

Budget consumed when an alert fires = burn rate × long window / 30 days.

| Alert | SLO | Burn | Threshold | Windows (long / short) | `for` | Severity | Budget gone when it fires |
|---|---|---|---|---|---|---|---|
| `AgentAvailabilityFastBurn` | availability | 14.4 | error ratio > 14.4 % | 1 h / 5 m | 5m | page | 2 % ≈ 12 failed requests |
| `AgentAvailabilitySlowBurn` | availability | 6 | error ratio > 6 % | 6 h / 30 m | 30m | ticket | 5 % ≈ 30 failed requests |
| `AgentMessageLatencyFastBurn` | message latency | 14.4 | > 72 % of turns over 4 s | 1 h / 5 m | 5m | page | 2 % ≈ 48 slow turns |
| `AgentMessageLatencySlowBurn` | message latency | 6 | > 30 % of turns over 4 s | 6 h / 30 m | 30m | ticket | 5 % ≈ 120 slow turns |
| `AgentFinishLatencyFastBurn` | finish latency | 14.4 | > 14.4 % of finishes over 1 s, and ≥ 5 finishes in 10 m | 10 m / 2 m | 2m | page | about 0.3 % |

- **Long and short windows:** the long window proves the burn is sustained. The short one makes the
  alert resolve soon after the fix, so it stops paging.
- **Unattended:** a 14.4× burn empties the budget in 50 h, and a 6× burn in 120 h.
- **Finish latency** uses short windows (10 m / 2 m) on purpose. In the drill, availability paged after 40
  minutes, and this alert must page in under 10 minutes when agent-db slows down. The `≥ 5 finishes`
  condition keeps a few slow finishes at night from paging anyone.

## Known limit: the finish guard at pilot traffic

The pilot's morning peak is about 27 finishes an hour, which is **about 4.5 finishes every 10 minutes**,
just under the `≥ 5` guard. At pilot traffic, `AgentFinishLatencyFastBurn` can therefore stay silent
during a real slowdown. The promtool scenario uses 4 finishes a minute, so it does not show this.

Options, which need a team decision:
- lower the guard to `≥ 3`;
- widen the windows to 30 m / 5 m;
- accept that the availability alert covers the pilot.

## Cause-based alerts we do not create

| Not an alert | Why |
|---|---|
| CPU or memory of the container | They do not mean patients are affected. If they matter, latency or errors show it. |
| A single 5xx from the AI provider, or one Groq retry | Retries are expected. The symptom alerts fire when patients are affected. |
| `agent.model.circuit.opened` or `mongodb.unavailable` log lines | They explain a page, they do not cause one. Search them while following the runbook. |
| Size of `outbox_events` | It belongs to the worker's freshness SLO, which is still planned. |
