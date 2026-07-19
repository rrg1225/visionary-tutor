import api from "./index";
import { parseSseBuffer } from "./stream";
import { refreshToken } from "./auth.js";
import { useAuthStore } from "../stores/authStore.js";
import { toastError, toastSuccess, toastWarning } from "../utils/toast";

const API_BASE = import.meta.env.VITE_API_BASE_URL || "/api";

function authHeaders() {
  const headers = { "Content-Type": "application/json" };
  const token = localStorage.getItem("vt_token");
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }
  const guestId = localStorage.getItem("vt_guest_id");
  if (guestId) {
    headers["X-Guest-Id"] = guestId;
  }
  return headers;
}

function parseErrorPayload(text) {
  if (!text) return null;
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

function formatHttpError(payload, fallback = "请求失败") {
  if (!payload) return fallback;
  if (typeof payload === "string") {
    const parsed = parseErrorPayload(payload);
    return parsed
      ? formatHttpError(parsed, fallback)
      : payload.length > 160
        ? fallback
        : payload;
  }
  if (
    payload.message &&
    typeof payload.message === "string" &&
    !payload.message.startsWith("{")
  ) {
    return payload.message;
  }
  if (payload.status === 403) return "访问被拒绝，请重新登录或刷新页面后重试";
  if (payload.status === 401) return "登录已过期，请重新登录后重试";
  if (payload.error) return payload.error;
  return fallback;
}

export function formatApiErrorMessage(error, fallback = "请求失败") {
  const payload = typeof error === "string" ? parseErrorPayload(error) : null;
  if (payload) return formatHttpError(payload, fallback);
  if (error?.visionary?.message) return error.visionary.message;
  if (error?.response?.status === 403)
    return "当前账号暂无访问权限，请刷新登录状态后重试";
  if (error?.response?.status === 401) return "登录已过期，请重新登录后重试";
  if (error?.response?.status === 404) return "请求的内容不存在或尚未发布";
  if (error?.response?.status >= 500) return "服务暂时不可用，请稍后重试";
  if (typeof error?.message === "string") {
    const parsed = parseErrorPayload(error.message);
    if (parsed) return formatHttpError(parsed, fallback);
    if (error.message.length > 160 && error.message.includes('"status"')) {
      return formatHttpError(
        parsed ?? parseErrorPayload(error.message),
        fallback,
      );
    }
    return error.message;
  }
  return fallback;
}

async function parseAxiosBlobError(error) {
  const data = error.response?.data;
  if (data instanceof Blob) {
    try {
      const text = await data.text();
      return formatHttpError(
        parseErrorPayload(text),
        `下载失败 (${error.response?.status || 0})`,
      );
    } catch {
      return formatHttpError(null, `下载失败 (${error.response?.status || 0})`);
    }
  }
  return formatApiErrorMessage(
    error,
    `下载失败 (${error.response?.status || 0})`,
  );
}

async function refreshAuthForDownload() {
  const authStore = useAuthStore();
  const currentToken = localStorage.getItem("vt_token");

  if (currentToken) {
    try {
      const response = await refreshToken(currentToken);
      if (response?.token) {
        localStorage.setItem("vt_token", response.token);
        authStore.token = response.token;
        return;
      }
    } catch {
      // refresh failed — recreate guest session below
    }
  }

  authStore.clearAuth();
  const ok = await authStore.createGuestSessionIfNeeded({ retries: 2 });
  if (!ok) {
    throw new Error("无法建立登录状态，请刷新页面或重新登录");
  }
}

async function ensureDownloadAuth() {
  if (localStorage.getItem("vt_token")) return;
  await refreshAuthForDownload();
}

/**
 * 经 axios 下载二进制（自动带 JWT、401 刷新、403 重试）。
 */
async function downloadBinaryViaApi(path, { retryAuth = true } = {}) {
  await ensureDownloadAuth();
  try {
    const response = await api.get(path, {
      responseType: "blob",
      silent: true,
    });
    const blob = response.data;
    if (blob?.type?.includes("application/json")) {
      const text = await blob.text();
      throw new Error(formatHttpError(parseErrorPayload(text), "下载失败"));
    }
    if (!blob?.size) {
      throw new Error("文件为空，请稍后重试");
    }
    return { blob, headers: response.headers };
  } catch (error) {
    const status = error.response?.status;
    if (retryAuth && (status === 401 || status === 403)) {
      await refreshAuthForDownload();
      return downloadBinaryViaApi(path, { retryAuth: false });
    }
    throw new Error(await parseAxiosBlobError(error));
  }
}

export async function fetchResourceRecommendations(params, options = {}) {
  const response = await api.get("/resources/recommendations", {
    params,
    silent: options.silent,
  });
  return response.data;
}

export async function listGeneratedResources(learningSessionId, options = {}) {
  const response = await api.get("/resources", {
    params: { learningSessionId },
    silent: options.silent,
  });
  return response.data;
}

/** 内置 CNN 示例资源（后端空库时自动注入） */
export async function listShowcaseResources(options = {}) {
  const response = await api.get("/resources/showcase", {
    silent: options.silent,
  });
  return response.data;
}

export async function fetchGovernanceTrace(artifactId) {
  const response = await api.get(
    `/v1/resources/${artifactId}/governance-trace`,
    { silent: true },
  );
  return response.data;
}

export async function generateResources(payload) {
  const response = await api.post(
    "/resources/generate",
    withGenerationRequestId(payload),
  );
  return response.data;
}

export async function startResourceGenerationJob(payload) {
  const response = await api.post(
    "/resources/generate/jobs",
    withGenerationRequestId(payload),
  );
  return response.data;
}

export async function getResourceGenerationJob(taskId) {
  const response = await api.get(`/resources/generate/jobs/${taskId}`);
  return response.data;
}

export async function cancelResourceGenerationJob(taskId) {
  const response = await api.delete(`/resources/generate/jobs/${taskId}`);
  return response.data;
}

export async function retryResourceGenerationJob(taskId) {
  const response = await api.post(`/resources/generate/jobs/${taskId}/retry`);
  return response.data;
}

/**
 * POST /api/resources/generate/stream — SSE progress (agent_step / workflow / complete).
 */
export async function streamResourceGeneration({ payload, onEvent, signal }) {
  const response = await fetch(`${API_BASE}/resources/generate/stream`, {
    method: "POST",
    headers: authHeaders(),
    body: JSON.stringify(withGenerationRequestId(payload)),
    signal,
  });

  if (!response.ok) {
    const text = await response.text().catch(() => "");
    throw new Error(
      formatHttpError(
        parseErrorPayload(text),
        `资源生成流失败 (${response.status})`,
      ),
    );
  }

  const reader = response.body?.getReader();
  if (!reader) {
    throw new Error("ReadableStream not supported");
  }

  const decoder = new TextDecoder();
  let buffer = "";

  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    buffer = parseSseBuffer(buffer, onEvent);
  }

  if (buffer.trim()) {
    parseSseBuffer(`${buffer}\n\n`, onEvent);
  }
}

