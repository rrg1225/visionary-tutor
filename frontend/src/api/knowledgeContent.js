import api from "./index";

export async function listSystemKnowledgeContent(options = {}) {
  const response = await api.get("/knowledge-content", {
    silent: options.silent,
  });
  return response.data;
}

export async function getSystemKnowledgeContent(slug, options = {}) {
  const response = await api.get(`/knowledge-content/${encodeURIComponent(slug)}`, {
    silent: options.silent,
  });
  return response.data;
}
