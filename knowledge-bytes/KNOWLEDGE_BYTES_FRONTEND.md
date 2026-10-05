# Knowledge Bytes — Frontend (Vue 3)

> **How to read this document**
> Each byte is one small, self-contained idea. Read them in order.
>
> **Reader assumed:** comfortable with basic programming, new to Vue or this project.
> **Scope:** everything under `frontend/`. The Java backend has its own knowledge-bytes file.

---

## PART 1 — TECH STACK AND OVERVIEW

### Byte 1: What the frontend is

**Builds on:** None — starting point

**In plain terms:**
A single-page web app with two faces. A shopper sees a storefront and a checkout. An admin sees
order search and a catalog editor. Same app, different navigation based on who is signed in.

**The code:**
```
frontend/
├── index.html            the HTML shell — one empty <div id="app">
├── package.json          dependencies and scripts
├── vite.config.js        dev server + /api proxy
├── Dockerfile            production image (nginx + prebuilt dist)
├── nginx.conf            production reverse proxy
└── src/
    ├── main.js           the 1-line entry point
    ├── App.vue           the shell: nav bar + <RouterView>
    ├── router.js         routes and the auth guard
    ├── api/              one file per backend resource
    ├── stores/           Pinia state
    ├── views/            one file per screen
    ├── components/       reusable pieces
    ├── fx/               animation helpers
    └── utils/money.js    currency formatting
```

**What's happening:**
Vite builds `src/` into a `dist/` folder of hashed static files. That folder is served by nginx.
The JavaScript is entirely client-side — there is no server-side rendering.

**Why it matters:**
The frontend never talks to a database and never knows a hostname. It only knows `/api` paths.
That is the single reason the backend could be rewritten without touching a line of this code.

---

### Byte 2: Dependencies and build scripts

**Builds on:** Byte 1

**In plain terms:**
Three runtime dependencies, two build dependencies. That is the entire toolchain.

**The code:**
```json
{
  "scripts": {
    "dev": "vite --port 5173",
    "build": "vite build",
    "preview": "vite preview --port 5173"
  },
  "dependencies": {
    "pinia": "^2.1.7",
    "vue": "^3.4.0",
    "vue-router": "^4.3.0"
  },
  "devDependencies": {
    "@vitejs/plugin-vue": "^5.0.0",
    "vite": "^5.4.0"
  }
}
```

**What's happening:**
Vue is the component framework. Pinia is the state library. Vue Router handles URLs. Vite is the
dev server and bundler. There is no UI framework, no HTTP client library, no CSS framework — the
styles are hand-written.

**Why it matters:**
A dependency list this short is a maintenance liability you can actually carry. Every `fetch` call
in the app is visible in `src/api/`, and every style is in the repo, so there is no mystery layer.

---

## PART 2 — THE ENTRY POINT AND ROUTING

### Byte 3: The three-line entry point

**Builds on:** Byte 2

**In plain terms:**
`main.js` creates the Vue app and installs two plugins.

**The code:**
```js
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router.js'

createApp(App).use(createPinia()).use(router).mount('#app')
```

**What's happening:**
`createApp(App)` builds the app around the root component. `.use(createPinia())` installs the
state library, `.use(router)` installs the URL router, and `.mount('#app')` attaches it to the
empty div from `index.html`.

**Why it matters:**
`index.html` contains no markup at all. Everything on screen is rendered by JavaScript after load,
which is why there is only one shell file and no server-rendered templates anywhere.


---
### Byte 4: Routes, and the role attached to each

**Builds on:** Byte 3

**In plain terms:**
Seven URLs. Each one declares whether it is for shoppers, for admins, or for everyone.

**The code:**
```js
const routes = [
  { path: '/login',  component: () => import('./views/LoginView.vue') },
  { path: '/',       component: () => import('./views/StorefrontView.vue'), meta: { role: 'user' } },
  { path: '/checkout',component: () => import('./views/CheckoutView.vue'),    meta: { role: 'user' } },
  { path: '/admin',  redirect: '/admin/orders',                             meta: { role: 'admin' } },
  { path: '/admin/orders',     component: () => import('./views/AdminSearchView.vue'),  meta: { role: 'admin' } },
  { path: '/admin/orders/:id', component: () => import('./views/OrderDetailView.vue'),   meta: { role: 'admin' } },
  { path: '/admin/catalog',    component: () => import('./views/CatalogAdminView.vue'), meta: { role: 'admin' } }
]
```

