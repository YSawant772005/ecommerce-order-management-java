# Knowledge Bytes — Infrastructure, Deployment & Docs

> **How to read this document**
> Each byte is one small, self-contained idea. Read them in order.
>
> **Reader assumed:** comfortable with basic programming, new to Docker or this project.
> **Scope:** every root-level file, `deploy/`, `scripts/`, `docs/`, and the ignore files.
> The Java backend and the frontend each have their own knowledge-bytes file.

---


## PART 1 — PROJECT OVERVIEW AND ARCHITECTURE


### Byte 1: Two deployment shapes, one codebase

**Builds on:** None — starting point

**In plain terms:**
The same Java jar and the same Vue build can be run either as six separate containers or as one
container running everything. Both are supported.

**The code:**
```
Shape A — multi-container (docker compose)
   postgres · mongo · elasticsearch · rabbitmq · backend · frontend

Shape B — single container (root Dockerfile)
   ubuntu:24.04
     ├── postgres   (supervised)
     ├── mongod     (supervised)
     ├── elastic    (supervised)
     ├── rabbitmq   (supervised)
     ├── java -jar  (supervised)
     └── nginx      (supervised)
   everything on 127.0.0.1 inside one container, exposed on 8080
```

**What's happening:**
Shape A is the development default: each service is its own container on a shared network.
Shape B is for compact single-host deployment: one `ubuntu` image with all six processes managed
by Supervisor.

**Why it matters:**
Both shapes run identical application code. Only the process topology differs, so a bug found in
one is reproducible in the other.

---


### Byte 2: The three compose files

**Builds on:** Byte 1

**In plain terms:**
Three YAML files, each with a distinct job. Docker merges the base and the override automatically.

**The code:**
```
docker-compose.yml          the four data stores
docker-compose.override.yml the Java backend + the frontend   ← auto-merged
docker-compose.infra.yml    the four data stores ONLY
```

**What's happening:**
`docker compose up -d` with no `-f` reads `docker-compose.yml` **and** auto-merges
`docker-compose.override.yml`. Passing `-f docker-compose.infra.yml` explicitly suppresses the
override, so only the data stores start and the backend runs on the host.

**Why it matters:**
The override file is the reason the stack "just works" with one command, and also the reason an
explicit `-f` is needed to get the stores-only variant. Forgetting that `-f` suppresses the merge
is a common surprise.

---


### Byte 3: The four data stores and their health checks

**Builds on:** Byte 2

**In plain terms:**
PostgreSQL, MongoDB, Elasticsearch, and RabbitMQ, each with a named volume and a health check.

**The code:**
```yaml
postgres:
  image: postgres:16
  ports: [ "5432:5432" ]
  volumes: [ "postgres-data:/var/lib/postgresql/data" ]
  healthcheck:
    test: ["CMD-SHELL", "pg_isready -U ecommerce -d ecommerce"]

mongo:
  image: mongo:7.0
  healthcheck:
    test: ["CMD", "mongosh", "--quiet", "--eval", "db.adminCommand('ping').ok"]

elasticsearch:
  image: docker.elastic.co/elasticsearch/elasticsearch:8.13.4
  environment:
    discovery.type: single-node
    xpack.security.enabled: "false"
    ES_JAVA_OPTS: "-Xms512m -Xmx512m"
  healthcheck:
    test: ["CMD-SHELL", "curl -fsS http://localhost:9200/_cluster/health || exit 1"]

rabbitmq:
  image: rabbitmq:3.12-management
  ports: [ "5672:5672", "15672:15672" ]   # 15672 = management UI
```

**What's happening:**
Named volumes mean data survives `docker compose down`. Each health check asks the store the
question only that store can answer.

**Why it matters:**
The backend's `depends_on: condition: service_healthy` blocks container startup until every store
answers. Without health checks the jar would start first, fail to connect, and — depending on the
driver — hang or crash.

---


### Byte 4: Why Elasticsearch needs four settings

**Builds on:** Byte 3

**In plain terms:**
Elasticsearch is the fussiest container in the stack, and three of the four settings exist so it
can run on a laptop.

