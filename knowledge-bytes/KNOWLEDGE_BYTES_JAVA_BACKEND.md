# Knowledge Bytes — E-Commerce Order Management (Java / Spring Boot)

> **How to read this document**
> Each "byte" is one small, self-contained idea. Bytes are ordered so that reading them
> in sequence builds the whole picture. Start at Byte 1 and go down.
> Every byte has: what it is in plain words, the code that does it, what is actually
> happening, and why it matters.
>
> **Reader assumed:** comfortable with basic programming, new to this specific project.
> **Project:** Java 17 + Spring Boot 3.5 backend, Vue 3 frontend.

---

## PART 1 — PROJECT OVERVIEW

### Byte 1: What this system actually is

**Builds on:** None — starting point

**In plain terms:**
An online store backend. A customer browses products, adds items to a cart, and places an
order. Staff use an admin screen to search and inspect orders. The interesting part is *not*
the shopping cart logic — it is that this system deliberately spreads its data across four
different databases and keeps them consistent without ever doing a slow two-phase commit.

**The code:**
```
frontend (Vue 3)  ──HTTP /api──>  backend-java (Spring Boot :8000)
                                        │
        ┌───────────────┬───────────────┼───────────────┐
        ▼               ▼               ▼               ▼
   PostgreSQL       MongoDB      Elasticsearch     RabbitMQ
   orders/users     catalog      order search      async queue
```

**What's happening:**
The frontend never talks to a database. It only talks to the Java backend over HTTP. The Java
backend is the single application service, and it is the only thing that knows how to reach
all four data stores.

**Why it matters:**
Everything else in this document exists to explain how one HTTP request can safely end up
touching three different databases without leaving them inconsistent.

---

### Byte 2: The technology stack

**Builds on:** Byte 1

**In plain terms:**
The backend is plain Java on Spring Boot. Data access is done with explicit SQL and explicit
Java code — no heavy object-relational mapper is hiding the queries.

**The code:**
```
Java 17
Spring Boot 3.5      — Web, JDBC, Validation, Scheduling
Spring AMQP          — RabbitMQ producer + consumer
PostgreSQL JDBC      — JdbcTemplate, hand-written SQL
Spring Data MongoDB  — product catalog
Elasticsearch REST   — low-level client, hand-written query JSON
Jackson              — JSON serialization
JUnit 5              — unit + integration tests
Vue 3 + Pinia        — frontend
Docker Compose       — all services
NGINX                — static serving + reverse proxy
```

**What's happening:**
`JdbcTemplate` means the SQL you see in the code is the SQL that runs. `MongoTemplate` means
Mongo documents are written as explicit field maps. The Elasticsearch client is low-level, so
the JSON body sent to Elasticsearch is exactly what the repository code builds — nothing is
generated behind your back.

**Why it matters:**
Because nothing is abstracted away, the code is longer but there are no surprises. When
something misbehaves you can read the exact query being sent.

---

## PART 2 — PROJECT ARCHITECTURE

### Byte 3: Four databases, four different jobs

**Builds on:** Byte 2

**In plain terms:**
Each database was chosen because it is genuinely good at one thing and bad at another. The
design keeps every store doing only what it is best at.

**The code:**
```
┌────────────────┬────────────────────────────────────┬──────────────────────┐
│ Store          │ Owns                               │ Why this store       │
├────────────────┼────────────────────────────────────┼──────────────────────┤
│ PostgreSQL     │ users, orders, order_items,        │ transactions, joins, │
│                │ sync_outbox                        │ durability           │
│ MongoDB        │ product catalog                    │ flexible documents   │
│ Elasticsearch  │ denormalized order projection      │ full-text + aggs     │
│ RabbitMQ       │ index sync work queue              │ decoupled retries    │
└────────────────┴────────────────────────────────────┴──────────────────────┘
```

**What's happening:**
PostgreSQL is the only *truth*. MongoDB holds things with a loose, evolving shape. Elasticsearch
holds a flattened copy optimized for reading, never for writing truth. RabbitMQ holds work that
has not been done yet.

**Why it matters:**
This is the single most important mental model in the project. If you remember "PostgreSQL is
truth, Elasticsearch is a cache that can always be rebuilt," most design questions answer
themselves.

---

