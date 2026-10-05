import { defineStore } from 'pinia'
import { listProductsPage } from '../api/products.js'

/** Storefront page size. The backend defaults to the same value. */
const PAGE_SIZE = 24

/** Catalog paging. Items accumulate; the backend returns one page at a time. */
export const useCatalog = defineStore('catalog', {
  state: () => ({
    items: [],
    state: 'idle',      // idle | loading | success | empty | error
    error: '',
    page: 0,
    hasNext: false,
    totalItems: 0,
    totalPages: 0,      // page count, for the pager when infinite scroll is off
    loadingMore: false, // a follow-up page is in flight
    loadMoreError: '',   // set when a follow-up page failed; retryable
    infiniteScroll: true // checkbox: auto-load on scroll, or classic pagination
  }),
  getters: {
    allLoaded: (state) => state.state === 'success' && !state.hasNext
  },
  actions: {
    /** Current storefront filter set (active products only, as before). */
    params() {
      const p = { page: 0, size: PAGE_SIZE }
      if (this.category) p.category = this.category
      if (this.search) p.search = this.search
      return p
    },

    /* First page. Replaces the list (used on mount and when filters change). */
    async load(filters = {}) {
      if (filters.category !== undefined) this.category = filters.category || ''
      if (filters.search !== undefined) this.search = filters.search || ''
      this.state = 'loading'
      this.error = ''
      this.page = 0
      this.hasNext = false
      this.loadMoreError = ''
      try {
        const res = await listProductsPage(this.params())
        this.items = res.items
        this.page = res.page
        this.hasNext = res.hasNext
        this.totalItems = res.totalItems
        this.totalPages = res.totalPages
        this.state = res.items.length ? 'success' : 'empty'
      } catch (e) {
        this.state = 'error'
        this.error = e.message
      }
    },

    /* Next page. Appends; never replaces. Guards against concurrent calls. */
    async loadMore() {
      if (this.loadingMore || this.state !== 'success' || !this.hasNext) return
      this.loadingMore = true
      this.loadMoreError = ''
      const next = this.page + 1
      try {
        const res = await listProductsPage({ ...this.params(), page: next })
        if (res.page !== next) return
        const seen = new Set(this.items.map((p) => p._id))
        this.items = this.items.concat(res.items.filter((p) => !seen.has(p._id)))
        this.page = res.page
        this.hasNext = res.hasNext
        this.totalItems = res.totalItems
        this.totalPages = res.totalPages
      } catch (e) {
        // Already-loaded products stay on screen; only the retry affordance appears.
        this.loadMoreError = e.message || 'Failed to load more products.'
      } finally {
        this.loadingMore = false
      }
    },

    /* Re-request the page that failed. */
    async retryLoadMore() {
      this.loadMoreError = ''
      await this.loadMore()
    },

    /**
     * Jump to a specific page, REPLACING the list. This is the pager path used
     * when infinite scroll is off: unlike `loadMore`, it must not append, or the
     * grid would grow by every page the shopper paged past.
     */
    async goToPage(page) {
      if (this.loadingMore || this.state !== 'success') return
      const last = Math.max(0, this.totalPages - 1)
      if (page < 0 || page > last || page === this.page) return
      this.loadingMore = true
      this.loadMoreError = ''
      try {
        const res = await listProductsPage({ ...this.params(), page })
        this.items = res.items
        this.page = res.page
        this.hasNext = res.hasNext
        this.totalItems = res.totalItems
      } catch (e) {
        // The products already on screen stay put; only the retry note appears.
        this.loadMoreError = e.message || 'Failed to load more products.'
      } finally {
        this.loadingMore = false
      }
    },
    nextPage() { return this.goToPage(this.page + 1) },
    prevPage() { return this.goToPage(this.page - 1) }
  }
})