**The code:**
```yaml
discovery.type: single-node          # don't wait for a cluster
xpack.security.enabled: "false"      # no TLS, no auth
ES_JAVA_OPTS: "-Xms512m -Xmx512m"    # modest heap
ulimits:
  memlock: { soft: -1, hard: -1 }   # unlocked memlock
```

**What's happening:**
Single-node discovery stops it blocking on cluster formation. Security disabled avoids certificate
setup. The 512m heap prevents it eating the machine. `memlock: -1` removes the bootstrap memory
lock, which otherwise fails in most container runtimes.

**Why it matters:**
The compose file's own comment says this: omitting any one of them is the most common reason
Elasticsearch refuses to start. These are environment workarounds, not application settings.


---

## PART 2 — THE JAVA BACKEND CONTAINER


### Byte 5: How the backend container is built

**Builds on:** Byte 2

**In plain terms:**
The backend image is a JRE plus the already-built jar. No Maven, no compilation inside Docker.

**The code:**
```yaml
backend:
  build:
    context: .
    dockerfile: backend-java/Dockerfile
  command: java -jar /app/app.jar
  ports: [ "8000:8000" ]
  environment:
    PG_DSN:      postgresql://ecommerce:ecommerce@postgres:5432/ecommerce
    MONGO_DSN:   mongodb://mongo:27017
    MONGO_DB_NAME: ecommerce
    ES_URL:      http://elasticsearch:9200
    RABBITMQ_URL: amqp://guest:guest@rabbitmq:5672//
  depends_on:
    postgres:       { condition: service_healthy }
    mongo:          { condition: service_healthy }
    elasticsearch:  { condition: service_healthy }
    rabbitmq:       { condition: service_healthy }
```

**What's happening:**
The DSNs use compose **service names** as hostnames (`postgres`, not `127.0.0.1`), which Docker's
internal DNS resolves. `depends_on` with `service_healthy` guarantees the stores are ready before
the JVM starts.

**Why it matters:**
The build context is `.` (the repo root) rather than `backend-java`, because the Dockerfile needs
the jar from `backend-java/target/`. This is also why `.dockerignore` must **not** exclude
`backend-java/target/`.

---


### Byte 6: Why the backend has no separate worker

**Builds on:** Byte 5

**In plain terms:**
The message consumer and the scheduled outbox drain run inside the same JVM as the web server.

**The code:**
```
composed container  →  java -jar app.jar
                       ├── HTTP server        :8000
                       ├── @RabbitListener    consumer for the es.sync queue
                       └── @Scheduled          outbox drain, every 15s
```

**What's happening:**
The previous architecture needed a separate queue worker and a separate scheduler process. Spring
AMQP and Spring Scheduling are libraries inside the application, so the same jar does all three
jobs.

**Why it matters:**
One container, one process, one health check. A failed deploy cannot leave a worker running
against a database the API no longer understands.

---


### Byte 7: The test-port proxy

**Builds on:** Byte 5

**In plain terms:**
A tiny sidecar republishes PostgreSQL on a second host port so host-run tests can reach it.

**The code:**
```yaml
postgres-test-port:
  image: alpine/socat
  container_name: ecommerce-postgres-proxy
  entrypoint: ["sh", "-c",
    "apk add --no-cache socat >/dev/null 2>&1; exec socat TCP-LISTEN:5432,fork,reuseaddr TCP:postgres:5432"]
  ports: [ "55433:5432" ]
```

**What's happening:**
On this machine a native PostgreSQL already owns host port 5432. `socat` listens on 5432 *inside
the container* and forwards to the compose `postgres` service, published on host port 55433.

**Why it matters:**
This exists only because of a host port collision. The application itself is unaffected — it uses
in-network `postgres:5432`. Without this proxy, integration tests running on the host via Maven
cannot reach the containerised database.

---


## PART 3 — THE SINGLE-CONTAINER DEPLOYMENT


### Byte 8: The root Dockerfile

**Builds on:** Byte 1

**In plain terms:**
One Ubuntu image containing all four databases, the Java runtime, the jar, nginx, and Supervisor.