### Byte 4: Repository layout

**Builds on:** Byte 3

**In plain terms:**
The Java code is layered so that a request flows downward through predictable folders.

**The code:**
```
backend-java/src/main/java/com/ecommerce/ordermanagement/
├── config/       DataSource, Rabbit, Elasticsearch, web/CORS setup
├── controller/   REST endpoints — the entire HTTP surface
├── model/        DTOs (request/response shapes) and domain records
├── repository/   All SQL / Mongo / Elasticsearch access lives here
├── service/      Business logic and transaction boundaries
├── seed/         Deterministic demo data generator + verifier
└── worker/       RabbitMQ listener + outbox drain scheduler

backend-java/src/main/resources/
├── application.yml               configuration
├── db/001_schema.sql             PostgreSQL schema (owned by Java)
└── search/orders_mapping.json    Elasticsearch mapping
```

**What's happening:**
The dependency rule is one-directional: `controller → service → repository`. Controllers never
write SQL. Repositories never contain business decisions. Services never build HTTP responses.

**Why it matters:**
When you need to change how data is stored you edit exactly one folder, and you can trust that
no HTTP behaviour changed as a side effect.


---
## PART 3 — DATABASE LOGIC

### Byte 5: The PostgreSQL schema

**Builds on:** Byte 3

**In plain terms:**
Four tables. Two hold real business data, one holds a price snapshot, one holds pending work.

**The code:**
```sql
CREATE TABLE users (
  id    BIGSERIAL PRIMARY KEY,
  email TEXT UNIQUE NOT NULL,
  name  TEXT NOT NULL
);

CREATE TABLE orders (
  id           BIGSERIAL PRIMARY KEY,
  user_id      BIGINT NOT NULL REFERENCES users(id),
  status       TEXT NOT NULL CHECK (status IN
                 ('PENDING','PAID','PROCESSING','SHIPPED','CANCELLED')),
  total_amount NUMERIC(12,2) NOT NULL,
  order_date   TIMESTAMPTZ NOT NULL DEFAULT now(),
  version      BIGINT NOT NULL DEFAULT 1,
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE order_items (
  id         BIGSERIAL PRIMARY KEY,
  order_id   BIGINT NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
  product_id TEXT NOT NULL,
  -- price AT PURCHASE TIME, never a live join to the catalog
  title      TEXT NOT NULL,
  unit_price NUMERIC(12,2) NOT NULL,
  quantity   INT NOT NULL CHECK (quantity > 0)
);

CREATE TABLE sync_outbox (
  id           BIGSERIAL PRIMARY KEY,
  order_id     BIGINT NOT NULL,
  version      BIGINT NOT NULL,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  processed_at TIMESTAMPTZ,
  attempts     INT NOT NULL DEFAULT 0,
  last_error   TEXT
);
```

**What's happening:**
Notice `order_items` stores `title` and `unit_price` as columns. It does **not** look them up
from the catalog at read time. `sync_outbox` has `processed_at` (null means still pending),
`attempts`, and `last_error` — the three columns that make reliable async processing possible.

**Why it matters:**
`NUMERIC(12,2)` is exact decimal arithmetic, not floating point. `0.1 + 0.2` is not `0.30` in
floating point, and order totals must be exact to the cent.

---

### Byte 6: The trigger that owns `version`

**Builds on:** Byte 5

**In plain terms:**
Every time an order row changes, the database itself bumps a counter and a timestamp. The
application never does this by hand.

**The code:**
```sql
CREATE OR REPLACE FUNCTION orders_touch() RETURNS trigger AS $$
BEGIN
  NEW.version    := OLD.version + 1;
  NEW.updated_at := now();
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER orders_touch
  BEFORE UPDATE ON orders
  FOR EACH ROW EXECUTE FUNCTION orders_touch();
```

**What's happening:**
`BEFORE UPDATE` means the trigger runs *before* the change is written, and it can modify the row
being written. Any UPDATE at all — from the API, from an admin tool, from a manual `psql`
session — increments `version`.

**Why it matters:**
`version` is the ordering token for Elasticsearch. Because the database owns it, it is impossible
for any code path to forget to increment it. A database-enforced counter cannot drift out of sync
with reality, which an application-managed counter can.


---
### Byte 7: Snapshot immutability

