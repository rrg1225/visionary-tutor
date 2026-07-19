import api from './index.js'

/**
 * Auth API - 认证相关接口
 * 
 * 接口列表：
 * - POST /api/auth/guest      - 创建游客会话
 * - POST /api/auth/register    - 用户注册（支持数据迁移）
 * - POST /api/auth/login       - 用户登录（支持数据迁移）
 * - POST /api/auth/refresh     - 刷新 Token
 * - POST /api/auth/logout      - 用户登出
 * - GET  /api/auth/validate    - 验证 Token
 */

/**
 * 创建游客会话
 * @returns {Promise<{token: string, guestId: string, expiresAt: string}>}
 */
export async function createGuestSession() {
  const response = await api.post('/auth/guest')
  return response.data
}

/**
 * 查询游客 Redis 对话配额（需游客 JWT）
 * @returns {Promise<{usedTurns: number, maxTurns: number, remainingTurns: number, sessionTtlSeconds: number}>}
 */
export async function fetchGuestChatQuota() {
  const response = await api.get('/auth/guest/quota')
  return response.data
}

export async function saveGuestSnapshot(contextJson) {
  // Snapshot persistence is best-effort and must never block login/register.
  const response = await api.put('/auth/guest/context', { contextJson }, {
    silent: true,
    skipAuthRefresh: true,
  })
  return response.data
}

/**
 * 获取一次性注册图形验证码。
 * @returns {Promise<{captchaId: string, imageDataUrl: string, expiresInSeconds: number}>}
 */
export async function fetchRegistrationCaptcha() {
  const response = await api.get('/auth/captcha', { silent: true })
  return response.data
}

/**
 * 用户注册
 * @param {Object} data - 注册信息
 * @param {string} data.username - 用户名
 * @param {string} data.password - 密码
 * @param {string} [data.email] - 邮箱（可选）
 * @param {string} [data.displayName] - 显示名称（可选）
 * @param {string} [data.learningPreference] - 学习偏好（可选）
 * @param {string} [data.guestId] - 游客ID（用于数据迁移）
 * @returns {Promise<AuthResponse>}
 */
export async function register(data) {
  const response = await api.post('/auth/register', data)
  return response.data
}

/**
 * 用户登录
 * @param {Object} data - 登录信息
 * @param {string} data.username - 用户名
 * @param {string} data.password - 密码
 * @param {string} [data.guestId] - 游客ID（用于数据迁移）
 * @returns {Promise<AuthResponse>}
 */
export async function login(data) {
  const response = await api.post('/auth/login', data)
  return response.data
}

export async function requestPasswordReset(email) {
  const response = await api.post('/auth/password-reset/request', { email })
  return response.data
}

export async function confirmPasswordReset(token, newPassword) {
  const response = await api.post('/auth/password-reset/confirm', { token, newPassword })
  return response.data
}

/**
 * 刷新 Token
 * @param {string} token - 当前 Token
 * @returns {Promise<{token: string, tokenType: string, expiresIn: number}>}
 */
export async function refreshToken(token) {
  const response = await api.post('/auth/refresh', null, {
    headers: {
      Authorization: `Bearer ${token}`,
    },
  })
  return response.data
}

/**
 * 用户登出
 * @param {string} token - 当前 Token
 * @returns {Promise<{success: boolean, message: string}>}
 */
export async function logout(token) {
  const response = await api.post('/auth/logout', null, {
    headers: {
      Authorization: `Bearer ${token}`,
    },
  })
  return response.data
}

/**
 * 验证 Token
 * @param {string} token - 当前 Token
 * @returns {Promise<{valid: boolean, message: string, type?: string, subject?: string}>}
 */
export async function validateToken(token) {
  const response = await api.get('/auth/validate', {
    headers: {
      Authorization: `Bearer ${token}`,
    },
  })
  return response.data
}

/**
 * Auth Response 类型定义（用于 IDE 提示）
 * @typedef {Object} AuthResponse
 * @property {string} token - JWT Token
 * @property {string} tokenType - Token 类型（Bearer）
 * @property {number} expiresIn - Token 过期时间（秒）
 * @property {boolean} isGuest - 是否为游客
 * @property {UserInfo} user - 用户信息
 * @property {MigrationInfo} [migration] - 数据迁移信息（如果有）
 */

/**
 * User Info 类型定义
 * @typedef {Object} UserInfo
 * @property {number} id - 用户ID
 * @property {string} username - 用户名
 * @property {string} email - 邮箱
 * @property {string} displayName - 显示名称
 * @property {string} [avatarUrl] - 头像URL
 * @property {string} [gradeLevel] - 年级/水平
 * @property {string} [learningGoal] - 学习目标
 */

/**
 * Migration Info 类型定义
 * @typedef {Object} MigrationInfo
 * @property {boolean} migrated - 是否执行了迁移
 * @property {string} fromGuestId - 原游客ID
 * @property {number} migratedSessionsCount - 迁移的会话数
 * @property {number} migratedReportsCount - 迁移的报告数
 */