**The code:**
```dockerfile
FROM ubuntu:24.04
RUN apt-get update && apt-get install -y --no-install-recommends \
      postgresql-16 rabbitmq-server supervisor nginx curl \
      openjdk-17-jre-headless \
    && rm -rf /var/lib/apt/lists && useradd -m -s /bin/bash es

RUN curl -fsSL https://fastdl.mongodb.org/.../mongodb-...tgz -o /tmp/mongo.tgz \
    && tar -xzf /tmp/mongo.tgz -C /opt/mongo --strip-components=1
RUN curl -fsSL https://artifacts.elastic.co/.../elasticsearch-8.13.4.tar.gz -o /tmp/es.tgz \
    && tar -xzf /tmp/es.tgz -C /opt/es --strip-components=1 && chown -R es:es /opt/es

COPY backend-java/target/order-management-1.0.0.jar      /app/app.jar
COPY backend-java/src/main/resources/db/001_schema.sql   /app/sql/001_schema.sql
COPY frontend/dist                /usr/share/nginx/html
COPY deploy/nginx.single.conf      /etc/nginx/sites-available/app
COPY deploy/supervisord.conf       /etc/supervisor/conf.d/app.conf
COPY deploy/entrypoint.sh          /entrypoint.sh
```

**What's happening:**
PostgreSQL and RabbitMQ come from apt. MongoDB and Elasticsearch have no suitable apt packages, so
both tarballs are downloaded and unpacked into `/opt`. Everything is copied in already built — the
image performs no compilation.

**Why it matters:**
The schema is copied from `backend-java/src/main/resources/db/`, not from the backend source tree.
That path is what makes the image independent of any other project directory.

---


### Byte 9: Supervisor runs six processes

**Builds on:** Byte 8

**In plain terms:**
Supervisor keeps the databases, the JVM, and nginx all alive with `autorestart = true`.

**The code:**
```ini
[program:postgres]
user = postgres
command = /usr/lib/postgresql/16/bin/postgres -D /data/pg -p 5432 -h 127.0.0.1 -k /tmp

[program:elasticsearch]
user = es
environment = ES_JAVA_OPTS="-Xms512m -Xmx512m"
command = /opt/es/bin/elasticsearch -E path.data=/data/es -E path.logs=/data/eslogs \
          -E discovery.type=single-node -E xpack.security.enabled=false ...

[program:api]
environment = PG_DSN="postgresql://ecommerce:ecommerce@127.0.0.1:5432/ecommerce",...
command = java -jar /app/app.jar

[program:nginx]
command = /usr/sbin/nginx -g "daemon off;"
```

**What's happening:**
Each program is one process with its own log file under `/data/logs/`. Elasticsearch runs as the
unprivileged `es` user because it refuses to start as root. `nginx` needs `daemon off` so
Supervisor — not nginx — owns the process tree.

**Why it matters:**
`autorestart = true` on every program means a crashed database comes back without restarting the
container. That is the entire resilience story for this deployment shape.


---

### Byte 10: The entrypoint's boot sequence

**Builds on:** Byte 9

**In plain terms:**
The script initialises data directories, starts Supervisor in the background, then applies the
schema and seeds — each step waiting for the previous one.

**The code:**
```bash
if [ ! -s /data/pg/PG_VERSION ]; then
  su postgres -c ".../initdb -D /data/pg -U postgres --auth=trust -E UTF8"
fi

supervisord -c /etc/supervisor/supervisord.conf &
SUP_PID=$!

for _ in $(seq 1 60); do pg_isready -h 127.0.0.1 -p 5432 -q && break; sleep 2; done

psql … <<'SQL'
CREATE ROLE ecommerce LOGIN PASSWORD 'ecommerce' SUPERUSER;
CREATE DATABASE ecommerce OWNER ecommerce;
SQL

psql … -f /app/sql/001_schema.sql >/dev/null

for _ in $(seq 1 45); do (exec 3<>/dev/tcp/127.0.0.1/27017) 2>/dev/null && break; sleep 2; done
for _ in $(seq 1 90); do curl -fsS http://127.0.0.1:9200/ >/dev/null 2>&1 && break; sleep 2; done

USERS=$(psql … -tAc "SELECT count(*) FROM users")
if [ "$USERS" = "0" ]; then
  java -jar /app/app.jar --app.seed.run=true --app.seed.reset=true \
       --spring.main.web-application-type=none >>/data/logs/seed.log 2>&1
fi
wait "$SUP_PID"
```

