import { describe, expect, it } from "vitest";
import { formatAssessmentError } from "../../src/composables/useAssessmentUpload";

describe("formatAssessmentError", () => {
  it("turns an Axios timeout into an actionable Chinese retry message", () => {
    const message = formatAssessmentError({
      code: "ECONNABORTED",
      message: "timeout of 30000ms exceeded",
    });
    expect(message).toContain("2 分钟");
    expect(message).toContain("已保留");
    expect(message).not.toContain("30000ms");
  });

  it("does not expose an English upstream error", () => {
    const message = formatAssessmentError({
      response: { status: 500, data: { message: "Internal server error" } },
    });
    expect(message).toContain("重新提交");
    expect(message).not.toContain("Internal server error");
  });
});
