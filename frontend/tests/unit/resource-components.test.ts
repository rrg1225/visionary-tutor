import { mount } from "@vue/test-utils";
import { describe, expect, it } from "vitest";
import ResourceCardShell from "../../src/components/resource/ResourceCardShell.vue";
import ResourceActionBar from "../../src/components/resource/ResourceActionBar.vue";
import ResourceGenerationStatus from "../../src/components/resource/ResourceGenerationStatus.vue";
import ResourceOriginBadge from "../../src/components/resource/ResourceOriginBadge.vue";

describe("resource presentation components", () => {
  it("renders honest provenance labels", () => {
    const wrapper = mount(ResourceOriginBadge, {
      props: { item: { origin: "LIVE", generationAgent: "DocAgent" } },
    });
    expect(wrapper.text()).toContain("真实生成");
    expect(wrapper.attributes("title")).toContain("DocAgent");
  });

  it("renders normalized progress and machine-readable status", () => {
    const wrapper = mount(ResourceGenerationStatus, {
      props: { active: true, progress: 130, message: "进入 Critic 审查" },
    });
    expect(wrapper.get(".progress-track span").attributes("style")).toContain(
      "100%",
    );
    expect(wrapper.get(".generation-status").attributes("data-status")).toBe(
      "CRITIQUING",
    );
  });

  it("emits cancellation and retry requests without changing generation state", async () => {
    const wrapper = mount(ResourceGenerationStatus, {
      props: { active: true, progress: 40, retryable: true },
    });
    await wrapper.get("button").trigger("click");
    expect(wrapper.emitted("cancel")).toHaveLength(1);

    await wrapper.setProps({ active: false });
    await wrapper.get("button").trigger("click");
    expect(wrapper.emitted("retry")).toHaveLength(1);
  });

  it("keeps historical video styling and gives local animations a wide layout", () => {
    const wrapper = mount(ResourceCardShell, {
      props: { artifactType: "VIDEO_SCRIPT", variant: "fresh" },
      slots: { default: "<strong>resource</strong>" },
    });
    expect(wrapper.classes()).toEqual(
      expect.arrayContaining([
        "resource-card",
        "resource-card--video",
        "fresh-card",
      ]),
    );
    expect(wrapper.text()).toContain("resource");

    const animation = mount(ResourceCardShell, {
      props: { artifactType: "VISUALIZATION" },
    });
    expect(animation.classes()).toContain("resource-card--visualization");
  });

  it("emits narrow action contracts without owning resource business logic", async () => {
    const wrapper = mount(ResourceActionBar, {
      props: {
        canReadAloud: true,
        canExportPptx: true,
        showVisualization: true,
      },
    });
    const buttons = wrapper.findAll("button");
    await Promise.all(buttons.map((button) => button.trigger("click")));
    expect(wrapper.emitted("read-aloud")).toHaveLength(1);
    expect(wrapper.emitted("export-pptx")).toHaveLength(1);
    expect(wrapper.emitted("open-visualization")).toHaveLength(1);
  });

  it("discloses loading states and can be hidden", async () => {
    const wrapper = mount(ResourceActionBar, {
      props: {
        canReadAloud: true,
        canExportPptx: true,
        showVisualization: true,
        ttsSpeaking: true,
        pptxLoading: true,
        vizLoading: true,
      },
    });
    expect(wrapper.text()).not.toContain("视频生成中");
    expect(wrapper.text()).toContain("朗读中");
    expect(wrapper.text()).toContain("导出中");
    expect(wrapper.text()).toContain("加载中");
    await wrapper.setProps({ show: false });
    expect(wrapper.find(".resource-actions").exists()).toBe(false);
  });
});