**What's happening:**
`initdb` runs only if `/data/pg/PG_VERSION` is absent, so a restart reuses existing data. Each
`for` loop is a bounded wait — 60×2s for PostgreSQL, 90×2s for Elasticsearch, which is genuinely
slow to start. The seed runs only when the `users` table is empty.

**Why it matters:**
The `users` count is the idempotency guard: a container restart does **not** re-seed and overwrite
real data. The final `wait` keeps PID 1 alive so the container stays up.

---


### Byte 11: nginx inside the single container

**Builds on:** Byte 8

**In plain terms:**
nginx is the only thing with a published port. It serves the UI and proxies the API.

**The code:**
```nginx
server {
  listen 8080;
  root /usr/share/nginx/html;
  index index.html;

  location /api/ { proxy_pass http://127.0.0.1:8000; }
  location /      { try_files $uri /index.html; }
}
```

**What's happening:**
Everything binds to `127.0.0.1` inside the container, so the databases and the JVM are unreachable
from outside. nginx listens on 8080 and is the single door.

**Why it matters:**
`try_files … /index.html` is required for client-side routing: a deep link like
`/admin/orders/5` must return the app shell rather than a 404. Without it, browser refresh breaks
the admin screens.

---


## PART 4 — CONFIGURATION FILES


### Byte 12: `.env.example`

**Builds on:** Byte 5

**In plain terms:**
The single list of environment variables the project understands. Copy it to `.env` to use it.

**The code:**
```bash
ORDER_SYNC_STRATEGY=dual_write

PG_DSN=postgresql://ecommerce:ecommerce@127.0.0.1:55432/ecommerce
MONGO_DSN=mongodb://127.0.0.1:27017
MONGO_DB_NAME=ecommerce
ES_URL=http://127.0.0.1:9200
ES_ORDERS_INDEX=orders
RABBITMQ_URL=amqp://guest:guest@127.0.0.1:5672//

CORS_ORIGINS=http://localhost:5173
API_HOST=127.0.0.1
API_PORT=8000

SEED_ANCHOR_DATE=2026-09-30T00:00:00Z
SEED_DEFAULT=42
```

**What's happening:**
Every connection string is in one place, so the same jar runs against Docker hostnames or local
ones purely by changing these values. Note the PostgreSQL port is **55432**, not 5432.

**Why it matters:**
The 55432 port exists because a system PostgreSQL already owns 5432 on this machine. That single
collision explains the port in `.env.example`, in `docker-compose.infra.yml`, and in the test
proxy from Byte 7.

---


### Byte 13: The fixed seed anchor date

**Builds on:** Byte 12

**In plain terms:**
The seed generates order dates relative to a constant date, never to "today".

**The code:**
```bash
# A constant anchor, never the current date, so the date-bucket invariants
# cannot expire and two runs are byte-reproducible.
SEED_ANCHOR_DATE=2026-09-30T00:00:00Z
```

**What's happening:**
Order dates are computed as offsets from this anchor. The comment states both reasons: invariants
that check date ranges cannot expire, and two runs on different days produce identical data.

**Why it matters:**
A seed anchored to the current date would silently start failing its own date invariants once the
anchor moved out of the expected window. Pinning it makes the dataset reproducible forever.

---


### Byte 14: `.gitignore` versus `.dockerignore`

**Builds on:** Byte 12

**In plain terms:**
Two ignore lists with different jobs and one deliberate exception between them.

**The code:**
```gitignore
# .gitignore
.env            # never commit real secrets
!.env.example   # but do commit the template
node_modules/   dist/   .vite/
backend-java/target/
```
```dockerignore
node_modules/
dist/
.git/
frontend/node_modules/
# NOTE: backend-java/target/ is deliberately NOT ignored — the image copies the
# built jar from there.
```