**What's happening:**
The `() => import(...)` form is a *lazy import* — each view's JavaScript is fetched only when
someone first navigates to it. The `meta.role` field is metadata for the guard in the next byte.

**Why it matters:**
Lazy imports keep the initial bundle small. The storefront loads without downloading the admin
screens. The role marker is what makes one app safely serve two very different audiences.

---

### Byte 5: The navigation guard

**Builds on:** Byte 4

**In plain terms:**
Before any page renders, the router checks whether the person is allowed to see it.

**The code:**
```js
router.beforeEach((to) => {
  const session = useSession()
  if (to.path === '/login' && session.isAuthenticated)
    return session.isAdmin ? '/admin' : '/'

  if (to.meta.role && !session.isAuthenticated)
    return { path: '/login', query: { redirect: to.fullPath } }

  if (to.meta.role === 'admin' && !session.isAdmin)
    return '/'
})
```

**What's happening:**
`beforeEach` runs before every navigation and can cancel it by returning a different route. Three
rules apply: an authenticated user cannot stay on the login page, an unauthenticated visitor is
sent to login with the intended URL preserved in `?redirect=`, and a non-admin cannot open an
admin page.

**Why it matters:**
This is **client-side routing only**. It controls what the UI shows; it is not a security control,
because the browser can be asked for any URL directly. It exists so users do not land on pages
that would render empty or crash.

---

## PART 3 — THE APPLICATION SHELL

### Byte 6: The shell swaps its own navigation

**Builds on:** Byte 5

**In plain terms:**
`App.vue` is one header plus one content slot. The header changes depending on the signed-in role.

**The code:**
```vue
<template>
  <div class="app" :class="{ 'admin-app': session.isAdmin }">
    <RouterView v-if="$route.path === '/login'" />
    <template v-else>
      <header class="nav">
        <RouterLink :to="session.isAdmin ? '/admin' : '/'">…</RouterLink>
        <nav class="links">
          <template v-if="session.isAdmin">
            <RouterLink to="/admin/orders">Order search</RouterLink>
            <RouterLink to="/admin/catalog">Catalog</RouterLink>
          </template>
          <template v-else>
            <RouterLink to="/">Shop</RouterLink>
            <RouterLink to="/checkout">Checkout</RouterLink>
          </template>
        </nav>
        <!-- cart pill + sign-out -->
      </header>
      <main class="body"><RouterView /></main>
      <FxLayer />
    </template>
  </div>
</template>
```

**What's happening:**
The login page renders *without* the header. Every other page renders inside it. The `<RouterView>`
component is a placeholder that Vue Router fills with whichever view component matched the current
URL.

**Why it matters:**
Because the shell is role-aware, a shopper never sees an admin link — there is no menu to hide,
because the admin navigation is never rendered for them at all.

---

### Byte 7: Global styles live in the shell

**Builds on:** Byte 6

**In plain terms:**
The design system — colours, buttons, tables, cards — is one `<style>` block in `App.vue`.

**The code:**
```css
:root {
  --accent: #d16f52;  --bg: #f7f5f0;  --card: #ffffff;
  --ink: #20312d;     --radius: 16px; --green: #15803d;
}
.btn          { /* shared pill button */ }
.card         { /* white rounded panel */ }
.state-pill   { /* LOADING / ERROR / SUCCESS chip */ }
.grid .card   { animation: fx-rise 420ms ... backwards; }
.grid .card:nth-child(2) { animation-delay: 50ms; }   /* stagger */
```

**What's happening:**
CSS custom properties declared on `:root` are readable by every component, so the palette is
defined exactly once. The `.grid .card` rules create a staggered entrance by giving each card a
slightly different `animation-delay`.

