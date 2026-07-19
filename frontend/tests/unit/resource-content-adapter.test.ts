import { describe, expect, it } from "vitest";
import {
  isCurrentResourceContentEnvelope,
  parseResourceContentEnvelope,
} from "../../src/adapters/resource-content-adapter";

describe("parseResourceContentEnvelope", () => {
  it("accepts object and JSON representations", () => {
    expect(parseResourceContentEnvelope({ origin: "LIVE" })).toEqual({
      origin: "LIVE",
    });
    expect(parseResourceContentEnvelope('{"degraded":true}')).toEqual({
      degraded: true,
    });
  });

  it("recognizes only a complete versioned envelope", () => {
    expect(
      isCurrentResourceContentEnvelope({
        schema_version: "1.0",
        origin: "LIVE",
        generation_mode: "REACT_MULTI_AGENT",
        degraded: false,
        personalized: true,
        agent: "DocAgent",
      }),
    ).toBe(true);
    expect(isCurrentResourceContentEnvelope({ origin: "LIVE" })).toBe(false);
  });

  it("returns an empty envelope for absent or malformed values", () => {
    expect(parseResourceContentEnvelope(null)).toEqual({});
    expect(parseResourceContentEnvelope(42)).toEqual({});
    expect(parseResourceContentEnvelope("{invalid")).toEqual({});
    expect(parseResourceContentEnvelope("null")).toEqual({});
  });
});
