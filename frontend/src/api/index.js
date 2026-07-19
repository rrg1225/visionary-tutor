import axios from 'axios'
import { useAuthStore } from '../stores/authStore.js'

/**
 * API 客户端配置
 * 
 * 功能：
 * 1. 基础配置（baseURL、timeout、headers）
 * 2. 请求拦截器 - 自动添加 Authorization 头
 * 3. 响应拦截器 - 统一错误处理、Token 过期自动刷新
 */

const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
  timeout: 30000,
  headers: {
    'Content-Type': 'application/json',
  },
})

function normalizeApiError(error) {
  const data = error.response?.data || {}
  const errorCode = data.errorCode || data.code || `HTTP-${error.response?.status || 'NETWORK'}`
  const message = data.message || error.message || '请求失败'
  return {
    errorCode,
    message,
    status: error.response?.status || 0,
    traceId: data.requestId || data.traceId || '',
  }
}

function publishApiError(error, config = {}) {
  if (config.silent) return
  const normalized = normalizeApiError(error)
  error.visionary = normalized
  window.dispatchEvent(new CustomEvent('api:error', { detail: normalized }))
}

// 用于 Token 刷新的独立实例（避免循环拦截）
const refreshApi = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
  timeout: 10000,
  headers: {
    'Content-Type': 'application/json',
  },
})

// 是否正在刷新 Token
let isRefreshing = false
// 等待刷新完成的请求队列
let refreshSubscribers = []

/**
 * 订阅 Token 刷新
 * @param {Function} callback - 刷新完成后执行的回调
 */
function subscribeTokenRefresh(resolve, reject, originalRequest) {
  refreshSubscribers.push({ resolve, reject, originalRequest })
}

/**
 * 通知所有订阅者 Token 已刷新
 * @param {string} token - 新 Token
 */
function onTokenRefreshed(token) {
  refreshSubscribers.forEach(({ resolve, originalRequest }) => {
    originalRequest.headers.Authorization = `Bearer ${token}`
    resolve(api(originalRequest))
  })
  refreshSubscribers = []
}

function onTokenRefreshFailed(error) {
  refreshSubscribers.forEach(({ reject }) => reject(error))
  refreshSubscribers = []
}

// ========== 请求拦截器 ==========

api.interceptors.request.use(
  (config) => {
    // 从 localStorage 获取 Token（不依赖 store 以避免循环依赖）
    const token = localStorage.getItem('vt_token')
    
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    
    // 如果有游客ID，也添加到请求头（用于后端追踪）
    const guestId = localStorage.getItem('vt_guest_id')
    if (guestId) {
      config.headers['X-Guest-Id'] = guestId
    }
    
    return config
  },
  (error) => {
    return Promise.reject(error)
  }
)

// ========== 响应拦截器 ==========

api.interceptors.response.use(
  // 成功响应
  (response) => {
    return response
  },
  
  // 错误处理
  async (error) => {
    const originalRequest = error.config
    
    // 如果不是 401 错误，直接抛出
    if (error.response?.status !== 401) {
      publishApiError(error, originalRequest)
      return Promise.reject(error)
    }

    // Best-effort requests (such as the pre-auth guest snapshot) must fail
    // fast instead of delaying or preventing the primary user action.
    if (originalRequest?.skipAuthRefresh) {
      publishApiError(error, originalRequest)
      return Promise.reject(error)
    }
    
    // 401 错误处理：尝试刷新 Token
    
    // 1. 如果是刷新 Token 的请求本身失败，说明需要重新登录
    if (originalRequest?.url?.includes('/auth/refresh')) {
      // 清除认证状态
      const authStore = useAuthStore()
      authStore.clearAuth()
      
      // 可以在这里触发全局登录弹窗
      window.dispatchEvent(new CustomEvent('auth:sessionExpired'))
      
      return Promise.reject(error)
    }

    // A request may be replayed at most once. Without this guard, a valid
    // refreshed token plus an endpoint that still returns 401 loops forever.
    if (!originalRequest || originalRequest._retry) {
      publishApiError(error, originalRequest)
      return Promise.reject(error)
    }
    originalRequest._retry = true
    
    // 2. 如果已经在刷新中，将请求加入队列等待
    if (isRefreshing) {
      return new Promise((resolve, reject) => {
        subscribeTokenRefresh(resolve, reject, originalRequest)
      })
    }
    
    // 3. 开始刷新 Token
    isRefreshing = true
    
    try {
      const authStore = useAuthStore()
      const currentToken = localStorage.getItem('vt_token')
      
      if (!currentToken) {
        throw new Error('No token available')
      }
      
      // 调用刷新接口
      const response = await refreshApi.post('/auth/refresh', null, {
        headers: {
          Authorization: `Bearer ${currentToken}`,
        },
      })
      
      const { token: newToken } = response.data
      
      // 更新存储
      localStorage.setItem('vt_token', newToken)
      if (authStore.token !== undefined) {
        authStore.token = newToken
      }
      
      // 通知等待的请求
      onTokenRefreshed(newToken)
      
      // 重试原请求
      originalRequest.headers.Authorization = `Bearer ${newToken}`
      return api(originalRequest)
      
    } catch (refreshError) {
      onTokenRefreshFailed(refreshError)
      // 刷新失败，清除认证
      const authStore = useAuthStore()
      authStore.clearAuth()
      
      // 触发全局事件
      window.dispatchEvent(new CustomEvent('auth:sessionExpired'))
      
      return Promise.reject(refreshError)
    } finally {
      isRefreshing = false
    }
  }
)

export default api
