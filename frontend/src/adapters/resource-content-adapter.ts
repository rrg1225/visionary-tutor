export interface ResourceContentEnvelope {
  schema_version?: "1.0";
  origin?: string;
  generation_mode?: string;
  degraded?: boolean;
  personalized?: boolean;
  agent?: string;
  fallback_reason?: string;
  content_safety?: "PASSED" | "REVIEW_REQUIRED" | "BLOCKED";
  graph?: unknown;
  [key: string]: unknown;
}

export function isCurrentResourceContentEnvelope(
  value: ResourceContentEnvelope,
): boolean {
  return (
    value.schema_version === "1.0" &&
    ["LIVE", "DEGRADED", "DEMO"].includes(value.origin || "") &&
    typeof value.generation_mode === "string" &&
    typeof value.degraded === "boolean" &&
    typeof value.personalized === "boolean" &&
    typeof value.agent === "string"
  );
}

export function parseResourceContentEnvelope(
  value: unknown,
): ResourceContentEnvelope {
  if (!value) return {};
  if (typeof value === "object") return value as ResourceContentEnvelope;
  if (typeof value !== "string") return {};
  try {
    const parsed = JSON.parse(value);
    return parsed && typeof parsed === "object"
      ? (parsed as ResourceContentEnvelope)
      : {};
  } catch {
    return {};
  }
}
