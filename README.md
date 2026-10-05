# E-Commerce Order Management — Java Backend

A production-style order management system built on **Java 17 + Spring Boot 3.5**, with a **Vue 3** storefront and admin console. The backend is the only application service; Python is no longer part of this repository.

The system demonstrates a polyglot persistence architecture: **PostgreSQL** as the source of truth, **MongoDB** for the product catalog, **Elasticsearch** for order search and analytics, and **RabbitMQ** for asynchronous index synchronization.

---

## Architecture

```
 ## System Architecture

```text
                         ┌──────────────────────────┐
                         │      Vue 3 Frontend      │
                         │    Storefront + Admin     │
                         └────────────┬─────────────┘
                                      │
                                      │ HTTP / REST
                                      │ /api
                                      ▼
                         ┌──────────────────────────┐
                         │    Nginx / Web Server    │
                         │   Frontend + API Proxy   │
                         └────────────┬─────────────┘
                                      │
                                      │ /api
                                      ▼
                         ┌──────────────────────────┐
                         │   Spring Boot Backend    │
                         │        Java 17            │
                         │        Port 8000          │
                         └────────────┬─────────────┘
                                      │
                    ┌─────────────────┼─────────────────┐
                    │                 │                 │
                    │                 │                 │
                    ▼                 ▼                 ▼
          ┌────────────────┐ ┌────────────────┐ ┌────────────────────┐
          │  PostgreSQL    │ │    MongoDB     │ │   Elasticsearch    │
          │                │ │                │ │                    │
          │ • Users        │ │ • Products     │ │ • Order Search     │
          │ • Orders       │ │ • Variants     │ │ • Filters          │
          │ • Order Items  │ │ • Attributes   │ │ • Aggregations     │
          │ • Status       │ │ • Tags         │ │ • Search Projection│
          │ • Money        │ │ • Catalog      │ │                    │
          │ • Versions     │ │                │ │                    │
          │ • Outbox       │ │                │ │                    │
          └───────┬────────┘ └────────────────┘ └────────────────────┘
                  │
                  │ Transaction Commit
                  │
                  ▼
          ┌────────────────┐
          │  Outbox Event  │
          │  PostgreSQL    │
          └───────┬────────┘
                  │
                  │ Async Event
                  ▼
          ┌────────────────┐
          │    RabbitMQ    │
          │ Message Broker │
          └───────┬────────┘
                  │
                  │ Message
                  ▼
          ┌────────────────────────┐
          │  Spring AMQP Listener  │
          │                        │
          │ Builds Elasticsearch   │
          │ Order Projection       │
          └────────────┬───────────┘
                       │
                       │ Index / Update
                       ▼
              ┌────────────────────┐
              │   Elasticsearch   │
              │  Order Projection │
              └────────────────────┘
```

**Store responsibilities are deliberately separated:**

| Store | Owns |
|---|---|
| PostgreSQL | users, orders, order_items, sync_outbox — the transactional source of truth |
| MongoDB | product catalog documents |
| Elasticsearch | denormalized order projection for full-text search + aggregations |
| RabbitMQ | work queue for asynchronous Elasticsearch indexing |

---

## Asynchronous Dual-Write Strategy

Writes are **not** simultaneous. Elasticsearch is updated through a transactional outbox so the database is never blocked by, or silently desynchronized with, the search index.

```
POST /api/orders
      │
      ▼┌─────────────────────────────────────────────┐
│  SINGLE PostgreSQL TRANSACTION             │
│   1. INSERT order                           │
│   2. INSERT order_items                     │
│   3. INSERT outbox event                    │
└─────────────────────────────────────────────┘
      │  COMMIT ✓ durable
      ▼ outbox row (pending)
      │
      ▼
   RabbitMQ  ──► @RabbitListener  ──►  Elasticsearch (idempotent write,
 external versioning)
      │
      ▼
   outbox row marked processed