**What's happening:**
`.gitignore` decides what enters version control. `.dockerignore` decides what enters the build
context — a performance optimisation, since sending `node_modules` to the daemon is slow.

**Why it matters:**
The exception is the important part. Excluding `backend-java/target/` would make the build succeed
and then fail at `COPY`, because the jar would not be in the context. The comment exists so nobody
"tidies up" that gap.


---

## PART 5 — SCRIPTS


### Byte 15: `scripts/dev-stack.sh`

**Builds on:** Byte 3

**In plain terms:**
A fallback that runs the same four real databases natively, without Docker.

**The code:**
```bash
STACK="$HOME/.local/stack"        # binaries
DATA="$HOME/.local/stackdata"     # data + logs
PGPORT=55432

_pg_ready()     { "$PG_BIN/pg_isready" -h 127.0.0.1 -p "$PGPORT" -q; }
_mongo_ready()  { (exec 3<>/dev/tcp/127.0.0.1/27017) 2>/dev/null; }
_es_ready()     { curl -fsS http://127.0.0.1:9200/ >/dev/null; }
_rabbit_ready() { "$STACK/rabbitmq/bin/rabbitmq-diagnostics" -q ping; }

case "${1:-start}" in
  start) start_postgres; start_mongo; start_es; start_rabbit; status ;;
  stop)  stop_all ;;
esac
```

**What's happening:**
Each `start_*` function first checks its own readiness probe and returns early if the service is
already up — so the script is safe to run repeatedly. Readiness is probed with a real question:
`pg_isready` for PostgreSQL, a raw TCP connect for MongoDB, a cluster-health HTTP call for
Elasticsearch, and `rabbitmq-diagnostics ping` for RabbitMQ.

**Why it matters:**
The script's own comment stresses it: both paths give the application **real** databases over real
sockets, neither is a mock. It exists for hosts without Docker, and it never pretends a service is
up when it is not.

---


## PART 6 — DOCUMENTATION


### Byte 16: `README.md`

**Builds on:** All previous bytes

**In plain terms:**
The entry point for anyone new: what the project is, how to run it, and what each port is.

**The code:**
```
What this system does
The polyglot persistence diagram
Quick start        — cp .env.example .env; docker compose up -d; verify /api/health
Local development  — infra-only compose + mvn spring-boot:run + npm run dev
API reference      — every route with its query parameters
Data model         — PostgreSQL tables, MongoDB documents, the ES index
```

**What's happening:**
It is the only file a new contributor is required to read. It links to the deeper documents rather
than duplicating them.

**Why it matters:**
Because the frontend needs no hostname and the schema is Java-owned, the setup instructions are
short and identical regardless of which backend implementation is running.

---


### Byte 17: `NOTES.md` — five learning milestones

**Builds on:** Byte 16

**In plain terms:**
Five short essays answering "why was it built this way?" Each defends one decision.

**The code:**
```
1. Split-brain          — ES down 5 min; orders still accepted; outbox rows retry;
                           15s drain replays the gap; reindex repairs the terminal case
2. Why not SQL search   — "Wir" → "Wireless Mouse" needs n-grams; ES computes aggs
                           document-scoped, independent of paging
3. Why MongoDB          — sparse attributes (dpi vs battery_hours vs ports) become one
                           document instead of EAV or nullable-column sprawl
4. Snapshotting         — an invoice must not re-read today's catalog
5. Write-path discipline— only one service writes orders, in one transaction;
                           dual-writing into Mongo would fork the truth
```

**What's happening:**
Each milestone describes a failure that the chosen design specifically prevents, not a feature it
enables. Milestone 1 is a worked example of the outbox under real stress.

**Why it matters:**
Rationale that only exists in someone's head is lost at the first rewrite. These five entries are
the answer to "why not just…?" for the five most tempting simplifications of this system.

---


### Byte 18: `docs/IMPLEMENTATION_TODO.md`

**Builds on:** Byte 17

**In plain terms:**
The project's single progress tracker, plus the locked architecture and vocabulary.