**Builds on:** Bytes 5 and 6

**In plain terms:**
When an order is placed, the product's name and price are copied into the order line. If someone
later edits that product in MongoDB, the old order still shows what the customer actually paid.

**The code:**
```
Product in Mongo (live catalog)        Order line in PostgreSQL (snapshot)
┌──────────────────────────┐          ┌──────────────────────────┐
│ title: "Basic Mouse"     │  ─────>  │ title: "Basic Mouse"     │
│ price: 12.50             │  copied  │ unit_price: 12.50        │
└──────────────────────────┘          └──────────────────────────┘
        │                                     │
   admin edits price                   never changes
        ↓                                     │
   price: 99.00                            still 12.50
```

**What's happening:**
The order creation code copies values out of the catalog and inserts them as ordinary columns.
From that moment on the order is self-describing. The search index is then built **only** from
PostgreSQL — never from the live catalog — so the search result also shows the historical price.

**Why it matters:**
This is a correctness requirement, not an optimization. A receipt that changes when a merchant
runs a sale is a trust problem. The seed data deliberately includes a "snapshot mismatch" fixture
— a product whose live price differs from the price on old orders — so this behaviour is
actively tested rather than assumed.

---

### Byte 8: Transaction boundaries in `OrderService`

**Builds on:** Byte 5

**In plain terms:**
Creating an order writes to three tables. Either all three writes land, or none do.

**The code:**
```java
@Transactional
public Order create(OrderRequest req) {
    Order order = ordersRepo.insert(...);      // 1. orders
    itemsRepo.insertAll(order.id(), ...);      // 2. order_items
    outboxRepo.enqueue(order.id(), v);         // 3. sync_outbox
    return order;
}
// RabbitMQ publish happens AFTER this method returns
```

**What's happening:**
`@Transactional` on the service method means Spring opens a database transaction at entry and
commits on successful exit. The RabbitMQ publish is deliberately placed *outside* that boundary.

**Why it matters:**
If the process crashes halfway through, the transaction rolls back and there is no half-created
order. The outbox row is written in the same transaction as the order, so an order can never exist
without a corresponding "please index me" event.

---

### Byte 9: MongoDB product catalog and `Decimal128`

**Builds on:** Byte 3

**In plain terms:**
Products live in MongoDB as documents. Prices use Mongo's exact-decimal type, not a double.

**The code:**
```java
Document doc = new Document("_id",      productId)
    .append("sku",         sku)
    .append("title",       title)
    .append("description", description)
    .append("price",       new Decimal128(price))   // not Double
    .append("category",    category)
    .append("tags",        tags)
    .append("active",      active)
    .append("updated_at",  Instant.now());

mongoTemplate.getCollection("products").insertOne(doc);
```

**What's happening:**
`Decimal128` is MongoDB's base-10 decimal type. Using a Java `double` would introduce binary
floating-point error into prices. The product `_id` is a caller-supplied string, which is why the
API returns it as `_id` — that is the product's identity for the rest of the system.

**Why it matters:**
Prices must round-trip exactly. `19.99` stored as a double and read back can be
`19.989999999999998`, which would show up in the UI and break totals.


---
## PART 4 — MESSAGE / QUEUE LOGIC

### Byte 10: The transactional outbox pattern

**Builds on:** Bytes 8 and 9

**In plain terms:**
Writing to PostgreSQL and writing to Elasticsearch are two separate systems. You cannot make them
atomic. So instead of trying, we record the *intent* to update Elasticsearch inside the same
database transaction, and deliver that intent afterwards.

**The code:**
```
        POST /api/orders
              │
              ▼
   ┌──────────────────────────────────────────────┐
   │  ONE PostgreSQL transaction                  │
   │   INSERT orders        ─┐                    │
   │   INSERT order_items   ─┤ all-or-nothing     │
   │   INSERT sync_outbox   ─┘ (pending)          │
   └──────────────────────────────────────────────┘
              │ COMMIT  ← the order is now durable
              ▼
     publish (order_id, outbox_id) to RabbitMQ
              │
              ▼
     @RabbitListener  ──►  write to Elasticsearch
              │
              ▼
     UPDATE sync_outbox SET processed_at = now()
```