**Why it matters:**
Having one stylesheet means a visual change is a one-file change. There is no CSS-in-JS, no utility
framework, and no per-component style duplication — except where a component genuinely owns its
own styles.


---
## PART 4 — STATE (PINIA STORES)

### Byte 8: What a Pinia store is

**Builds on:** Byte 7

**In plain terms:**
A store is a small named object holding shared state plus the functions that change it.

**The code:**
```js
export const useCatalog = defineStore('catalog', {
  state: () => ({ items: [], state: 'idle', error: '' }),
  actions: {
    async load(params = {}) {
      this.state = 'loading'
      try {
        this.items = await listProducts(params)
        this.state = this.items.length ? 'success' : 'empty'
      } catch (e) {
        this.state = 'error'
        this.error = e.message
      }
    }
  }
})
```

**What's happening:**
`state` is the data, `actions` are the functions, and `useCatalog()` returns the live store.
Every async action moves `state` through `'loading'` → `'success'` / `'empty'` / `'error'`, which
is exactly the set of conditions the views render.

**Why it matters:**
The four-state pattern means views never invent their own loading logic. A view only asks "what is
the state?" and renders the matching branch, so every screen handles failure the same way.

---

### Byte 9: The session store

**Builds on:** Byte 8

**In plain terms:**
Holds who is signed in, survives a page refresh, and exposes two boolean questions.

**The code:**
```js
state: () => ({
  userId: Number(localStorage.getItem('nest-user-id')) || null,
  userName: localStorage.getItem('nest-user-name') || '',
  role: localStorage.getItem('nest-role') || ''
}),
getters: {
  isAuthenticated: (state) => Boolean(state.role),
  isAdmin: (state) => state.role === 'admin'
}
```

**What's happening:**
Initial state is read straight from `localStorage`, so a refresh does not sign you out. The role
is a plain string in the browser — there is no token, because there is no real authentication.

**Why it matters:**
Because `isAuthenticated` and `isAdmin` are derived getters rather than separate stored fields, a
role can never be `admin` while `isAuthenticated` is false. The two questions can never disagree
with each other.

---

### Byte 10: The cart store merges duplicate lines

**Builds on:** Byte 8

**In plain terms:**
Adding the same product twice increases its quantity instead of creating a second line.

**The code:**
```js
add(productId, qty = 1, title = '', category = '') {
  const line = this.lines.find((l) => l.product_id === productId)
  if (line) line.quantity += qty
  else this.lines.push({ product_id: productId, quantity: qty, title, category })
}
```

**What's happening:**
The store searches for an existing line with the same `product_id` before pushing a new one. It
keeps `title` and `category` alongside the id purely so the checkout page can render something
readable without a second round trip.

**Why it matters:**
The cart holds only ids and quantities. Prices are resolved server-side when the order is created,
so a stale price shown in the browser can never become the price actually charged.

---

### Byte 11: The search store owns all filter state

**Builds on:** Byte 8

**In plain terms:**
One store holds every filter field, the current page, and the last result — and it builds the
request payload from whichever fields are filled in.

**The code:**
```js
async run(resetPage = false) {
  if (resetPage) this.page = 1
  const payload = { q: this.q || null, page: this.page, size: this.size }
  if (this.statuses.length)  payload.statuses   = this.statuses
  if (this.date_from)        payload.date_from = new Date(this.date_from).toISOString()
  if (this.price_min !== '') payload.price_min  = this.price_min
  this.result = await searchOrders(payload)
  this.state = this.result.total ? 'success' : 'empty'
}
```

**What's happening:**
Empty filters are omitted from the payload rather than sent as blanks. `resetPage` exists because
changing a filter must jump back to page 1, while paging must not. Date strings are converted to
ISO format before sending.

**Why it matters:**
The `resetPage` flag is what prevents the classic bug: you are on page 4, you add a status filter,
and the app stays on page 4 of the *new* smaller result set and shows nothing.

---

### Byte 12: The orders store returns the created order

**Builds on:** Byte 8

