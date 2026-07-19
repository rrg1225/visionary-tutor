import api from './index'

/** 保存一次学习状态辅助报告（仅聚合指标，原始视频不上传）。 */
export async function createLearningStateReport(payload) {
  const response = await api.post('/learning-state-reports', payload, { silent: true })
  return response.data
}

/**
 * 列出我的状态报告；传 contextType/contextKey 时按学习场景过滤
 * （例如某套题卷的历史观察记录）。
 */
export async function listLearningStateReports(params = {}) {
  const response = await api.get('/learning-state-reports', { params, silent: true })
  return response.data
}
