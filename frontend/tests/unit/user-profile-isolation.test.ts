import { createPinia, setActivePinia } from "pinia";
import { beforeEach, describe, expect, it, vi } from "vitest";

const authState = {
  isRegistered: true,
  isGuest: false,
  currentUserId: 1,
  user: { id: 1, email: "one@example.com" },
  displayName: "账号一",
};

vi.mock("../../src/stores/authStore", () => ({
  useAuthStore: () => authState,
}));
vi.mock("../../src/api/profile", () => ({ extractLearnerProfile: vi.fn() }));
vi.mock("../../src/api/learner", () => ({ fetchLearnerState: vi.fn() }));

import {
  resolveProfileStorageKey,
  useUserProfileStore,
} from "../../src/stores/userProfile";

describe("user profile account isolation", () => {
  beforeEach(() => {
    localStorage.clear();
    setActivePinia(createPinia());
    Object.assign(authState, {
      isRegistered: true,
      isGuest: false,
      currentUserId: 1,
      user: { id: 1, email: "one@example.com" },
      displayName: "账号一",
    });
  });

  it("uses a different cache key for every registered and guest identity", () => {
    expect(resolveProfileStorageKey(1, false)).not.toBe(
      resolveProfileStorageKey(2, false),
    );
    expect(resolveProfileStorageKey("guest-a", true)).not.toBe(
      resolveProfileStorageKey("guest-b", true),
    );
  });

  it("resets in-memory onboarding data before loading another account", () => {
    localStorage.setItem(
      resolveProfileStorageKey(1, false),
      JSON.stringify({
        goal: "账号一的目标",
        onboardingComplete: true,
        isComplete: true,
      }),
    );

    const profile = useUserProfileStore();
    profile.hydrateFromStorage();
    expect(profile.goal).toBe("账号一的目标");
    expect(profile.onboardingComplete).toBe(true);

    Object.assign(authState, {
      currentUserId: 2,
      user: { id: 2, email: "two@example.com" },
      displayName: "账号二",
    });
    profile.hydrateFromStorage();

    expect(profile.goal).toBe("");
    expect(profile.onboardingComplete).toBe(false);
    expect(profile.profileSnapshot).toBeNull();
  });

  it("never assigns the old unscoped profile cache to a newly logged-in account", () => {
    localStorage.setItem(
      "visionary-tutor-user-profile",
      JSON.stringify({
        goal: "旧版共享缓存",
        onboardingComplete: true,
      }),
    );

    const profile = useUserProfileStore();
    profile.hydrateFromStorage();

    expect(profile.goal).toBe("");
    expect(profile.onboardingComplete).toBe(false);
  });
});