**What's happening:**
The outbox table is a queue stored *inside the database*. Because it shares the transaction with
the order, the event cannot be lost: if the order exists, the event exists. Delivering the event
to RabbitMQ afterwards is safe, because if that step fails the event is still sitting in the outbox
table waiting to be retried.

**Why it matters:**
This is called the **transactional outbox** pattern — the standard answer to "how do I keep a
database and a search index in sync without distributed transactions." Without it you have two bad
options: write to Elasticsearch inside the DB transaction (slow, and Elasticsearch cannot roll back
a committed PostgreSQL transaction), or write to both and accept that one can fail, leaving them
permanently inconsistent.

---

### Byte 11: The producer

**Builds on:** Byte 10

**In plain terms:**
After the order commits, the backend tells RabbitMQ "order 42 needs indexing."

**The code:**
```java
// SyncDispatcher — runs only after the transaction commits
public void dispatch(long orderId, long outboxId) {
    rabbitTemplate.convertAndSend("es.sync",
        new OrderSyncMessage(orderId, outboxId));
}
```

**What's happening:**
The message body is a small pair of identifiers, not the order data. It deliberately does not
carry the order payload — the consumer re-reads from PostgreSQL so it always indexes committed,
current truth. The queue is named `es.sync`.

**Why it matters:**
Sending identifiers rather than payloads means a message can never contain stale data, and a retry
after a partial failure re-reads the freshest committed state.

---

### Byte 12: The consumer

**Builds on:** Byte 11

**In plain terms:**
A background method waits for messages and indexes each order into Elasticsearch.

**The code:**
```java
@RabbitListener(queues = "es.sync")
public void onMessage(OrderSyncMessage msg) {
    Optional<Order> order = ordersRepo.findById(msg.orderId());
    if (order.isEmpty()) { outboxRepo.markSuperseded(msg.outboxId()); return; }

    String doc = buildOrderDocument(order.get());   // from PostgreSQL only

    // external versioning: stale events rejected by Elasticsearch itself
    searchRepo.index(order.get().id(), doc, order.get().version());

    outboxRepo.markProcessed(msg.outboxId());       // idempotent
}
```

**What's happening:**
Two safety properties are at work. First, `markProcessed` is a compare-and-set update
(`WHERE id = ? AND processed_at IS NULL`), so if RabbitMQ delivers the same message twice the
second settlement is a harmless no-op — the consumer is idempotent. Second, the index call passes
`version_type=external`, so Elasticsearch rejects the write if a newer version already landed.

**Why it matters:**
RabbitMQ guarantees *at-least-once* delivery, not exactly-once. The only safe way to build on that
is to assume duplicates will happen and make them harmless. Here they are harmless twice over: the
outbox settlement is idempotent, and the external version check means an out-of-order old message
can never clobber a newer document.

---

### Byte 13: The drain scheduler

**Builds on:** Bytes 10–12

**In plain terms:**
If RabbitMQ was down when an order was created, its outbox row would sit there forever. A
scheduled job sweeps for those leftovers and republishes them.

**The code:**
```java
@Scheduled(fixedDelay = 15000)
public void drainOutbox() {
    List<OutboxRow> pending = outboxRepo.claimPending(MAX_ATTEMPTS, batchSize);
    for (OutboxRow row : pending) {
        dispatcher.dispatch(row.orderId(), row.id());
        outboxRepo.markProcessed(row.id());
    }
}
```

**What's happening:**
Rows are claimed with `FOR UPDATE SKIP LOCKED`, so if several backend instances run this job at
the same time, each claims *different* rows instead of fighting over the same one. A row is only
retried while `attempts < MAX_ATTEMPTS` (5). After that it is left alone for a human to look at
rather than being retried forever.

**Why it matters:**
This is what turns "at-least-once, best effort" into "eventually consistent, reliably." It is the
safety net that makes the whole design tolerable: no message can be permanently lost, and a poison
message cannot spin forever.


---
## PART 5 — SEARCH LOGIC

### Byte 14: The Elasticsearch document projection

**Builds on:** Byte 7

**In plain terms:**
Each order is flattened into one search document containing customer details and item snapshots.

