import { describe, expect, it } from "vitest";
import { resolveResourceOrigin } from "../../src/domain/resource-origin";

describe("resolveResourceOrigin", () => {
  it("never presents demo content as live generation", () => {
    expect(
      resolveResourceOrigin({ isShowcase: true, origin: "LIVE" }),
    ).toMatchObject({
      origin: "DEMO",
      label: "示例数据",
    });
  });

  it("prioritizes degraded disclosure over live metadata", () => {
    expect(
      resolveResourceOrigin({
        origin: "LIVE",
        degraded: true,
        fallbackReason: "timeout",
      }),
    ).toMatchObject({
      origin: "DEGRADED",
      title: "timeout",
    });
  });

  it("returns an explicit unknown state when provenance is absent", () => {
    expect(resolveResourceOrigin({})).toMatchObject({
      origin: "UNKNOWN",
      label: "来源待确认",
    });
  });
});
