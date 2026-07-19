import { describe, expect, it } from "vitest";
import { serializeProfileSnapshot } from "../../src/utils/profileSnapshot";
import { RESOURCE_TYPE_OPTIONS } from "../../src/constants/resourceTypes";

describe("AI teacher preferences and resource migration", () => {
  it("embeds answer preferences into the learner snapshot", () => {
    const value = serializeProfileSnapshot(
      { goal: { value: "理解 CNN" } },
      null,
      { tone: "简洁直接", detail: "精简", structure: "先结论后步骤" },
    );
    const parsed = JSON.parse(value);
    expect(parsed.goal.value).toBe("理解 CNN");
    expect(parsed.aiTeacherPreferences.tone).toBe("简洁直接");
  });

  it("does not offer cloud video script generation anymore", () => {
    expect(
      RESOURCE_TYPE_OPTIONS.some((item) => item.type === "VIDEO_SCRIPT"),
    ).toBe(false);
    expect(
      RESOURCE_TYPE_OPTIONS.find((item) => item.type === "VISUALIZATION")
        ?.label,
    ).toBe("动画讲解");
  });
});
