export type ResourceOrigin = "LIVE" | "DEGRADED" | "DEMO" | "UNKNOWN";

export interface ResourceProvenance {
  origin?: string | null;
  isShowcase?: boolean | null;
  degraded?: boolean | null;
  publishStatus?: string | null;
  generationMode?: string | null;
  generationAgent?: string | null;
  fallbackReason?: string | null;
}

export interface ResourceOriginPresentation {
  origin: ResourceOrigin;
  label: string;
  tone: "live" | "degraded" | "demo" | "neutral";
  title: string;
}

export function resolveResourceOrigin(
  item: ResourceProvenance,
): ResourceOriginPresentation {
  if (item.isShowcase || item.origin === "DEMO") {
    return {
      origin: "DEMO",
      label: "示例数据",
      tone: "demo",
      title: "内置演示资源，不代表现场生成",
    };
  }

  if (
    item.degraded ||
    item.origin === "DEGRADED" ||
    item.publishStatus === "DEGRADED"
  ) {
    return {
      origin: "DEGRADED",
      label: "降级产物",
      tone: "degraded",
      title: item.fallbackReason || "主智能体路径不可用时生成的降级产物",
    };
  }

  if (item.origin === "LIVE") {
    const mode = item.generationMode || "在线智能体";
    const agent = item.generationAgent ? ` · ${item.generationAgent}` : "";
    return {
      origin: "LIVE",
      label: "真实生成",
      tone: "live",
      title: `生成路径：${mode}${agent}`,
    };
  }

  return {
    origin: "UNKNOWN",
    label: "来源待确认",
    tone: "neutral",
    title: "该资源尚未提供完整生成来源",
  };
}