**In plain terms:**
Placing an order and loading an order share one store, but only `place()` hands the result back.

**The code:**
```js
async place(payload) {
  this.state = 'loading'
  try {
    this.lastPlaced = await placeOrder(payload)   // ← full response
    this.state = 'success'
    return this.lastPlaced
  } catch (e) { this.state = 'error'; this.error = e.message; throw e }
}
```

**What's happening:**
`place()` both stores the result and returns it, because the checkout view needs `order_id` and
`sync_status` immediately. `load(id)` only writes to `this.detail`, because a route change needs no
return value.

**Why it matters:**
Note the `throw e` in the catch. The error is recorded *and* re-thrown, so the view can decide to
show its own error message while the store keeps the state consistent.


---
## PART 5 — API LOGIC

### Byte 13: One API file per backend resource

**Builds on:** Byte 8

**In plain terms:**
`src/api/` is the only place in the app that calls the backend. Views and stores never call
`fetch` directly.

**The code:**
```
src/api/products.js   listProducts, getProduct, createProduct, updateProduct
src/api/orders.js     placeOrder, getOrder, updateStatus
src/api/search.js     searchOrders
src/api/users.js      listUsers
```

**What's happening:**
Each function wraps one endpoint, adds the headers, checks `response.ok`, and returns parsed JSON.
Nothing else in the app knows a URL exists.

**Why it matters:**
Because the URLs live in exactly four files, a backend route change is a four-file change. Views
stay declarative and stores stay testable.

---

### Byte 14: Money is formatted without arithmetic

**Builds on:** Byte 13

**In plain terms:**
A one-line helper turns the money string from the backend into a display string.

**The code:**
```js
// Money arrives as a JSON string ("50.16"). Format without float math.
export function formatMoney(str) {
  return new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' }).format(str)
}
```

**What's happening:**
`Intl.NumberFormat` adds the currency symbol and thousands separators. The comment is the important
part: the function never does arithmetic, so no rounding error can be introduced at display time.

**Why it matters:**
The backend already decided the price. The frontend's only job is presentation. If the frontend
also computed totals, two different numbers would be possible for the same order.

---

### Byte 15: The error-shape problem, and its fix

**Builds on:** Byte 13

**In plain terms:**
The backend returns `detail` as a plain string for simple errors but as an **array of objects**
for validation failures. Naively stringifying that array produces `[object Object]`.

**The code:**
```js
async function detailOf(r, fallback) {
  try {
    const d = (await r.json())?.detail
    if (typeof d === 'string') return d
    if (Array.isArray(d)) return d.map((x) => x?.msg || JSON.stringify(x)).join('; ')
    if (d) return JSON.stringify(d)
  } catch {
    /* non-JSON body (e.g. nginx 502 html) — fall through to fallback */
  }
  return fallback
}
```

**What's happening:**
Three branches handle the three shapes `detail` can take: a string, an array of `{msg}` objects,
or something else. The `catch` covers the case where the response body is not JSON at all — an
nginx 502 page, for example.

**Why it matters:**
This helper is the difference between an admin seeing *"unknown field `pric` was unexpected"* and
seeing *"[object Object]"*. It is also the reason `products.js` has a comment explaining a
behaviour that would otherwise look like defensive noise.


---
## PART 6 — SCREENS (VIEWS)

### Byte 16: Storefront — browse and add to cart

**Builds on:** Bytes 8, 11

**In plain terms:**
The shopper's home page. Category buttons and a search box filter the catalog; each product card
can be added to the cart with an animation.

**The code:**
```js
async function reload() {
  await catalog.load({ category: category.value, search: query.value || undefined })
}
onMounted(reload)

async function add(product, event) {
  cart.add(product._id || product.id, 1, product.title, product.category)
  const btn = event?.currentTarget || event?.target
  await Promise.allSettled([flyToCart(event), burst(btn)])
  toast(`Added ${product.title}`)
}
```

**What's happening:**
Filtering happens **server-side** — the view sends `category` and `search` to the backend rather
than filtering an in-memory array. The animations are fired but deliberately not awaited
(`Promise.allSettled`), so a missing animation never blocks the cart update.