function withGenerationRequestId(payload = {}) {
  if (payload.requestId) return payload;
  const requestId =
    globalThis.crypto?.randomUUID?.() ||
    `generation-${Date.now()}-${Math.random().toString(16).slice(2)}`;
  return { ...payload, requestId };
}

export async function assessLearning(userId, learningSessionId) {
  const response = await api.post("/resources/learning/assess", null, {
    params: {
      userId,
      learningSessionId,
    },
  });
  return response.data;
}

export async function fetchKnowledgeTracingRadar(userId) {
  const response = await api.get("/resources/knowledge-tracing/radar", {
    params: { userId },
  });
  return response.data;
}

export async function submitQuizResult(payload) {
  const response = await api.post("/resources/quiz/submit", payload);
  return response.data;
}

export async function recordLearningMetric(payload) {
  const response = await api.post("/resources/metrics/record", payload);
  return response.data;
}

/** 记录会话前测基线（首次进入学习或生成资源前调用） */
export async function recordPreTest(
  userId,
  learningSessionId,
  concept = "综合练习",
  scorePercent = 50,
) {
  const response = await api.post("/resources/learning/pre-test", {
    userId,
    learningSessionId,
    concept,
    scorePercent,
  });
  return response.data;
}

export async function recordPostTest(
  userId,
  learningSessionId,
  concept = "general",
  scorePercent = 80,
) {
  const response = await api.post("/resources/learning/post-test", {
    userId,
    learningSessionId,
    concept,
    scorePercent,
  });
  return response.data;
}