**The code:**
```java
String buildOrderDocument(Order o) {
  return """
    {
      "order_id":     %d,
      "customer":     { "name": %s, "email": %s },
      "status":       %s,
      "order_date":   %s,
      "total_amount": "%s",
      "items":        [ { "product_id": %s, "title": %s,
                           "unit_price": "%s", "quantity": %d } ]
    }""".formatted(...);
}
```

**What's happening:**
Note `"total_amount": "29.99"` — a **quoted string**, not a number. The money codec stringifies
decimals on the way in and parses them back out of `_source` on the way back. This is read
exclusively from PostgreSQL; MongoDB is never consulted here.

**Why it matters:**
Because this function is a pure function of committed PostgreSQL rows, the search index always
reflects what the customer was actually charged — the snapshot — not the merchant's current
catalog price. It is also why the search path has no MongoDB latency in it at all.

---

### Byte 15: The index mapping

**Builds on:** Byte 14

**In plain terms:**
The mapping declares how each field should be indexed, which determines whether Elasticsearch can
search or aggregate on it correctly.

**The code:**
```json
{
  "mappings": {
    "properties": {
      "status":       { "type": "keyword" },
      "total_amount": { "type": "scaled_float", "scaling_factor": 100 },
      "customer": {
        "properties": {
          "name": { "type": "text",
                    "fields": { "prefix": { "type": "text",
                                            "analyzer": "autocomplete" } } }
        }
      },
      "items": {
        "properties": {
          "product_id": { "type": "keyword" },
          "title":      { "type": "text" },
          "unit_price": { "type": "scaled_float", "scaling_factor": 100 }
        }
      }
    }
  }
}
```

**What's happening:**
`keyword` fields are stored whole and are exact-match only — correct for `status` and
`product_id`. `scaled_float` is an integer scaled by a factor, so money stays compact and
aggregatable while remaining exact. The `autocomplete` analyzer on the `prefix` sub-fields indexes
every prefix of the value, which is what makes as-you-type search work.

**Why it matters:**
If a mapping is wrong, Elasticsearch either refuses the document or silently produces garbage
results. Text fields cannot be aggregated at all; if `total_amount` were mapped as `text`, every
revenue aggregation would fail with a fielddata error and the admin screen would break. The index
is therefore created with this mapping explicitly at startup, never left to auto-detection.

---

### Byte 16: The search query

**Builds on:** Byte 15

**In plain terms:**
One request runs a full-text query with optional filters, then computes aggregates over the
*entire* matching set — not just the current page.

**The code:**
```java
String body = """
{
  "query": { "bool": {
      "must":   [ { "multi_match": {
          "query": %s,
          "fields": ["customer.name^2","items.title.prefix",
                     "items.title","customer.email"] } } ],
      "filter": [ %s ]                 // status / date range / price range
  }},
  "from": %d, "size": %d,             // pagination
  "aggs": {
    "revenue":       { "sum":  { "field": "total_amount" } },
    "status_facets": { "terms": { "field": "status" } }
  }
}""".formatted(q, filters, (page-1)*size, size);
```

**What's happening:**
Two ideas are combined here. Pagination uses `from`/`size`, so only the requested slice of hits
comes back. Aggregations are **siblings** of `from`/`size` in the request body, so they are
computed over all matching documents before pagination is applied.

**Why it matters:**
This is the answer to a classic bug. If aggregates were computed only over the returned page,
"revenue: 4932.96" would change as you paged through results — and a total that changes when you
click next is not a total. Because the aggregations are separate from the page slice, `revenue` and
`status_facets` always describe the whole result set.

---

### Byte 17: Reindex

**Builds on:** Bytes 13–16

**In plain terms:**
A single endpoint rebuilds the entire search index from PostgreSQL.

**The code:**
```
POST /api/sync/reindex
        │
        ▼
  for each order in PostgreSQL:      # full scan, no message queue involved
      buildOrderDocument(order)
      ES.index(order.id, doc, order.version)
        │
        ▼
  {"indexed": 40}
```

**What's happening:**
It bypasses the queue and the outbox entirely, reading straight from the source of truth. It is
used to repair a damaged or mis-mapped index, and after a bulk data change.

**Why it matters:**
Reindex is the escape hatch that makes the whole architecture safe. Because Elasticsearch holds
no unique data — every document is derivable from PostgreSQL — the index can always be thrown away
and rebuilt. It is also the repair path if Elasticsearch was ever unreachable at startup.