**Why it matters:**
`p._id || p.id` accepts either field name, which is the frontend tolerating the backend's `_id`
choice. The `allSettled` is deliberate: a cosmetic effect must never be able to fail a purchase.

---

### Byte 17: Checkout — the one write in the shopper flow

**Builds on:** Byte 12

**In plain terms:**
Sends the cart to the backend and shows the confirmation, including the sync status.

**The code:**
```js
placed.value = await orders.place({
  user_id: session.userId,
  items: cart.lines.map((l) => ({ product_id: l.product_id, quantity: l.quantity }))
})
cart.clear()
state.value = 'success'
```
```vue
<div v-if="state === 'success' && placed" class="state-pill success">
  SUCCESS — order {{ placed.order_id }} ({{ placed.sync_status }})
</div>
```

**What's happening:**
The payload contains **only ids and quantities** — no prices, no titles. The backend reads the
catalog, snapshots the price, and computes the total. The cart is cleared only after the request
succeeds.

**Why it matters:**
Sending prices from the browser would let a client dictate what it pays. The success banner
displays `sync_status`, which is how the user learns the order exists but is still queued for
search indexing.

---

### Byte 18: Login — two roles, no real authentication

**Builds on:** Byte 9

**In plain terms:**
A tabbed sign-in where shoppers pick an account and admins type fixed demo credentials.

**The code:**
```js
function submit() {
  if (mode.value === 'admin') {
    if (email.value !== 'admin@nest.local' || password.value !== 'admin123') {
      error.value = 'Use the demo admin credentials shown below.'
      return
    }
    session.loginAsAdmin()
    router.push('/admin')
    return
  }
  const user = users.value.find((item) => String(item.id) === selectedUser.value) || users.value[0]
  session.loginAsUser(user)
  router.push(route.query.redirect || '/')
}
```

**What's happening:**
The admin email and password are hardcoded in the source and compared in the browser. Shopper
identity comes from `GET /api/users`. The `?redirect=` query parameter from the guard is honoured
so a user is returned to where they were headed.

**Why it matters:**
This is a demo stand-in, not authentication. Anyone can read the credentials from the bundle. It
exists to demonstrate role-based navigation, and the backend performs no auth check either.


---
### Byte 19: Admin order search — reading Elasticsearch

**Builds on:** Byte 11

**In plain terms:**
The admin order screen. Every result comes from Elasticsearch, never from PostgreSQL.

**The code:**
```vue
<h2>Orders <span class="muted">· Elasticsearch only</span></h2>

<div class="card kpi">
  <div class="kpi-label">💰 Revenue (filtered)</div>
  <div class="price">{{ formatMoney(search.result.revenue) }}</div>
  <div class="kpi-sub">across {{ search.result.total }} orders</div>
</div>
<div class="card kpi">
  <div class="kpi-label">🧾 By status</div>
  <span v-for="(n, s) in search.result.status_facets" class="badge">{{ s }} · {{ n }}</span>
</div>
```
```js
const pages = computed(() =>
  Math.max(1, Math.ceil((search.result?.total || 0) / search.size))
)
onMounted(() => search.run(true))
```

**What's happening:**
Three numbers come back from **one** request: `total`, `revenue`, and `status_facets`. They are
displayed together because they all describe the same filtered set. `total` also drives the pager.

**Why it matters:**
Because revenue and facets are computed by Elasticsearch over the whole result set rather than the
current page, the numbers do not change as you page. A total that shifts when you click "Next" is
not a total.

---

### Byte 20: Order detail — status change with a version guard

**Builds on:** Byte 8

**In plain terms:**
Shows one order and lets an admin change its status, sending the version it currently sees.

**The code:**
```js
await updateStatus(route.params.id, {
  status: statusSel.value,
  expected_version: orders.detail.version
})
statusMsg.value = 'SUCCESS — status updated, search re-index queued'
```

