import { afterEach, describe, expect, it, vi } from "vitest";
import {
  acquireUserCameraStream,
  getCameraSupportIssue,
  normalizeCameraAccessError,
} from "../../src/utils/camera";

afterEach(() => {
  vi.restoreAllMocks();
  Object.defineProperty(window, "isSecureContext", {
    configurable: true,
    value: true,
  });
});

describe("camera access guard", () => {
  it("explains that production camera access requires HTTPS", () => {
    Object.defineProperty(window, "isSecureContext", {
      configurable: true,
      value: false,
    });

    expect(getCameraSupportIssue()).toMatchObject({
      code: "INSECURE_CONTEXT",
      retryable: false,
    });
    expect(getCameraSupportIssue()?.message).toContain("HTTPS");
  });

  it("does not retry a denied permission with a second getUserMedia request", async () => {
    Object.defineProperty(window, "isSecureContext", {
      configurable: true,
      value: true,
    });
    const denied = Object.assign(new Error("denied"), {
      name: "NotAllowedError",
    });
    const getUserMedia = vi.fn().mockRejectedValue(denied);
    Object.defineProperty(navigator, "mediaDevices", {
      configurable: true,
      value: { getUserMedia },
    });

    await expect(acquireUserCameraStream()).rejects.toMatchObject({
      code: "PERMISSION_DENIED",
    });
    expect(getUserMedia).toHaveBeenCalledTimes(1);
  });

  it("maps a busy camera to an actionable message", () => {
    const busy = Object.assign(new Error("track failed"), {
      name: "NotReadableError",
    });
    expect(normalizeCameraAccessError(busy)).toMatchObject({
      code: "DEVICE_BUSY",
    });
  });
});
