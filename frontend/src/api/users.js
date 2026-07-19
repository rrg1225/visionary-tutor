import api from './index.js'

/**
 * 当前登录用户的账号资料持久化
 *
 * - GET  /api/users/me  读取账号资料
 * - PUT  /api/users/me  更新账号资料
 */

export async function fetchCurrentUser() {
  const response = await api.get('/users/me')
  return response.data
}

export async function updateCurrentUser(payload) {
  const response = await api.put('/users/me', payload)
  return response.data
}
