import api from "./index";

export async function generateTutoringMultimodal(payload) {
  const response = await api.post("/tutoring/multimodal", payload);
  return response.data;
}

export async function askContextualTutor({
  question,
  context,
  learnerProfile,
  image,
  learningSessionId,
  contextType,
  contextKey,
  contextTitle,
  answerMode,
}) {
  const form = new FormData();
  form.append("question", question);
  if (context) form.append("context", context);
  if (learnerProfile) form.append("learnerProfile", learnerProfile);
  if (learningSessionId)
    form.append("learningSessionId", String(learningSessionId));
  if (contextType) form.append("contextType", contextType);
  if (contextKey) form.append("contextKey", contextKey);
  if (contextTitle) form.append("contextTitle", contextTitle);
  if (answerMode) form.append("answerMode", answerMode);
  if (image) form.append("image", image);
  const response = await api.post("/tutoring/ask", form, {
    timeout: 180_000,
    // Override the JSON default so Axios lets the browser attach the multipart boundary.
    headers: { "Content-Type": "multipart/form-data" },
  });
  return response.data;
}