---
## PART 6 — API LOGIC

### Byte 18: The endpoint surface

**Builds on:** Bytes 1, 4

**In plain terms:**
Thirteen routes, all under `/api`. Controllers are thin — they validate, delegate, and return.

**The code:**
```
GET    /api/health
GET    /api/users

GET    /api/products              ? category, search, include_inactive, skip, limit
GET    /api/products/{id}
POST   /api/products
PUT    /api/products/{id}

POST   /api/orders                              → 201
GET    /api/orders/{id}
PATCH  /api/orders/{id}/status

POST   /api/search/orders
GET    /api/sync/status/{order_id}
POST   /api/sync/drain-outbox
POST   /api/sync/reindex
```

**What's happening:**
A controller method receives a typed request object, calls one service method, and returns a typed
response. Note the product update verb is `PUT` (full replace), not `PATCH`.

**Why it matters:**
Keeping controllers thin means the HTTP contract and the business rules can change independently.
There is exactly one place where each business decision is made, which is why the frontend can be
rewritten without touching a single query.

---

### Byte 19: The response contract

**Builds on:** Byte 18

**In plain terms:**
The exact JSON shape the frontend depends on. Getting a money field from a number to a string
breaks the UI, so these details are load-bearing.

**The code:**
```json
// GET /api/orders/1
{
  "order_id": 1,
  "status": "SHIPPED",
  "sync_status": "IN_SYNC",          // QUEUED | IN_SYNC | FAILED
  "total_amount": "29.99",           // STRING, two decimals
  "version": 1,
  "items": [ { "product_id": "sku-1",
               "title": "Wireless Mouse",
               "unit_price": "29.99",
               "quantity": 2 } ]
}
```
```java
// money never leaves as a double
public String money(BigDecimal v) {
    return v.setScale(2, RoundingMode.HALF_UP).toPlainString();
}
```

**What's happening:**
Money is always a two-decimal **string**. A JSON number would arrive in JavaScript as an IEEE-754
double and lose precision. `sync_status` is exposed per order so the frontend can show whether the
search index has caught up yet.

**Why it matters:**
`sync_status` is what makes the eventual consistency *visible*. Without it a user would place an
order, search for it immediately, find nothing, and conclude the system is broken. With it, the UI
can honestly show "queued for indexing."

---

### Byte 20: Error handling and validation

**Builds on:** Byte 19

**In plain terms:**
All errors come back in one shape, and unknown request fields are rejected rather than ignored.

**The code:**
```java
// single handler for the whole app
@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(ApiException.class)
  ResponseEntity<?> notFound(ApiException e) {
      return ResponseEntity.status(e.status())
          .body(Map.of("detail", e.getMessage()));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<?> unprocessable(...) {
      // 422 — unknown body keys are refused
  }
}
```

**What's happening:**
A not-found produces `404 {"detail": "order 999999 not found"}`. A duplicate SKU produces `409`.
An unparseable or over-specified request body produces `422`, because the DTOs are configured to
reject unknown properties rather than silently dropping them.

**Why it matters:**
Silently ignoring an unknown field is how typos become phantom bugs — you send `total_amunt`, the
server ignores it, and you spend an afternoon wondering why the total is zero. Refusing the
request turns that into an immediate, obvious error.


---
## PART 7 — FRONTEND LOGIC

### Byte 21: The Vue frontend talks to one origin

**Builds on:** Byte 18

**In plain terms:**
The frontend never hardcodes a backend address. It calls relative paths and a proxy does the rest.

**The code:**
```js
// frontend/src/api/orders.js
export const fetchOrders = (id) =>
  fetch(`/api/orders/${id}`).then(r => r.json());

// frontend/vite.config.js — dev only
server: { proxy: { '/api': 'http://127.0.0.1:8000' } }
```
```
# production
frontend/nginx.conf
  location /api/ { proxy_pass http://backend:8000; }
```

**What's happening:**
In development, Vite proxies `/api` to the backend on port 8000. In production, NGINX does the same
thing. The frontend code is identical in both cases, which is why it never mentions a hostname.

**Why it matters:**
This is what allowed the backend to be rewritten without changing a single line of frontend code.
The contract is the HTTP contract, nothing else.

