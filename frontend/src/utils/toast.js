/** 轻量全局 Toast，替代 element-plus ElMessage（无额外依赖） */

const COLORS = {
  success: '#166534',
  error: '#b91c1c',
  warning: '#92400e',
  progress: '#1e40af',
}

let hideTimer = null

function ensureEl() {
  let el = document.getElementById('vt-global-toast')
  if (!el) {
    el = document.createElement('div')
    el.id = 'vt-global-toast'
    el.setAttribute('role', 'status')
    el.style.cssText = [
      'position:fixed',
      'bottom:24px',
      'right:24px',
      'z-index:9999',
      'padding:12px 16px',
      'border-radius:8px',
      'color:#fff',
      'font-size:14px',
      'line-height:1.45',
      'max-width:360px',
      'box-shadow:0 4px 12px rgba(0,0,0,.15)',
      'display:none',
    ].join(';')
    document.body.appendChild(el)
  }
  return el
}

/**
 * @param {string} message
 * @param {'success'|'error'|'warning'|'progress'} [type]
 * @param {number} [duration] ms
 */
export function showToast(message, type = 'success', duration = 2800) {
  if (typeof document === 'undefined') return
  const el = ensureEl()
  el.style.background = COLORS[type] || COLORS.success
  el.textContent = message
  el.style.display = 'block'
  if (hideTimer) clearTimeout(hideTimer)
  hideTimer = setTimeout(() => {
    el.style.display = 'none'
  }, duration)
}

export function toastSuccess(message, duration) {
  showToast(message, 'success', duration)
}

export function toastError(message, duration = 4500) {
  showToast(message, 'error', duration)
}

export function toastWarning(message, duration = 4500) {
  showToast(message, 'warning', duration)
}
