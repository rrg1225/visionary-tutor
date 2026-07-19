import api from './index'

export async function extractLearnerProfile(payload) {
  const response = await api.post('/profile/extract', payload, {
    // 画像是回答后的后台增强；超时不能污染主聊天界面或全局错误提示。
    timeout: 20_000,
    silent: true,
  })
  return response.data
}

export async function validateOnboardingAnswer(payload) {
  const response = await api.post('/profile/validate-onboarding-answer', payload)
  return response.data
}
