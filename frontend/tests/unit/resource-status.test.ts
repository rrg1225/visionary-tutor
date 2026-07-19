import { describe, expect, it } from "vitest";
import {
  inferGenerationStatus,
  normalizeProgress,
} from "../../src/domain/resource-status";

describe("resource generation status", () => {
  it("clamps invalid progress values", () => {
    expect(normalizeProgress(-10)).toBe(0);
    expect(normalizeProgress(120)).toBe(100);
    expect(normalizeProgress("invalid")).toBe(0);
  });

  it("maps observable progress messages to domain states", () => {
    expect(inferGenerationStatus(true, 10, "Planner 动态规划完成")).toBe(
      "PLANNING",
    );
    expect(inferGenerationStatus(true, 60, "进入 Critic 审查")).toBe(
      "CRITIQUING",
    );
    expect(inferGenerationStatus(true, 90, "fallback 到 Legacy")).toBe(
      "DEGRADED",
    );
    expect(inferGenerationStatus(true, 20, "RAG 检索中")).toBe("RETRIEVING");
    expect(inferGenerationStatus(true, 90, "正在持久化保存")).toBe(
      "PERSISTING",
    );
    expect(inferGenerationStatus(true, 50, "多智能体生成中")).toBe(
      "GENERATING",
    );
    expect(inferGenerationStatus(true, 50, "执行异常")).toBe("FAILED");
    expect(inferGenerationStatus(false, 30, "")).toBe("IDLE");
    expect(inferGenerationStatus(false, 100, "")).toBe("SUCCEEDED");
  });
});
