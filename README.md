# stokr-platform

Modular monolith automated trading platform foundation (Phase 1 infrastructure).

## Prerequisites

- Java 21
- Maven 3.9+
- Docker Desktop (for local infrastructure)
- Node.js 20+ (for `stokr-ui`)

## Start infrastructure

From the repository root:

```bash
docker compose --env-file .env up -d
```

Services:

- PostgreSQL: `localhost:5432` (database `stokr_platform`)
- Redis: `localhost:6379`
- RabbitMQ AMQP: `localhost:5672` (UI: `http://localhost:15672`)
- pgAdmin: optional, `http://localhost:5050`

To include pgAdmin:

```bash
docker compose --env-file .env --profile tools up -d
```

## Run the full stack with Docker

```bash
docker compose --env-file .env --profile app up --build
```

Services:

- UI: `http://localhost:3000`
- API: `http://localhost:8080`
- Swagger UI: `http://localhost:8080/swagger-ui/index.html`

If Docker commands fail with an API route error against
`dockerDesktopLinuxEngine`, restart Docker Desktop and confirm `docker ps`
works before starting the stack.

## Run the backend

Defaults match `.env` for local development.

```bash
mvn clean install
mvn -pl stokr-bootstrap spring-boot:run
```

API base URL: `http://localhost:8080`

Swagger UI: `http://localhost:8080/swagger-ui/index.html`

Health: `http://localhost:8080/actuator/health`

## Run the UI

```bash
cd stokr-ui
npm install
npm run dev
```

UI dev server defaults to `http://localhost:5173` and expects the API on `http://localhost:8080`.

## Notes

- The runnable Spring Boot module is `stokr-bootstrap` (single deployable JAR).
- Database migrations live in `stokr-bootstrap/src/main/resources/db/migration`.
- JWT signing secret is configured via `JWT_SECRET` (see `application.yml`).
