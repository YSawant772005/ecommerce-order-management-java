import { defineStore } from 'pinia'
import { listProductsPage } from '../api/products.js'

/** Page size for the admin catalog table. */
const PAGE_SIZE = 24

/**
 * Admin catalog paging state.
 *
 * Separate from the storefront `catalog` store on purpose: the admin table must
 * default to `status=all` because it has to see drafts too, while the storefront
 * shows active products only. Both talk to the same paginated endpoint.
 */
export const useAdminCatalog = defineStore('adminCatalog', {
  state: () => ({
    items: [],
    state: 'idle',        // idle | loading | success | empty | error
    error: '',
    search: '',
    category: '',         // '' = All
    status: 'all',        // all | active | inactive
    page: 0,
    totalItems: 0,
    totalPages: 0,
    hasNext: false,
    loadingMore: false,
    loadMoreError: ''     // set when a follow-up page failed; retryable
  }),
  getters: {
    allLoaded: (state) => state.state === 'success' && !state.hasNext,
    filtersActive: (state) => Boolean(state.search || state.category || state.status !== 'all')
  },
  actions: {
    /** Current filter set, sent to MongoDB. Never filtered in Vue. */
    params() {
      const p = { page: 0, size: PAGE_SIZE, status: this.status }
      if (this.search) p.search = this.search
      if (this.category) p.category = this.category
      return p
    },

    /**
     * First page. Always REPLACES the list: used on mount and whenever a search
     * term or filter changes, so pages are never appended across filter sets.
     */
    async load() {
      this.state = 'loading'
      this.error = ''
      this.page = 0
      this.hasNext = false
      this.loadMoreError = ''
      try {
        const res = await listProductsPage(this.params())
        this.items = res.items
        this.page = res.page
        this.totalItems = res.totalItems
        this.totalPages = res.totalPages
        this.hasNext = res.hasNext
        this.state = res.items.length ? 'success' : 'empty'
      } catch (e) {
        this.state = 'error'
        this.error = e.message
      }
    },

    /**
     * Next page, APPENDED. These guards are what stop the IntersectionObserver
     * from firing overlapping requests while the sentinel stays on screen.
     */
    async loadMore() {
      if (this.loadingMore || this.state !== 'success' || !this.hasNext) return
      this.loadingMore = true
      this.loadMoreError = ''
      const next = this.page + 1
      try {
        const res = await listProductsPage({ ...this.params(), page: next })
        if (res.page !== next) return          // never append a page we already hold
        const seen = new Set(this.items.map((p) => p._id))
        this.items = this.items.concat(res.items.filter((p) => !seen.has(p._id)))
        this.page = res.page
        this.totalItems = res.totalItems
        this.totalPages = res.totalPages
        this.hasNext = res.hasNext
      } catch (e) {
        // Already-loaded rows stay on screen; only a retry affordance appears.
        this.loadMoreError = e.message || 'Failed to load products.'
      } finally {
        this.loadingMore = false
      }
    },

    /** Re-request the page that failed. */
    async retryLoadMore() {
      this.loadMoreError = ''
      await this.loadMore()
    },

    /** Search / category / status change: reset paging, reload page 0. */
    async setSearch(value) {
      this.search = value
      await this.load()
    },
    async setCategory(value) {
      this.category = value
      await this.load()
    },
    async setStatus(value) {
      this.status = value
      await this.load()
    },
    async resetFilters() {
      this.search = ''
      this.category = ''
      this.status = 'all'
      await this.load()
    }
  }
})