**What's happening:**
`expected_version` is sent with the request. If another admin changed the order in between, the
version no longer matches and the backend rejects the write. After a success the view reloads, and
a `watch` on `route.params.id` reloads when the URL id changes.

**Why it matters:**
This is **optimistic concurrency** — the browser declares which version it was looking at, so two
admins cannot silently overwrite each other. The success message explicitly says re-indexing was
queued, because the status change has not reached Elasticsearch yet.

---

### Byte 21: Catalog admin — editing MongoDB directly

**Builds on:** Byte 15

**In plain terms:**
Create and edit products. It has its own form and calls the product endpoints directly.

**The code:**
```js
const canSave = computed(() => {
  const basics = String(form.value.title).trim() !== '' &&
                 String(form.value.price).trim() !== '' &&
                 form.value.category !== ''
  if (!basics) return false
  /* Create-only rule: a new document needs 1 attribute + 1 variant. */
  if (form.value._id) return true
  return attrs.value.some((a) => String(a.key).trim() !== '') &&
         variants.value.some((v) => String(v.sku).trim() !== '')
})
```
```vue
<Teleport to="body">
  <div v-if="formOpen" class="modal-backdrop">…</div>
</Teleport>
```

**What's happening:**
The Save button is disabled until the form is valid, and the hint text explains exactly what is
missing. `Teleport to="body"` renders the modal outside the component so it is not clipped by any
ancestor `overflow`.

**Why it matters:**
The create rule is asymmetric on purpose — a new product must declare at least one attribute and
one variant, but an edit does not. The comment points at the backend rule being mirrored, so the
two stay in step.


---
## PART 7 — COMPONENTS

### Byte 22: `SyncBadge` — making eventual consistency visible

**Builds on:** Byte 17

**In plain terms:**
A seven-line component that shows whether an order has reached the search index.

**The code:**
```vue
<template>
  <span class="badge queued" v-if="status === 'QUEUED'">{{ status }}</span>
  <span class="badge pending" v-else>{{ status }}</span>
</template>
<script setup>
defineProps({ status: String })
</script>
```

**What's happening:**
`defineProps` declares the prop. `QUEUED` gets a distinct colour class so the "not indexed yet"
state is visually obvious.

**Why it matters:**
This is the entire UI cost of the asynchronous sync design. Without it, a user who places an order
and immediately searches would see nothing and assume the system is broken. With it, the delay is
stated rather than hidden.

---

### Byte 23: `KpiCards` — the two-tile summary

**Builds on:** Byte 20

**In plain terms:**
Renders the order total and status as two cards above the line items.

**The code:**
```vue
<div class="card"><div>Order Total</div><div class="price">{{ total }}</div></div>
<div class="card"><div>Status</div><div class="price">{{ status }}</div></div>
```
```vue
<KpiCards :total="formatMoney(orders.detail.total_amount)"
          :status="orders.detail.status" />
```

**What's happening:**
The component is fully presentational — it receives already-formatted strings and knows nothing
about orders or the API.

**Why it matters:**
Formatting happens in the parent, not the component. That keeps the component reusable and means
the money rule (backend decides, frontend displays) holds everywhere without this file knowing the
rule exists.

---

### Byte 24: `ProductCard` — emoji art instead of photos

**Builds on:** Byte 16

**In plain terms:**
One product tile. Since there are no product images, it draws a pastel block with an emoji.

**The code:**
```js
const ART = {
  audio:       { emoji: '🎧', tone: 'art-pink' },
  office:      { emoji: '💡', tone: 'art-yellow' },
  cables:      { emoji: '🔌', tone: 'art-purple' },
  peripherals: { emoji: '⌨️', tone: 'art-blue' }
}
const art = computed(() => ART[props.product.category] || { emoji: '🛍️', tone: 'art-mint' })
```
```js
const highlight = computed(() =>
  Object.entries(props.product.attributes || {}).slice(0, 2)
    .map(([k, v]) => `${k}: ${v}`).join(' · ')
)
```

**What's happening:**
Category determines the artwork. The first two attributes are rendered as a subtitle line, with
`|| {}` guarding products that have no attributes at all.