export async function fetchLearningEffectExperiment(
  userId,
  learningSessionId,
  options = {},
) {
  const response = await api.get("/resources/learning/effect-experiment", {
    params: { userId, learningSessionId },
    silent: options.silent,
  });
  return response.data;
}

/** 下载单个资源产物 PPTX */
export async function downloadArtifactPptx(artifactId) {
  if (!artifactId || String(artifactId).match(/^[A-Z_]+$/)) {
    throw new Error("资源 ID 无效，请刷新页面后重试");
  }
  const { blob } = await downloadBinaryViaApi(
    `/resources/${artifactId}/export/pptx`,
  );
  return blob;
}

/**
 * 下载学习会话 PPTX（支持 standard / premium）。
 * 解析 X-Pptx-Export-Mode 响应头；premium 降级时弹 Toast 安抚。
 */
export async function downloadSessionPptx(
  sessionId,
  quality = "standard",
  loadingRef,
) {
  if (loadingRef) loadingRef.value = true;
  try {
    const { blob, headers } = await downloadBinaryViaApi(
      `/resources/session/${sessionId}/export/pptx?quality=${quality}`,
    );
    const exportMode =
      headers?.["x-pptx-export-mode"] ||
      headers?.["X-Pptx-Export-Mode"] ||
      "standard-fallback";
    const filename = `visionary-session-${sessionId}-${quality}.pptx`;

    const link = document.createElement("a");
    link.href = URL.createObjectURL(blob);
    link.download = filename;
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    URL.revokeObjectURL(link.href);

    if (quality === "premium" && exportMode === "standard-fallback") {
      toastWarning("精美版生成排队中超时，已为您自动切换为标准版导出", 4500);
    } else if (exportMode === "premium") {
      toastSuccess("精美版 PPTX 已导出");
    } else {
      toastSuccess("PPTX 已导出");
    }
  } catch (err) {
    toastError(`PPTX 导出失败：${formatApiErrorMessage(err, "网络错误")}`);
    console.error("[downloadSessionPptx] error:", err);
  } finally {
    if (loadingRef) loadingRef.value = false;
  }
}

/** 按轻量幻灯片编辑器中的标题/正文重新导出 PPTX。 */
export async function downloadEditedSessionPptx(
  sessionId,
  payload,
  { retryAuth = true } = {},
) {
  await ensureDownloadAuth();
  try {
    const response = await api.post(
      `/resources/session/${sessionId}/export/pptx/edited`,
      payload,
      { responseType: "blob", silent: true },
    );
    const blob = response.data;
    if (blob?.type?.includes("application/json")) {
      throw new Error(
        formatHttpError(
          parseErrorPayload(await blob.text()),
          "编辑版 PPTX 导出失败",
        ),
      );
    }
    if (!blob?.size) throw new Error("导出文件为空，请稍后重试");
    return blob;
  } catch (error) {
    const status = error.response?.status;
    if (retryAuth && (status === 401 || status === 403)) {
      await refreshAuthForDownload();
      return downloadEditedSessionPptx(sessionId, payload, {
        retryAuth: false,
      });
    }
    throw new Error(await parseAxiosBlobError(error));
  }
}
