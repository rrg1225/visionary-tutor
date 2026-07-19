import { flushPromises, shallowMount } from "@vue/test-utils";
import { beforeEach, describe, expect, it, vi } from "vitest";
import ScopedResourceWorkspace from "../../src/components/resource/ScopedResourceWorkspace.vue";

const mocks = vi.hoisted(() => ({
  authStore: {
    isRegistered: true,
    currentUserId: 7,
  },
  userProfile: {
    weakPoints: ["卷积尺寸"],
  },
  learningSession: {
    isGeneratingResources: false,
    resourceGenerationProgress: 0,
    resourceGenerationStatus: "",
    resourceGenerationEtaSeconds: 0,
    resourceGenerationRetryable: false,
    currentSessionId: 12,
    weakNodes: [{ name: "Padding" }],
    ensureCurrentSession: vi.fn(async () => ({ id: 12 })),
    hydrateGeneratedResources: vi.fn(async () => []),
    resumeResourceGeneration: vi.fn(async () => false),
    retryResourceGeneration: vi.fn(async () => true),
    cancelResourceGeneration: vi.fn(async () => undefined),
  },
  libraryResources: {
    value: [
      {
        id: 3,
        artifactType: "HANDOUT",
        title: "卷积复习讲义",
        learningSessionId: 12,
      },
      {
        id: 4,
        artifactType: "QUIZ",
        title: "不应出现在资料页",
        learningSessionId: 12,
      },
    ],
  },
  loading: { __v_isRef: true, value: false },
  loadError: { __v_isRef: true, value: "" },
  loadLibrary: vi.fn(async () => []),
  generateResources: vi.fn(async () => true),
  refreshRecommendations: vi.fn(async () => undefined),
}));

vi.mock("vue-router", async () => {
  const actual =
    await vi.importActual<typeof import("vue-router")>("vue-router");
  return {
    ...actual,
    useRoute: () => ({ fullPath: "/learning-materials", query: {} }),
  };
});

vi.mock("../../src/stores/authStore", () => ({
  useAuthStore: () => mocks.authStore,
}));

vi.mock("../../src/stores/userProfile", () => ({
  useUserProfileStore: () => mocks.userProfile,
}));

vi.mock("../../src/stores/learningSession", () => ({
  useLearningSessionStore: () => mocks.learningSession,
}));

vi.mock("../../src/composables/useResourceLibrary", () => ({
  useResourceLibrary: () => ({
    libraryResources: mocks.libraryResources,
    loading: mocks.loading,
    loadError: mocks.loadError,
    loadLibrary: mocks.loadLibrary,
  }),
}));

vi.mock("../../src/composables/useResourceGeneration", () => ({
  useResourceGeneration: () => ({
    defaultResourceTopic: { value: "CNN" },
    generateResources: mocks.generateResources,
    refreshRecommendations: mocks.refreshRecommendations,
  }),
}));

const props = {
  workspaceId: "materials",
  title: "我的学习资料",
  description: "只展示讲义与阅读",
  allowedTypes: ["HANDOUT", "EXTENDED_READING"],
  sessionTopic: "学习资料",
};

function mountWorkspace() {
  return shallowMount(ScopedResourceWorkspace, {
    props,
    global: {
      stubs: {
        RouterLink: {
          props: ["to"],
          template: "<a><slot /></a>",
        },
      },
    },
  });
}

