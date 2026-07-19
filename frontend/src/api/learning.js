import api from "./index";

export async function listLearningSessions(userId, options = {}) {
  const response = await api.get("/learning-sessions", {
    params: { userId },
    silent: options.silent,
  });
  return response.data;
}

export async function listLearningSessionSummaries(userId, options = {}) {
  const response = await api.get("/learning-sessions/summaries", {
    params: { userId },
    silent: options.silent,
  });
  return response.data;
}

export async function createLearningSession(payload, options = {}) {
  const response = await api.post("/learning-sessions", payload, {
    silent: options.silent,
  });
  return response.data;
}

export async function startNewLearningSession(
  topic = "个性化学习会话",
  options = {},
) {
  const response = await api.post(
    "/learning-sessions/new",
    { topic },
    { silent: options.silent },
  );
  return response.data;
}

export async function activateLearningSession(id, options = {}) {
  const response = await api.post(`/learning-sessions/${id}/activate`, null, {
    silent: options.silent,
  });
  return response.data;
}

export async function updateLearningSession(id, payload) {
  const response = await api.put(`/learning-sessions/${id}`, payload);
  return response.data;
}

export async function deleteLearningSession(id) {
  await api.delete(`/learning-sessions/${id}`);
}

export async function listSessionChatMessages(sessionId, options = {}) {
  const response = await api.get(`/learning-sessions/${sessionId}/messages`, {
    params: {
      contextType: options.contextType,
      contextKey: options.contextKey,
    },
    silent: options.silent,
  });
  return response.data;
}

export async function appendSessionChatMessage(
  sessionId,
  payload,
  options = {},
) {
  const response = await api.post(
    `/learning-sessions/${sessionId}/messages`,
    payload,
    {
      silent: options.silent,
    },
  );
  return response.data;
}

export async function listDiagnosticReports(learningSessionId) {
  const response = await api.get("/diagnostic-reports", {
    params: { learningSessionId },
  });
  return response.data;
}

export async function createDiagnosticReport(payload) {
  const response = await api.post("/diagnostic-reports", payload);
  return response.data;
}
