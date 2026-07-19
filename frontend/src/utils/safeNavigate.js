/**
 * Navigate with hard fallback when Vite dynamic import / cache fails (common on OneDrive paths).
 * @param {import('vue-router').Router} router
 * @param {string | import('vue-router').RouteLocationRaw} to
 * @param {{ forceReload?: boolean }} [options] - forceReload: full page load (recommended after login)
 */
export async function safeNavigate(router, to, options = {}) {
  const resolved = router.resolve(to)
  const target = resolved.fullPath || resolved.href

  if (options.forceReload) {
    window.location.assign(target)
    return
  }

  try {
    await router.replace(to)
    if (router.currentRoute.value.fullPath !== resolved.fullPath) {
      console.warn('[safeNavigate] route mismatch after replace, forcing reload:', target)
      window.location.assign(target)
    }
  } catch (error) {
    console.warn('[safeNavigate] router.replace failed, using location.assign:', error)
    window.location.assign(target)
  }
}