describe("scoped resource workspace", () => {
  beforeEach(() => {
    mocks.authStore.isRegistered = true;
    mocks.learningSession.isGeneratingResources = false;
    mocks.learningSession.resourceGenerationRetryable = false;
    mocks.learningSession.currentSessionId = 12;
    mocks.learningSession.lastResourceRunId = null;
    mocks.loadError.value = "";
    mocks.libraryResources.value = [
      {
        id: 3,
        artifactType: "HANDOUT",
        title: "卷积复习讲义",
        learningSessionId: 12,
      },
      {
        id: 4,
        artifactType: "QUIZ",
        title: "不应出现在资料页",
        learningSessionId: 12,
      },
    ];
    vi.clearAllMocks();
  });

  it("limits the page to its allowed resource types and restores the session", async () => {
    const wrapper = mountWorkspace();
    await flushPromises();

    expect(wrapper.text()).toContain("本页不会生成其他资源类型");
    expect(wrapper.text()).toContain("讲义");
    expect(wrapper.text()).toContain("阅读");
    expect(wrapper.text()).not.toContain("不应出现在资料页");
    expect(mocks.learningSession.ensureCurrentSession).toHaveBeenCalledWith(
      "学习资料",
    );
    expect(mocks.loadLibrary).toHaveBeenCalledWith(7);
  });

  it("generates exactly one selected task type and refreshes the library", async () => {
    const wrapper = mountWorkspace();
    await flushPromises();

    await wrapper.get("textarea").setValue("卷积输出尺寸");
    const radios = wrapper.findAll('input[type="radio"]');
    await radios[1].setValue();
    const generateButton = wrapper
      .findAll("button")
      .find((button) => button.text().includes("生成阅读"));
    expect(generateButton).toBeDefined();
    await generateButton!.trigger("click");
    await flushPromises();

    expect(mocks.generateResources).toHaveBeenCalledWith(
      {
        topic: "卷积输出尺寸",
        resourceTypes: ["EXTENDED_READING"],
      },
      { redirectToLibrary: false, startNewSession: true },
    );
    expect(mocks.loadLibrary).toHaveBeenCalled();
  });

  it("shows a login gate without starting a learning session", async () => {
    mocks.authStore.isRegistered = false;
    const wrapper = mountWorkspace();
    await flushPromises();

    expect(wrapper.text()).toContain("登录后创建并保存学习资源");
    expect(wrapper.find("textarea").exists()).toBe(false);
    expect(mocks.learningSession.ensureCurrentSession).not.toHaveBeenCalled();
  });

  it("does not count showcase content as the learner's generated history", async () => {
    mocks.libraryResources.value = [
      {
        id: 99,
        artifactType: "HANDOUT",
        title: "示例讲义",
        learningSessionId: 1,
        isShowcase: true,
      },
    ];
    const wrapper = mountWorkspace();
    await flushPromises();

    expect(wrapper.text()).toContain("0 次生成 · 1 项示例");
    expect(wrapper.text()).not.toContain("1 次生成");
  });

  it("shows a retryable notice when personal resources fail to sync", async () => {
    mocks.loadError.value = "个人资源同步失败，当前仅显示示例资源。";
    const wrapper = mountWorkspace();
    await flushPromises();

    expect(wrapper.text()).toContain("个人资源没有完全同步");
    expect(wrapper.text()).toContain("个人资源同步失败");
    expect(wrapper.text()).toContain("重试");
  });

  it("keeps independent resource buttons strictly scoped", async () => {
    const wrapper = shallowMount(ScopedResourceWorkspace, {
      props: {
        ...props,
        allowedTypes: ["VISUALIZATION", "CODE_PRACTICE"],
        typeLabels: {
          VISUALIZATION: "动画实验",
          CODE_PRACTICE: "代码实验",
        },
        allowMultiSelect: true,
        independentTypeActions: true,
      },
      global: {
        stubs: { RouterLink: { props: ["to"], template: "<a><slot /></a>" } },
      },
    });
    await flushPromises();
    await wrapper.get("textarea").setValue("卷积步长");

    const codeButton = wrapper
      .findAll("button")
      .find((button) => button.text().trim() === "生成代码实验");
    await codeButton!.trigger("click");
    await flushPromises();

    expect(mocks.generateResources).toHaveBeenCalledWith(
      { topic: "卷积步长", resourceTypes: ["CODE_PRACTICE"] },
      { redirectToLibrary: false, startNewSession: true },
    );
  });

  it("supports multi-resource generation and manages searchable history", async () => {
    localStorage.clear();
    const prompt = vi.spyOn(window, "prompt").mockReturnValue("重命名讲义");
    const confirm = vi.spyOn(window, "confirm").mockReturnValue(true);
    const wrapper = shallowMount(ScopedResourceWorkspace, {
      props: {
        ...props,
        allowedTypes: ["HANDOUT", "QUIZ"],
        allowMultiSelect: true,
        defaultSelectAll: true,
        historyEnabled: true,
      },
      global: {
        stubs: { RouterLink: { props: ["to"], template: "<a><slot /></a>" } },
      },
    });
    await flushPromises();

    await wrapper.get("textarea").setValue("卷积综合复习");
    const generate = wrapper
      .findAll("button")
      .find((button) => button.text().includes("生成讲义 + 题库"));
    await generate!.trigger("click");
    await flushPromises();
    expect(mocks.generateResources).toHaveBeenCalledWith(
      { topic: "卷积综合复习", resourceTypes: ["HANDOUT", "QUIZ"] },
      { redirectToLibrary: false, startNewSession: true },
    );

    expect(wrapper.text()).toContain("卷积复习讲义");
    const search = wrapper.get('input[type="search"]');
    await search.setValue("卷积复习");
    expect(wrapper.text()).toContain("卷积复习讲义");
    const historyButtons = wrapper.findAll(".history-actions button");
    await historyButtons[0].trigger("click");
    await historyButtons[1].trigger("click");
    expect(prompt).toHaveBeenCalled();
    expect(
      localStorage.getItem("vt_resource_history_materials:aliases"),
    ).toContain("重命名讲义");
    await search.setValue("");
    await wrapper.findAll(".history-actions button")[2].trigger("click");
    expect(confirm).toHaveBeenCalled();
    expect(
      localStorage.getItem("vt_resource_history_materials:hidden"),
    ).not.toBeNull();
    expect(wrapper.findAll(".history-list li")).toHaveLength(1);

    const advanced = wrapper
      .findAll("button")
      .find((button) => button.text().includes("高级模式"));
    await advanced!.trigger("click");
    expect(localStorage.getItem("vt_advanced_mode")).toBe("true");
  });
});
