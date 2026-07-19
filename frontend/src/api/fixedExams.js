import api from "./index";

export async function listFixedExamPapers(options = {}) {
  const response = await api.get("/fixed-exams", { silent: options.silent });
  return response.data;
}

export async function getFixedExamPaper(paperCode, options = {}) {
  const response = await api.get(`/fixed-exams/${paperCode}`, {
    silent: options.silent,
  });
  return response.data;
}

export async function startFixedExamAttempt(paperCode, learningSessionId) {
  const response = await api.post(`/fixed-exams/${paperCode}/attempts`, {
    learningSessionId,
  });
  return response.data;
}

export async function getFixedExamAttempt(attemptId, options = {}) {
  const response = await api.get(`/fixed-exams/attempts/${attemptId}`, {
    silent: options.silent,
  });
  return response.data;
}

export async function saveFixedExamAnswer(attemptId, questionId, payload) {
  const response = await api.put(
    `/fixed-exams/attempts/${attemptId}/answers/${questionId}`,
    payload,
    { silent: true },
  );
  return response.data;
}

export async function revealFixedExamAnswer(attemptId, questionId) {
  const response = await api.post(
    `/fixed-exams/attempts/${attemptId}/answers/${questionId}/reveal`,
  );
  return response.data;
}

export async function submitFixedExamAttempt(attemptId) {
  const response = await api.post(`/fixed-exams/attempts/${attemptId}/submit`);
  return response.data;
}

export async function getFixedExamReport(attemptId, options = {}) {
  const response = await api.get(`/fixed-exams/attempts/${attemptId}/report`, {
    silent: options.silent,
  });
  return response.data;
}

export async function listFixedExamReports(options = {}) {
  const response = await api.get("/fixed-exams/reports", { silent: options.silent });
  return response.data;
}