**Why it matters:**
The `|| {}` guard is what keeps one sparse product from throwing an error and blanking the whole
grid. The `||` fallback in `ART` does the same for an unknown category.

---

### Byte 25: `UiDropdown` — a custom select

**Builds on:** Byte 8

**In plain terms:**
A styled dropdown built from plain markup instead of a native `<select>`.

**The code:**
```js
const emit = defineEmits(['update:modelValue', 'change'])
function pick(v) {
  open.value = false
  emit('update:modelValue', v)
  emit('change', v)
}
```
```vue
<div v-if="open" class="cdd-backdrop" @click="open = false"></div>
```

**What's happening:**
Emitting `update:modelValue` is what makes `v-model` work on the component. A full-screen
transparent backdrop closes the menu on any outside click. Selecting compares values with
`String(...)` on both sides so a numeric option value matches a string one.

**Why it matters:**
The `String()` coercion on both sides is the detail that prevents a dropdown silently failing to
show its selected item when one side is a number and the other a string — a very common source of
"the select shows nothing when I open it" bugs.


---
## PART 8 — ANIMATION SYSTEM

### Byte 26: The `fx` module

**Builds on:** Byte 16

**In plain terms:**
One shared reactive object collects all in-flight animations. It is deliberately **not** a store.

**The code:**
```js
import { reactive } from 'vue'

export const fx = reactive({ flyers: [], toasts: [], sparks: [] })

let seq = 0
const nextId = () => ++seq
```

**What's happening:**
`reactive` makes the object deeply reactive just like a store's state, but it has no actions,
getters, or persistence — it is a plain shared list. A monotonic counter gives every entry a
unique id.

**Why it matters:**
These are cosmetic and must never be shared through the store system or persisted anywhere. Using
a bare `reactive` object keeps that boundary obvious.

---

### Byte 27: Fly-to-cart geometry

**Builds on:** Byte 26

**In plain terms:**
To animate a pill from the button to the cart, it measures both elements and computes the offset.

**The code:**
```js
const a = fromEl.getBoundingClientRect()
const b = target.getBoundingClientRect()
const flyer = {
  id: nextId(),
  label: product.title || 'Item',
  x: a.left + a.width / 2,
  y: a.top + a.height / 2,
  dx: b.left + b.width / 2 - (a.left + a.width / 2),   // horizontal delta
  dy: b.top  + b.height / 2 - (a.top  + a.height / 2), // vertical delta
}
fx.flyers.push(flyer)
setTimeout(() => {
  fx.flyers = fx.flyers.filter((f) => f.id !== flyer.id)
  bumpCart()
}, 680)
```

**What's happening:**
`getBoundingClientRect()` gives pixel positions. The centre of the source becomes the start point,
and `dx`/`dy` are the deltas to the cart's centre. The timeout removes the entry and bumps the
cart — the animation length is 680ms in both JavaScript and CSS, so the removal happens exactly as
the pill lands.

**Why it matters:**
The removal is keyed by id rather than by clearing the whole array, so two animations running at
once do not cancel each other. The 680ms value must match the CSS `animation` duration or the pill
vanishes mid-flight.

---

### Byte 28: `FxLayer` — one overlay renders all effects

**Builds on:** Byte 27

**In plain terms:**
A single fixed-position component renders every active animation, driven entirely by the `fx` object.

**The code:**
```vue
<div class="fx-layer" aria-hidden="true">
  <div v-for="f in fx.flyers" :key="`f${f.id}`" class="fx-flyer"
       :style="{ '--x': `${f.x}px`, '--y': `${f.y}px`, ... }">
  <span v-for="s in fx.sparks" :key="`s${s.id}`" class="fx-spark" ...>
  <TransitionGroup name="fx-toast">
    <div v-for="t in fx.toasts" :key="`t${t.id}`" class="fx-toast">…</div>
  </TransitionGroup>
</div>
```
```css
.fx-layer { position: fixed; inset: 0; pointer-events: none; z-index: 60; }
```