```

Key guarantees:

- **PostgreSQL commits first.** The RabbitMQ publish happens only *after* a successful commit.
- **At-least-once delivery.** Consumers are idempotent, so duplicate messages are harmless.
- **Outbox drain.** A scheduled job re-publishes pending events every 15 seconds, guaranteeing eventual delivery even if RabbitMQ was unavailable at write time.
- **Optimistic concurrency.** Documents are written with `version_type=external`, so a stale event can never overwrite a newer document.
- **Version tracking.** A database trigger owns `version` and `updated_at` on `orders`, and the outbox rejects events whose version is behind the index.

---

## Technology Stack

### Backend
- **Java 17**
- **Spring Boot 3.5** — Web, JDBC, Validation, Scheduling
- **Spring AMQP** — RabbitMQ producer/consumer (replaces the former task queue)
- **PostgreSQL JDBC** — explicit `JdbcTemplate` SQL, transactional outbox
- **Spring Data MongoDB** — catalog persistence
- **Elasticsearch Low-Level REST Client** — byte-exact query bodies, external versioning
- **Jackson** — JSON serialization
- **JUnit 5 + Testcontainers-style integration tests**

### Frontend
- **Vue 3** (Composition API, `<script setup>`)
- **Pinia** for state
- **Vue Router**
- **Vite** dev server with `/api` proxy
- **NGINX** for production static serving and API reverse proxy

### Infrastructure
- **Docker Compose** — PostgreSQL, MongoDB, Elasticsearch, RabbitMQ, backend, frontend
- **NGINX** — single-container deployment mode

---

## Project Structure

```
.
├── backend-java/                 # Java / Spring Boot application
│   ├── src/main/java/com/ecommerce/ordermanagement/
│   │   ├── config/               # DataSource, Rabbit, Elasticsearch, web config
│   │   ├── controller/           # REST controllers (API surface)
│   │   ├── model/                # DTOs and domain models
│   │   ├── repository/           # PostgreSQL / Mongo / Elasticsearch access
│   │   ├── service/              # Business logic
│   │   ├── seed/                 # Deterministic seed data generator
│   │   └── worker/               # RabbitMQ listener + outbox drain scheduler
│   ├── src/main/resources/
│   │   ├── application.yml       # Configuration
│   │   ├── db/001_schema.sql     # PostgreSQL schema (Java-owned)
│   │   └── search/               # Elasticsearch index mappings
│   └── pom.xml
│
├── frontend/                     # Vue 3 SPA
│   ├── src/
│   │   ├── api/                  # HTTP clients
│   │   ├── stores/               # Pinia stores
│   │   ├── views/                # Storefront + admin pages
│   │   └── components/
│   ├── nginx.conf                # Production NGINX config
│   └── Dockerfile
│
├── deploy/                       # Single-container deployment│   ├── entrypoint.sh
│   ├── nginx.single.conf
│   └── supervisord.conf
│
├── scripts/                      # Dev + operational helpers
├── docs/                         # Design notes and audits
├── knowledge-bytes/              # Architecture knowledge base
├── docker-compose.yml
├── docker-compose.infra.yml      # Data stores only
├── docker-compose.override.yml   # Local development stack
├── Dockerfile                    # Single-container (backend + worker + NGINX)
└── .env.example
```

---

## Requirements

| Tool | Version |
|---|---|
| JDK | 17+ |
| Maven | 3.8+ |
| Docker | 20+ |
| Docker Compose | v2 |
| Node.js | 18+ (frontend only) |

---

## Quick Start

### 1. Configure environment

```bash
cp .env.example .env
```

### 2. Start the full stack

```bash
docker compose up -d
```

This starts PostgreSQL, MongoDB, Elasticsearch, RabbitMQ, the Java backend, and the Vue frontend.

### 3. Apply the schema

The schema is Java-owned and applied on first start. To apply manually:

```bash
docker compose exec -T postgres \
  psql -U ecommerce -d ecommerce \
  < backend-java/src/main/resources/db/001_schema.sql
```

### 4. Seed data

```bash
docker compose exec backend java -jar /app/app.jar \
  --app.seed.run=true \
  --app.seed.reset=true \
  --spring.main.web-application-type=none
```

Produces: **8 users · 25 products · 40 orders · ~85 order items (avg ≥ 2 per order)**, including a deliberate snapshot-mismatch fixture and a fully caught-up Elasticsearch index. The seed is deterministic and self-verifying — it fails loudly if any invariant is violated.

### 5. Verify

```bash
curl http://localhost:8000/api/health
```

```json
{"postgres": "UP", "mongo": "UP", "elasticsearch": "UP"}
```

Open the frontend at **http://localhost:5173**.

---

## Local Development (without Docker for the app)

Run only the data stores:

```bash
docker compose -f docker-compose.infra.yml up -d
```

Run the backend directly:

```bash
mvn -f backend-java/pom.xml spring-boot:run
```

Run the frontend:

```bash
cd frontend
npm install
npm run dev
```

---

## Build and Test

```bash
# Compile and run all tests
mvn -f backend-java/pom.xml clean test

# Build the executable jar
mvn -f backend-java/pom.xml -DskipTests package

