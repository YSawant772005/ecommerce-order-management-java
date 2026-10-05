// FastAPI returns `detail` as a string for simple errors but as an array of
// {type, loc, msg} objects for Pydantic validation failures. Stringifying the
// array directly gives "[object Object]", which hides the real cause.
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

export async function listProductsPage(params = {}) {
  const cleanParams = Object.fromEntries(
    Object.entries(params).filter(([, value]) => value !== undefined && value !== null && value !== '')
  )
  const q = new URLSearchParams(cleanParams).toString()
  const r = await fetch(`/api/products${q ? `?${q}` : ''}`)
  if (!r.ok) throw new Error(await detailOf(r, 'products failed'))
  return r.json()
}

/**
 * Flat list for callers that do not page. Walks every page server-side so the
 * admin catalog still shows the whole collection; `cap` bounds how many products
 * are rendered, and the caller reports the remainder.
 */
export async function listProducts(params = {}, cap = 1000) {
  const items = []
  let page = 0
  let totalItems = 0
  while (items.length < cap) {
    const res = await listProductsPage({ ...params, page, size: 100 })
    items.push(...res.items)
    totalItems = res.totalItems
    page = res.page + 1
    if (!res.hasNext) break
  }
  return { items: items.slice(0, cap), totalItems }
}
export async function getProduct(id) {
  const r = await fetch(`/api/products/${id}`)
  if (!r.ok) throw new Error(await detailOf(r, 'product not found'))
  return r.json()
}
export async function createProduct(payload) {
  const r = await fetch('/api/products', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  })
  if (!r.ok) throw new Error(await detailOf(r, 'create failed'))
  return r.json()
}
export async function updateProduct(id, payload) {
  const r = await fetch(`/api/products/${id}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  })
  if (!r.ok) throw new Error(await detailOf(r, 'update failed'))
  return r.json()
}
