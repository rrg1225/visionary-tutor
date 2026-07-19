import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { login, register, createGuestSession, fetchGuestChatQuota, refreshToken, saveGuestSnapshot, validateToken } from '../api/auth.js'
import router from '../router'

// NOTE: authStore.js 当前约500行（报告中13000行说法不准确）。
// 未来计划：逐步迁移到 TypeScript (.ts)。新功能/新store优先使用 .ts 文件。

async function syncLearnerProfileFromBackend(userId) {
  if (!userId) return
  window.dispatchEvent(new CustomEvent('auth:profileSyncRequested', { detail: { userId } }))
}

function sleep(ms) {
  return new Promise((resolve) => window.setTimeout(resolve, ms))
}

function formatGuestSessionError(error) {
  const status = error?.response?.status
  if (!error?.response) {
    return '无法连接后端服务，请确认 MySQL、Redis 与后端（8080）已启动后再刷新'
  }
  if (status === 503 || status === 502) {
    return '后端或 Redis 尚未就绪，请等待几秒后点击「重试连接」'
  }
  if (status >= 500) {
    return `服务器错误（${status}），请检查后端日志`
  }
  return error?.response?.data?.message || '创建游客会话失败，请刷新页面重试'
}

/**
 * Auth Store - 管理认证状态和渐进式注册流程
 * 
 * 核心功能：
 * 1. 游客模式管理 - 自动创建/恢复游客会话
 * 2. 登录/注册 - 支持数据迁移（guestId 传递）
 * 3. Token 管理 - 自动刷新、持久化存储
 * 4. 权限守卫 - 检查用户类型和认证状态
 * 5. 挂起操作 - 登录后继续之前被拦截的操作
 * 
 * 渐进式注册（Progressive Profiling）实现：
 * - 游客可以体验基础功能（AI对话、视频流）
 * - 深度操作触发登录弹窗
 * - 登录/注册时自动传递 guestId 实现数据迁移
 */

const STORAGE_KEYS = {
  TOKEN: 'vt_token',
  GUEST_ID: 'vt_guest_id',
  USER: 'vt_user',
  IS_GUEST: 'vt_is_guest',
}

async function persistGuestLearningSnapshot(currentGuestId) {
  if (!currentGuestId) return { saved: true, hadLocalData: false }
  const chatKey = `vt_guest_chat_${currentGuestId}`
  let messages = []
  try {
    messages = JSON.parse(localStorage.getItem(chatKey) || '[]')
  } catch {
    messages = []
  }
  const storage = {}
  for (let index = 0; index < localStorage.length; index += 1) {
    const key = localStorage.key(index)
    if (!key?.startsWith('vt_') || key === STORAGE_KEYS.TOKEN || key === STORAGE_KEYS.USER) continue
    if (key === STORAGE_KEYS.GUEST_ID || key === STORAGE_KEYS.IS_GUEST || key === chatKey) continue
    const value = localStorage.getItem(key)
    if (value != null) storage[key] = value
  }
  const snapshot = {
    version: 1,
    capturedAt: new Date().toISOString(),
    lastRoute: `${window.location.pathname}${window.location.search}`,
    messages: Array.isArray(messages) ? messages : [],
    storage,
  }
  const hadLocalData = snapshot.messages.length > 0 || Object.keys(storage).length > 0
  try {
    const result = await saveGuestSnapshot(JSON.stringify(snapshot))
    return { saved: result?.saved === true, hadLocalData }
  } catch {
    return { saved: false, hadLocalData }
  }
}

function finalizeGuestMigration(previousGuestId, snapshotResult, migration) {
  const completed = migration?.migrated === true && (snapshotResult.saved || !snapshotResult.hadLocalData)
  if (completed) {
    localStorage.removeItem(`vt_guest_chat_${previousGuestId}`)
    localStorage.removeItem('vt_pending_guest_migration')
  } else if (previousGuestId) {
    localStorage.setItem('vt_pending_guest_migration', previousGuestId)
  }
}