# Build the Docker image
docker build -t ecommerce-order-management .
```

Tests run in two layers:

- **Unit / contract tests** — API response shape, error format, validation, search document construction, seed determinism.
- **Integration tests** — run against a real PostgreSQL instance and verify snapshot immutability, trigger-owned versioning, outbox durability, and transactional atomicity. Core database behaviour is tested for real, not mocked.

---

## API Reference

Base URL: `http://localhost:8000/api`

### Health

| Method | Path | Description |
|---|---|---|
| `GET` | `/health` | Connectivity status of every backing store |

### Users

| Method | Path | Description |
|---|---|---|
| `GET` | `/users` | List all users |

### Products

| Method | Path | Description |
|---|---|---|
| `GET` | `/products` | List products — supports `category`, `search`, `include_inactive`, `skip`, `limit` |
| `GET` | `/products/{id}` | Fetch a single product |
| `POST` | `/products` | Create a product |
| `PUT` | `/products/{id}` | Update a product |

### Orders

| Method | Path | Description |
|---|---|---|
| `POST` | `/orders` | Create an order (writes items + outbox in one transaction) |
| `GET` | `/orders/{id}` | Fetch an order |
| `PATCH` | `/orders/{id}/status` | Update order status |

### Search

| Method | Path | Description |
|---|---|---|
| `POST` | `/search/orders` | Full-text search, filters, pagination, aggregations |

Request parameters: `q`, `statuses`, `date_from`, `date_to`, `price_min`, `price_max`, `page`, `size`.

Response includes `total`, `page`, `size`, `revenue` (aggregated order value), `status_facets`, and `hits`.

### Sync

| Method | Path | Description |
|---|---|---|
| `GET` | `/sync/status/{order_id}` | PostgreSQL vs Elasticsearch sync state for an order |
| `POST` | `/sync/drain-outbox` | Manually trigger an outbox drain |
| `POST` | `/sync/reindex` | Rebuild the entire Elasticsearch index from PostgreSQL |

---

## Data Model

### PostgreSQL

| Table | Purpose |
|---|---|
| `users` | Customer accounts |
| `orders` | Order header, status, total, version |
| `order_items` | Line items with **price snapshot** at purchase time |
| `sync_outbox` | Pending Elasticsearch synchronization events |

Money is stored as `NUMERIC(12,2)` and serialized as a fixed two-decimal string (e.g. `"29.99"`) to avoid floating-point drift.

Order line items are **immutable snapshots**. Editing a product in MongoDB never changes the price or title recorded on a historical order — which is why the search index is built purely from PostgreSQL, never from the live catalog.

### MongoDB

Product documents keyed by `_id`, with SKU, title, description, price, category, tags, attributes, variants, active flag, and `updated_at`. Monetary values use `Decimal128` to preserve exact precision.

### Elasticsearch

Index `orders` stores a denormalized projection of each order including customer details and item snapshots. The mapping defines `status` and `items.product_id` as `keyword`, and monetary fields as `scaled_float`, which keeps aggregations fast and exact while still supporting full-text search on customer name and item title.

---

## Configuration

All settings are supplied through environment variables, defined in `.env.example`:

| Variable | Purpose |
|---|---|
| `PG_DSN` | PostgreSQL connection string |
| `MONGO_DSN` | MongoDB connection string |
| `ES_URL` | Elasticsearch base URL |
| `RABBITMQ_URL` | RabbitMQ connection string |
| `CORS_ORIGINS` | Allowed frontend origins |
| `APP_SEED_RUN` | Enable seed execution on start |

---

## Deployment

**Docker Compose (multi-container)** — the default. Each service runs in its own container with NGINX handling static assets and reverse-proxying `/api` to the backend.

**Single container** — `Dockerfile` at the repository root builds one image containing the Java jar, the NGINX configuration, and Supervisor, which manages the backend process. Suitable for compact single-host deployments.

---

## Operational Notes

- **Elasticsearch mapping drift** — if the backend ever starts while Elasticsearch is unreachable, the index can be auto-created with dynamic mapping, which breaks aggregations. `POST /api/sync/reindex` repairs this.
- **Outbox monitoring** — pending outbox rows indicate indexing lag. If this count grows, the RabbitMQ consumer or Elasticsearch is likely unavailable; the drain job retries automatically.
- **Sync visibility** — the frontend exposes sync state per order, so queued vs fully indexed data is observable directly in the UI.

---

## Documentation

| Path | Contents |
|---|---|
| `docs/` | Design specifications, implementation notes, audits |
| `knowledge-bytes/` | Detailed architecture knowledge base |
| `NOTES.md` | Strategy and decision history |

---