**What's happening:**
JavaScript supplies only numbers via CSS custom properties; the actual movement is a CSS
`@keyframes` animation that reads them. `pointer-events: none` means the overlay spans the whole
viewport but never intercepts a click.

**Why it matters:**
Splitting position from animation means the layout logic is testable JavaScript and the motion is
declarative CSS. `pointer-events: none` is what stops an invisible full-screen div from swallowing
every button on the page.


---
## PART 9 — BUILD AND DEPLOYMENT

### Byte 29: The dev proxy

**Builds on:** Byte 13

**In plain terms:**
In development, Vite forwards `/api` calls to the backend so the browser sees one origin.

**The code:**
```js
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: { '/api': 'http://127.0.0.1:8000' }
  }
})
```

**What's happening:**
The browser requests `http://localhost:5173/api/products`. Vite's dev server sees the `/api`
prefix and forwards it to port 8000, returning the response as if it came from the same origin.

**Why it matters:**
This is why no source file needs a hostname, and why the same code works unchanged in production
where nginx does the identical job.

---

### Byte 30: The production image

**Builds on:** Byte 29

**In plain terms:**
Five lines: nginx plus a prebuilt `dist/` folder.

**The code:**
```dockerfile
FROM nginx:alpine
COPY dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
```
```nginx
server {
  listen 80;
  root /usr/share/nginx/html;
  index index.html;

  location /api/ { proxy_pass http://ecommerce-backend:8000; }

  location / { try_files $uri /index.html; }
}
```

**What's happening:**
There is no Node.js in the image and no build step — `npm run build` must be run beforehand, and
only the resulting static files are copied. `try_files … /index.html` is what makes client-side
routing work: a deep link like `/admin/orders/5` returns `index.html` so Vue Router can take over.

**Why it matters:**
Serving `index.html` for unknown paths is mandatory for a single-page app. Without it, refreshing
on `/admin/orders/5` would return a 404 from nginx. The proxy hostname is the compose service name,
which Docker's network resolves automatically.

---

### Byte 31: `frontend/dist/` is build output

**Builds on:** Byte 30

**In plain terms:**
The `dist/` folder holds hashed filenames like `AdminSearchView-bx7WUxxC.js`.

**The code:**
```
frontend/dist/
├── index.html
└── assets/
    ├── index-CZhbxK_l.js
    ├── AdminSearchView-bx7WUxxC.js
    ├── CatalogAdminView-BtqHlo6v.js
    └── money-DTr9dw3E.js
```

**What's happening:**
Vite hashes each filename from its contents. A changed file gets a new name, so a cached old file
can never be served in place of a new one. Lazy imports from Byte 4 are visible here as separate
chunks.

**Why it matters:**
It explains the folder structure: one chunk per lazily-loaded view. If you see a route's file here,
that route is a code-split entry point. Note `dist/` and `node_modules/` are both in `.gitignore`
and `.dockerignore` — they are generated, never committed.

---

## PUTTING IT TOGETHER

The frontend is a small Vue 3 single-page app whose only job is to be a faithful client of the
Java backend's HTTP contract. It is wired together by four files — `main.js` installs the plugins,
`router.js` maps URLs to views and enforces roles, `App.vue` provides the role-aware shell, and
`vite.config.js` points `/api` at the backend — and then organised into three honest layers: `api/`
owns every URL, `stores/` owns shared state, and `views/` only renders what it is given.

The design respects the backend's rules rather than duplicating them. Money arrives as a string and
is only ever formatted, never recalculated. The checkout sends ids and quantities and lets the
server decide the price. The admin search displays `revenue` and `status_facets` exactly as
Elasticsearch computed them, so those numbers never shift as you page. And `SyncBadge` exists purely
to make the asynchronous indexing delay visible rather than mysterious.

The `fx` module and its overlay sit entirely outside the data flow: a plain reactive object, one
presentational component, and CSS keyframes that read numbers as custom properties. That separation
is why the animations can be elaborate without ever making a purchase fail. Finally, the same
`/api` paths work in development (Vite proxy) and production (nginx proxy), which is exactly why
the backend could be replaced from one language to another without a single line here changing.