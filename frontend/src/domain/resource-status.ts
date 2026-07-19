export type GenerationStatus =
  | "IDLE"
  | "PLANNING"
  | "RETRIEVING"
  | "GENERATING"
  | "CRITIQUING"
  | "PERSISTING"
  | "SUCCEEDED"
  | "DEGRADED"
  | "FAILED";

export function normalizeProgress(progress: unknown): number {
  const numeric = Number(progress);
  if (!Number.isFinite(numeric)) return 0;
  return Math.max(0, Math.min(100, numeric));
}

export function inferGenerationStatus(
  active: boolean,
  progress: unknown,
  message = "",
): GenerationStatus {
  if (!active) return normalizeProgress(progress) >= 100 ? "SUCCEEDED" : "IDLE";
  if (/失败|异常/.test(message)) return "FAILED";
  if (/降级|fallback/i.test(message)) return "DEGRADED";
  if (/审查|critic|复核/i.test(message)) return "CRITIQUING";
  if (/检索|rag/i.test(message)) return "RETRIEVING";
  if (/规划|planner/i.test(message)) return "PLANNING";
  if (/保存|持久化/.test(message)) return "PERSISTING";
  return "GENERATING";
}