export const useAuthStore = defineStore('auth', () => {
  // ==================== State ====================
  
  /** JWT Token */
  const token = ref(localStorage.getItem(STORAGE_KEYS.TOKEN) || null)
  
  /** 游客ID（格式：gst_{uuid}） */
  const guestId = ref(localStorage.getItem(STORAGE_KEYS.GUEST_ID) || null)
  
  /** 用户信息 */
  const user = ref(JSON.parse(localStorage.getItem(STORAGE_KEYS.USER) || 'null'))
  
  /** 是否为游客模式 */
  const isGuest = ref(localStorage.getItem(STORAGE_KEYS.IS_GUEST) === 'true')
  
  /** 认证弹窗显示状态 */
  const showAuthModal = ref(false)
  
  /** 弹窗模式：login 或 register */
  const authModalMode = ref('register') // 默认引导注册
  
  /** 认证加载状态 */
  const isLoading = ref(false)
  
  /** 认证错误信息 */
  const authError = ref(null)

  /** 游客会话初始化失败（与登录/注册表单错误分离） */
  const guestSessionError = ref(null)

  /** initAuth 是否已完成（main.js 启动阶段置 true） */
  const authInitialized = ref(false)
  
  /** 挂起的操作（登录后继续执行） */
  const pendingAction = ref(null)
  
  /** 对话轮次计数（触发登录的阈值判断） */
  const conversationTurns = ref(0)

  /** 游客配额以后端返回值为准，避免前后端阈值漂移。 */
  const guestChatQuota = ref({ usedTurns: 0, maxTurns: 5, remainingTurns: 5 })

  // ==================== Getters ====================
  
  /** 是否已登录（游客或正式用户都算） */
  const isLoggedIn = computed(() => !!token.value)
  
  /** 是否已注册为正式用户 */
  const isRegistered = computed(() => isLoggedIn.value && !isGuest.value)
  
  /** 显示的用户名 */
  const displayName = computed(() => {
    if (isGuest.value) return '游客'
    return user.value?.displayName || user.value?.username || '用户'
  })
  
  /** 用户头像 */
  const avatarUrl = computed(() => {
    return user.value?.avatarUrl || null
  })
  
  /** 用户ID（正式用户返回数字ID，游客返回 guestId） */
  const currentUserId = computed(() => {
    if (isGuest.value) return guestId.value
    return user.value?.id || null
  })
  
  /** 免费对话上限（与后端 GuestSessionService.MAX_GUEST_CHAT_TURNS 一致） */
  const GUEST_CHAT_TURN_LIMIT = 5

  const guestMaxTurns = computed(() => guestChatQuota.value.maxTurns || GUEST_CHAT_TURN_LIMIT)
  const guestRemainingTurns = computed(() => Math.max(0, guestChatQuota.value.remainingTurns ?? (guestMaxTurns.value - conversationTurns.value)))

  /** 是否需要显示登录提示（游客且已用尽免费次数） */
  const shouldPromptLogin = computed(() => {
    return isGuest.value && guestRemainingTurns.value <= 0
  })

  function applyChatQuota(quota) {
    if (!quota || typeof quota.usedTurns !== 'number') return
    conversationTurns.value = quota.usedTurns
    const maxTurns = Number.isFinite(quota.maxTurns) ? quota.maxTurns : GUEST_CHAT_TURN_LIMIT
    guestChatQuota.value = {
      ...quota,
      maxTurns,
      remainingTurns: Number.isFinite(quota.remainingTurns)
        ? quota.remainingTurns
        : Math.max(0, maxTurns - quota.usedTurns),
    }
  }

  /**
   * 从 Redis 同步游客对话配额（发送消息前 / 发送后调用）
   */
  async function syncGuestChatQuota() {
    if (!isGuest.value || !token.value) {
      return {
        usedTurns: conversationTurns.value,
        maxTurns: GUEST_CHAT_TURN_LIMIT,
        remainingTurns: Math.max(0, GUEST_CHAT_TURN_LIMIT - conversationTurns.value),
      }
    }
    try {
      const quota = await fetchGuestChatQuota()
      applyChatQuota(quota)
      return quota
    } catch (error) {
      console.warn('Failed to sync guest chat quota:', error)
      return null
    }
  }

  // ==================== Actions ====================

  /** 从 localStorage 重新加载认证快照（路由/E2E 在模块初始化后写入时使用） */
  function reloadFromStorage() {
    token.value = localStorage.getItem(STORAGE_KEYS.TOKEN) || null
    guestId.value = localStorage.getItem(STORAGE_KEYS.GUEST_ID) || null
    try {
      user.value = JSON.parse(localStorage.getItem(STORAGE_KEYS.USER) || 'null')
    } catch {
      user.value = null
    }
    isGuest.value = localStorage.getItem(STORAGE_KEYS.IS_GUEST) === 'true'
  }
  
  /**
   * 初始化认证状态
   * 页面加载时调用，恢复本地存储的认证信息或创建新游客会话
   * 如果 token 存在，必须向后端发起验证请求，防止过期 token 绕过路由守卫
   */
  async function initAuth() {
    reloadFromStorage()
    guestSessionError.value = null
    try {
      if (token.value) {
        try {
          const validation = await validateToken(token.value)
          if (!validation?.valid) {
            throw new Error(validation?.message || 'Token invalid or expired')
          }
          const tokenIsGuest = validation.type === 'GUEST'
          if (tokenIsGuest !== isGuest.value) {
            isGuest.value = tokenIsGuest
            if (tokenIsGuest) {
              user.value = {
                id: validation.subject || guestId.value,
                username: 'Guest',
                displayName: '游客',
              }
            }
            persistAuth()
          }
          if (isGuest.value) {
            await syncGuestChatQuota()
          }
          return true
        } catch (error) {
          console.warn('Token validation failed, clearing auth:', error)
          clearAuth()
          if (router.currentRoute.value.name !== 'auth') {
            router.push({ name: 'auth', query: { mode: 'login' } })
          }
        }
      }

      await createGuestSessionIfNeeded()
      if (isGuest.value) {
        await syncGuestChatQuota()
      }
      return true
    } finally {
      authInitialized.value = true
    }
  }
  
  /**
   * 创建游客会话（如果还没有）
   */
  async function createGuestSessionIfNeeded(options = {}) {
    if (guestId.value && token.value && isGuest.value) {
      return true
    }

    const maxAttempts = options.retries ?? 3
    isLoading.value = true
    guestSessionError.value = null

    try {
      for (let attempt = 1; attempt <= maxAttempts; attempt += 1) {
        try {
          const response = await createGuestSession()

          token.value = response.token
          guestId.value = response.guestId
          isGuest.value = true
          user.value = {
            id: response.guestId,
            username: 'Guest',
            displayName: '游客',
          }

          persistAuth()
          applyChatQuota(response.chatQuota)
          guestSessionError.value = null
          console.log('Guest session created:', response.guestId, response.chatQuota)
          return true
        } catch (error) {
          console.error(`Failed to create guest session (attempt ${attempt}/${maxAttempts}):`, error)
          if (attempt < maxAttempts) {
            await sleep(800 * attempt)
            continue
          }
          guestSessionError.value = formatGuestSessionError(error)
          return false
        }
      }
      return false
    } finally {
      isLoading.value = false
    }
  }

  /** Auth 页或全局重试游客会话 */
  async function retryGuestSession() {
    clearAuth()
    return createGuestSessionIfNeeded({ retries: 2 })
  }
  
  /**
   * 用户注册
   * @param {Object} credentials - 注册信息 { username, password, email, displayName, learningPreference }
   * @returns {Promise<boolean>} 是否成功
   */
  async function signup(credentials) {
    try {
      isLoading.value = true
      authError.value = null
      const previousGuestId = guestId.value
      const snapshotResult = await persistGuestLearningSnapshot(previousGuestId)
      
      // 注册时传递当前 guestId 以便数据迁移
      const request = {
        ...credentials,
        guestId: guestId.value, // 关键：传递游客ID实现数据迁移
      }
      
      const response = await register(request)
      
      // 更新为正式用户信息
      token.value = response.token
      guestId.value = null
      isGuest.value = false
      user.value = response.user
      
      // 持久化
      persistAuth()
      finalizeGuestMigration(previousGuestId, snapshotResult, response.migration)
      
      // 记录迁移结果
      if (response.migration?.migrated) {
        console.log('Data migrated:', response.migration)
      }
      
      // 关闭弹窗
      closeAuthModal()
      
      await syncLearnerProfileFromBackend(response.user?.id)
      
      // 执行挂起的操作
      await executePendingAction()
      
      return true
    } catch (error) {
      console.error('Registration failed:', error)
      authError.value = error.response?.data?.message || '注册失败，请重试'
      return false
    } finally {
      isLoading.value = false
    }
  }
  
  /**
   * 用户登录
   * @param {Object} credentials - 登录信息 { username, password }
   * @returns {Promise<boolean>} 是否成功
   */
  async function signin(credentials) {
    try {
      isLoading.value = true
      authError.value = null
      const previousGuestId = guestId.value
      const snapshotResult = await persistGuestLearningSnapshot(previousGuestId)
      
      // 登录时传递当前 guestId 以便数据迁移
      const request = {
        ...credentials,
        guestId: guestId.value, // 关键：传递游客ID实现数据迁移
      }
      
      const response = await login(request)
      
      // 更新为正式用户信息
      token.value = response.token
      guestId.value = null
      isGuest.value = false
      user.value = response.user
      
      // 持久化
      persistAuth()
      finalizeGuestMigration(previousGuestId, snapshotResult, response.migration)
      
      // 记录迁移结果
      if (response.migration?.migrated) {
        console.log('Data migrated:', response.migration)
      }
      
      // 关闭弹窗
      closeAuthModal()
      
      await syncLearnerProfileFromBackend(response.user?.id)
      
      // 执行挂起的操作
      await executePendingAction()
      
      return true
    } catch (error) {
      console.error('Login failed:', error)
      authError.value = error.response?.data?.message || '登录失败，请检查用户名和密码'
      return false
    } finally {
      isLoading.value = false
    }
  }
  
  /**
   * 登出
   */
  async function logout() {
    clearAuth()
    // 重新创建游客会话
    return createGuestSessionIfNeeded()
  }
  
  /**
   * 打开认证弹窗
   * @param {string} mode - 'login' 或 'register'
   * @param {Object} action - 挂起的操作信息 { type, payload }
   */
  function openAuthModal(mode = 'register', action = null) {
    authModalMode.value = mode
    pendingAction.value = action
    authError.value = null
    showAuthModal.value = false
    router.push({ name: 'auth', query: { mode } })
  }

  /**
   * 关闭认证页（由路由页返回，不再使用遮罩弹窗）
   */
  function closeAuthModal() {
    showAuthModal.value = false
    authError.value = null
  }
  
  /**
   * 切换弹窗模式
   */
  function toggleAuthModalMode() {
    authModalMode.value = authModalMode.value === 'login' ? 'register' : 'login'
    authError.value = null
  }
  
  /**
   * 设置挂起的操作
   * @param {Object} action - { type, payload }
   */
  function setPendingAction(action) {
    pendingAction.value = action
  }
  
  /**
   * 执行挂起的操作
   */
  async function executePendingAction() {
    if (!pendingAction.value) return
    
    const action = pendingAction.value
    pendingAction.value = null
    
    try {
      sessionStorage.setItem('vt_pending_action', JSON.stringify(action))
    } catch {
      // ignore quota errors
    }

    window.dispatchEvent(new CustomEvent('auth:continueAction', { 
      detail: action 
    }))
  }
  
  /**
   * 增加对话轮次
   */
  function incrementConversationTurns() {
    conversationTurns.value++
  }
  
  /**
   * 重置对话轮次
   */
  function resetConversationTurns() {
    conversationTurns.value = 0
    guestChatQuota.value = { usedTurns: 0, maxTurns: GUEST_CHAT_TURN_LIMIT, remainingTurns: GUEST_CHAT_TURN_LIMIT }
  }
  
  /**
   * 持久化认证信息到 localStorage
   */
  function persistAuth() {
    if (token.value) {
      localStorage.setItem(STORAGE_KEYS.TOKEN, token.value)
    } else {
      localStorage.removeItem(STORAGE_KEYS.TOKEN)
    }
    
    if (guestId.value) {
      localStorage.setItem(STORAGE_KEYS.GUEST_ID, guestId.value)
    } else {
      localStorage.removeItem(STORAGE_KEYS.GUEST_ID)
    }
    
    if (user.value) {
      localStorage.setItem(STORAGE_KEYS.USER, JSON.stringify(user.value))
    } else {
      localStorage.removeItem(STORAGE_KEYS.USER)
    }
    
    localStorage.setItem(STORAGE_KEYS.IS_GUEST, isGuest.value.toString())
  }
  
  /**
   * 清除认证信息
   */
  function clearAuth() {
    token.value = null
    guestId.value = null
    user.value = null
    isGuest.value = false
    conversationTurns.value = 0
    
    localStorage.removeItem(STORAGE_KEYS.TOKEN)
    localStorage.removeItem(STORAGE_KEYS.GUEST_ID)
    localStorage.removeItem(STORAGE_KEYS.USER)
    localStorage.removeItem(STORAGE_KEYS.IS_GUEST)
  }
  
  /**
   * 获取用于 API 请求的认证头
   * @returns {Object} 包含 Authorization 头的对象
   */
  function getAuthHeaders() {
    if (!token.value) return {}
    return {
      Authorization: `Bearer ${token.value}`,
    }
  }
  
  /**
   * 尝试刷新 Token
   */
  async function tryRefreshToken() {
    if (!token.value) return false
    
    try {
      const response = await refreshToken(token.value)
      token.value = response.token
      persistAuth()
      return true
    } catch (error) {
      console.error('Token refresh failed:', error)
      // 刷新失败，清除认证
      clearAuth()
      return false
    }
  }

  return {
    // State
    token,
    guestId,
    user,
    isGuest,
    isLoading,
    authError,
    guestSessionError,
    authInitialized,
    showAuthModal,
    authModalMode,
    pendingAction,
    conversationTurns,
    guestChatQuota,
    
    // Getters
    isLoggedIn,
    isRegistered,
    displayName,
    avatarUrl,
    currentUserId,
    shouldPromptLogin,
    guestMaxTurns,
    guestRemainingTurns,
    
    // Actions
    initAuth,
    reloadFromStorage,
    createGuestSessionIfNeeded,
    retryGuestSession,
    signup,
    signin,
    logout,
    openAuthModal,
    closeAuthModal,
    toggleAuthModalMode,
    setPendingAction,
    executePendingAction,
    syncGuestChatQuota,
    applyChatQuota,
    GUEST_CHAT_TURN_LIMIT,
    incrementConversationTurns,
    resetConversationTurns,
    getAuthHeaders,
    tryRefreshToken,
    clearAuth,
  }
})