---

### Byte 22: Frontend state

**Builds on:** Byte 21

**In plain terms:**
Pinia stores hold the catalog, the cart, the search results, and the current session.

**The code:**
```
stores/catalog.js   product list + filters        → GET  /api/products
stores/cart.js      lines, totals, checkout       → POST /api/orders
stores/orders.js    order list + detail + status  → GET  /api/orders/{id}
stores/search.js    hits, facets, revenue, paging  → POST /api/search/orders
stores/session.js   current user
```

**What's happening:**
Each store maps to one API surface. `search.js` holds the whole response — `hits`, `status_facets`
and `revenue` together — because those three come from a single request and must stay consistent
with each other on screen.

**Why it matters:**
Keeping the aggregate with its page of results in one store prevents the classic mismatch where
the facet counts describe all 40 results but the list shows 10, and the user cannot tell why.

---

## PART 8 — HOW EVERYTHING WORKS TOGETHER

### Byte 23: Placing an order, end to end

**Builds on:** Bytes 1–22

**In plain terms:**
One click in the browser touches four systems without any of them being able to fail silently.

**The code:**
```
 1. Browser  POST /api/orders
 2. Service  @Transactional {
 3.             INSERT orders
 4.             INSERT order_items     ← prices copied from Mongo into the order
 5.             INSERT sync_outbox     ← "index this order"
 6.           } COMMIT                       ← single atomic transaction
 7. Dispatch publish (order_id, outbox_id) → RabbitMQ   ← only after COMMIT
 8. Listener  read order from PostgreSQL
 9.           build document (snapshot values)
10.           ES.index(id, doc, version=external)
11.           UPDATE outbox SET processed_at = now()
12. Response  201 { "sync_status": "QUEUED" }
13. Worker    (ms later) order is searchable; sync_status → IN_SYNC
```

**What's happening:**
Steps 3–6 are atomic. Step 7 happens only after the commit succeeds. Steps 8–11 run asynchronously
and may take a moment, which is why the API answers `QUEUED` rather than pretending the search index
is already current.

**Why it matters:**
This is the whole design in one picture. The user gets an immediate, durable confirmation, the
search index catches up shortly after, and there is no window in which the order exists but can
never be indexed.

---

### Byte 24: The sync status endpoint

**Builds on:** Byte 23

**In plain terms:**
A small endpoint that compares the database's version with the index's version for one order.

**The code:**
```
GET /api/sync/status/1
{
  "order_id": 1,
  "pg_version": 1,      // from PostgreSQL
  "es_version": 1,      // from Elasticsearch
  "status": "IN_SYNC"   // QUEUED | IN_SYNC | FAILED
}
```

**What's happening:**
It is a diagnostic and a UI signal at once. If the two versions match, the index reflects the
database. If the PostgreSQL version is higher, indexing is still in flight.

**Why it matters:**
Every eventual-consistency system needs a way to answer "is it caught up yet?" Without it you are
debugging blind. It is also the single most useful endpoint when diagnosing a sync problem.


---
## PART 9 — THE SEED

### Byte 25: Deterministic seed data

**Builds on:** Bytes 5, 9, 23

**In plain terms:**
A generator produces a fixed demo dataset and then checks its own work.

**The code:**
```
SeedPlan    — pure and deterministic. Builds the plan from a fixed seed.
              Produces 8 users, 25 products, 40 orders, ~85 order items.
SeedService — writes the plan into Mongo + PostgreSQL, indexes into ES,
              then verifies every invariant and prints PASS/FAIL per check.
```

**What's happening:**
The plan is separated from the writing so the numbers can be tested without a database. The plan
uses a seeded RNG, so the same input always yields the same orders. Among the invariants checked:
item count ≥ 80, average items per order ≥ 2, Elasticsearch fully caught up, and the snapshot
mismatch fixture still present.

**Why it matters:**
The verifier is the important part. A seed that quietly produces too little data is worse than no
seed, because everything built on top of it looks fine until you count. The invariant is the
contract, and the generator must satisfy it or the run fails loudly.

---

### Byte 26: The snapshot mismatch fixture

**Builds on:** Bytes 7 and 25

**In plain terms:**
The seed deliberately creates one product whose live price differs from the price recorded on
historical orders.

