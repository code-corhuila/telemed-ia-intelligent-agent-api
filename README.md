# telemed-ia-intelligent-agent-api

Intelligent Agent bounded context (HU-06): the patient's pre-consultation before an
appointment. The agent asks follow-up questions and produces a structured summary for the
professional. It never diagnoses, prescribes or replaces the professional (ADR-008).

## Least privilege

| Layer | Rule |
|---|---|
| Who | Only patients with a token signed by identity-api (RS256, issuer `telemed-ia-identity-and-access`). Every other role gets `403`; anything else is denied. |
| Which data | The patient is taken from the token, never from the request body. A patient only reaches their own conversations. |
| Which appointment | One pre-consultation per appointment, and none without one. |
| Keys | The service holds identity-api's **public** key only: it verifies tokens, never issues them. |
| Operations | Health and info on a separate management port, published only on localhost; no other actuator endpoint. The container runs as an unprivileged user. |

## Modules

```
intelligent-agent-core/       domain and use cases, plain Java
intelligent-agent-adapters/   HTTP in; MongoDB and the AI provider out
intelligent-agent-app/        composition root and configuration
```

## Run

```bash
mvn -B verify                                   # tests

cp .env.example .env                            # then set JWT_PUBLIC_KEY_HOST_FILE
docker network create platform                  # once, if it does not exist
docker compose --env-file .env -f deploy/compose.yml up --build -d
curl http://localhost:9086/actuator/health      # {"status":"UP"}
```

| Variable | Meaning |
|---|---|
| `PORT` / `MANAGEMENT_PORT` | API (8086, inside the `platform` network only; reach it through the gateway) and health (9086, localhost only) ports |
| `JWT_ISSUER` | issuer of identity-api's tokens |
| `JWT_PUBLIC_KEY_HOST_FILE` | identity-api's public key (PEM) on the host |

## Branching

`develop`, `qa` and `main` take no direct commits. Work goes through short-lived branches and
pull requests; promotion is by `git cherry-pick -x`. `main` requires the teacher's approval.