**The code:**
```
Architecture (locked):
  Vue 3 + Vite → Spring Boot → ┬─ MongoDB  (product catalog)
                               └─ PostgreSQL (orders / users / source of truth)
                                      │ COMMIT
                                      ▼
                                   Outbox ──► RabbitMQ ──► listener ──► Elasticsearch

Strategy: Strategy 1 only — asynchronous queued dual-write synchronization.
Strategy 2 (periodic polling) is NOT implemented and NOT configurable.

Vocabulary (use these words):
  MongoDB = Product Catalog
  PostgreSQL = source of truth
  RabbitMQ = message broker / transport
  Outbox = reliable event record
```

**What's happening:**
The file states the architecture as fixed, not as a proposal. It also fixes the *vocabulary*, and
explicitly forbids calling the strategy "simultaneous dual write".

**Why it matters:**
Naming is load-bearing in a distributed system. "Outbox", "canonical projection", and
"asynchronous queued dual-write" each name one specific thing. Using a different word for the same
concept is how two people end up debugging different problems.

---


### Byte 19: The remaining `docs/` files

**Builds on:** Byte 18

**In plain terms:**
A mix of dated historical records and the original design documents.

**The code:**
```
docs/RECOVERY_AUDIT.md                          audit of the starting state
docs/REPOSITORY_AUDIT.md                        file-by-file inventory
docs/superpowers/specs/2026-09-30-…-design.md   approved design
docs/superpowers/plans/2026-09-30-….md          approved plan
docs/writings/2026-09-30-writing-plans.md       the planning narrative
docs/Ecommerce_Strategy_1_….docx                the assignment brief
```

**What's happening:**
The `superpowers/` and `writings/` trees are dated snapshots of a design session. The two audit
files record what was found before any code was written.

**Why it matters:**
These are a historical record, not live documentation. They should not be rewritten to match the
current code — a dated audit that changes to match later work stops being evidence of anything.
Delete them only as a deliberate archival decision, never as cleanup.

---


## PART 7 — HOW EVERYTHING WORKS TOGETHER


### Byte 20: Bringing the whole stack up

**Builds on:** Bytes 1–15

**In plain terms:**
Two commands, in order, from a clean machine.

**The code:**
```bash
cp .env.example .env

docker compose up -d          # base + override, merged automatically
                              #   → postgres, mongo, elasticsearch, rabbitmq
                              #   → backend (waits for all four to be healthy)
                              #   → frontend

docker compose exec backend java -jar /app/app.jar \
  --app.seed.run=true --app.seed.reset=true \
  --spring.main.web-application-type=none
```

**What's happening:**
The override file is auto-merged, so the backend starts only after every store reports healthy. The
seed then writes the demo dataset and self-verifies.

**Why it matters:**
`--spring.main.web-application-type=none` runs the seed as a batch job with no web server, so it
exits when finished rather than becoming a second application instance competing for port 8000.

---


## PUTTING IT TOGETHER

The infrastructure exists to support one architectural promise: the Java backend is the only
application, and it reaches four stores through one well-defined contract. The three compose files
are that contract expressed three ways — full stack, stores-only for host development, and the
single-container variant where Supervisor plays the role Docker plays elsewhere. In every shape the
databases bind to localhost or to a private network, and only nginx is ever publicly reachable.

Two decisions in this layer look like quirks and are not. The PostgreSQL port is 55432 rather than
5432 because a native install already owns 5432 on this host, which is also why the `socat` test
proxy exists. And `.dockerignore` deliberately excludes almost everything *except*
`backend-java/target/`, because that directory holds the jar the image copies — a "tidier" ignore
file would produce a confusing `COPY` failure.

The documentation layer exists because this is a system where the reasoning is harder than the code.
`NOTES.md` defends five decisions against five tempting simplifications, and `IMPLEMENTATION_TODO.md`
fixes both the architecture and the vocabulary — including the insistence that the sync strategy is
called *asynchronous queued dual-write synchronization* and never "simultaneous dual write". The
dated files under `docs/` are a historical record and should be left alone: an audit that has been
edited to match later work is no longer evidence of anything.