**The code:**
```
live catalog (Mongo)            historical order line (PostgreSQL / ES)
Wireless Mouse Pro              Wireless Mouse Pro
  price: 49.99        ←≠→         unit_price: 29.99   (what was charged)
```

**What's happening:**
After seeding, the product's price is changed in MongoDB only. The seed then asserts that the order
and the search index still report the old price.

**Why it matters:**
This is a self-checking test disguised as data. Anyone who later "simplifies" the order read path to
join against the live catalog will immediately see the invariant fail. It converts a subtle
architectural rule into something that breaks loudly.


---
## PART 10 — IMPORTANT BUSINESS RULES AND GOTCHAS

### Byte 27: The rules you must not break

**Builds on:** All previous bytes

**In plain terms:**
A short list of invariants that future changes are expected to respect.

**The code:**
```
 1. PostgreSQL is the only source of truth.
 2. Order line prices are snapshots — never read live from the catalog.
 3. Elasticsearch is derived data — it may be deleted and rebuilt at will.
 4. Never write to Elasticsearch inside a database transaction.
 5. The outbox row is written in the same transaction as the order.
 6. Money crosses the wire as a two-decimal STRING, everywhere.
 7. The index mapping is created explicitly — never rely on auto-detection.
 8. Aggregations must be siblings of from/size, never inside the page slice.
 9. Unknown request fields are rejected (422), not ignored.
10. Consumers must stay idempotent; duplicates are expected, not exceptional.
```

**What's happening:**
Each of these corresponds to a specific byte above. Rules 1–5 protect durability and consistency,
6–8 protect the wire and search contracts, and 9–10 protect against silent failure.

**Why it matters:**
This is the checklist to review against before changing any code in this repository. Every one of
these rules exists because the alternative produced a real bug.

---

### Byte 28: Gotchas that will bite you

**Builds on:** Byte 27

**In plain terms:**
Specific traps in this stack that are not obvious from the code alone.

**The code:**
```
● Money in Elasticsearch _source is a JSON STRING. Use node.asText(),
  never String.valueOf(node) — the latter returns the quoted form and
  BigDecimal throws NumberFormatException.

● HEAD requests that return 404 do NOT throw in the low-level REST client.
  A guard written as "if the request didn't throw, the index exists" is
  always true, so the mapping never gets created. Read the status code.

● Host port 5432 may be shadowed by a native PostgreSQL install.
  Use the compose-published port when running integration tests on the host.

● Redeclaring a dependency at a narrower Maven scope silently REMOVES it
  from the wider one. This strips the JDBC driver out of the fat jar and
  the app fails at runtime with "Failed to load driver class".

● Elasticsearch ignores a mapping you change on an existing index.
  Delete and reindex, or nothing will change.

● A seed invariant that fails is telling you the truth. Never lower the
  threshold to make it pass.
```

**What's happening:**
Each of these is a real failure that occurred during development and cost time. They are recorded
here because the code cannot warn you about them.

**Why it matters:**
The money and mapping traps produce errors that look like data corruption rather than bugs. The
scope trap produces a jar that builds cleanly and then fails at startup. Knowing these in advance
saves a lot of time.


---
## PUTTING IT TOGETHER

This system keeps four databases consistent by giving each one a single, narrow job: PostgreSQL
owns the truth, MongoDB owns the live catalog, Elasticsearch owns fast reading, and RabbitMQ owns
work that has not happened yet. The glue between them is the transactional outbox — the order and
its "please index me" event are written in one atomic commit, so an event can never be lost, and a
15-second drain job guarantees anything the broker missed is eventually retried. The consumer is
written to tolerate duplicates, because at-least-once delivery is the only guarantee a message
broker actually offers.

What ties the design together is the snapshot rule. When an order is placed, the product title and
price are copied out of MongoDB and frozen into PostgreSQL, and the search index is built purely
from those frozen values. That single decision is why a historical receipt stays correct after the
merchant changes a price, and why reindexing can always throw Elasticsearch away and rebuild it
from scratch.

Read the bytes in order and the project stops being four databases and a message queue and starts
being one system with one idea: commit once to the store that owns the truth, then propagate that
commit outward asynchronously, and always leave yourself a way to check whether the propagation has
finished.
