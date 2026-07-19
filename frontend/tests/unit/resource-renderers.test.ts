import { mount, shallowMount } from "@vue/test-utils";
import { describe, expect, it, vi } from "vitest";
import CodingResourceCard from "../../src/components/resource/cards/CodingResourceCard.vue";
import DocumentResourceCard from "../../src/components/resource/cards/DocumentResourceCard.vue";
import MindMapResourceCard from "../../src/components/resource/cards/MindMapResourceCard.vue";
import QuizResourceCard from "../../src/components/resource/cards/QuizResourceCard.vue";

vi.mock("../../src/composables/useTextToSpeech", () => ({
  useTextToSpeech: () => ({
    loadCloudAudio: vi.fn(async () => ({ src: "blob:audio" })),
    resolveTtsAudioUrl: vi.fn((path: string) => path),
  }),
}));

vi.mock("../../src/components/MermaidViewer.vue", () => ({
  default: {
    name: "MermaidViewer",
    template: "<div class='mermaid-stub'>diagram</div>",
  },
}));

const tutorStub = {
  name: "ContextualTutorPanel",
  template: "<div class='tutor-stub'>AI tutor</div>",
};

describe("typed resource renderers", () => {
  it("sanitizes and renders document markdown", () => {
    const wrapper = mount(DocumentResourceCard, {
      props: { content: "# CNN\n<script>alert(1)</script>" },
    });
    expect(wrapper.html()).toContain("CNN");
    expect(wrapper.html()).not.toContain("<script>");
  });

  it("captures selected reading context for the tutor", async () => {
    const wrapper = mount(DocumentResourceCard, {
      props: {
        title: "CNN 拓展阅读",
        content: "# CNN\n卷积核会在输入上滑动并提取局部特征。",
        enableTutor: true,
      },
      global: { stubs: { ContextualTutorPanel: tutorStub } },
    });
    const markdown = wrapper.get(".markdown-body");
    const selection = vi.spyOn(globalThis, "getSelection").mockReturnValue({
      anchorNode: markdown.element.firstChild,
      isCollapsed: false,
      toString: () => "卷积核会在输入上滑动",
    } as Selection);
    await markdown.trigger("mouseup");
    expect(wrapper.text()).toContain("已选中一段正文");
    expect(wrapper.text()).toContain("卷积核会在输入上滑动");
    selection.mockRestore();
  });

  it("renders each code sandbox completion state", () => {
    expect(
      mount(CodingResourceCard, {
        props: { content: "print(1)", loading: true },
      }).text(),
    ).toContain("沙箱执行中");
    expect(
      mount(CodingResourceCard, {
        props: { content: "print(1)", passed: true },
      }).text(),
    ).toContain("测试通过");
    const failed = mount(CodingResourceCard, {
      props: {
        content: "print(1)",
        failed: true,
        statusLabel: "执行超时",
        errorLog: "timeout",
        executionTimeMs: 5000,
      },
    });
    expect(failed.text()).toContain("执行超时");
    expect(failed.text()).toContain("5000 ms");
    expect(
      mount(CodingResourceCard, {
        props: { content: "x", unavailable: true },
      }).text(),
    ).toContain("暂不可用");
  });

  it("supports code execution, stop, reset, versions, assertions and indentation", async () => {
    const wrapper = mount(CodingResourceCard, {
      props: {
        content: "# 累加练习\nprint(1)",
        passed: true,
        outputLog: "2",
      },
      global: { stubs: { ContextualTutorPanel: tutorStub } },
    });
    const editor = wrapper.get<HTMLTextAreaElement>(".code-editor");
    const buttons = wrapper.findAll("button");
    expect(wrapper.text()).toContain("累加练习");

    await editor.setValue("# 累加练习\nprint(2)");
    await buttons[0].trigger("click");
    expect(wrapper.emitted("execute")?.[0]).toEqual(["# 累加练习\nprint(2)"]);

    await editor.setValue("# 累加练习\nprint(3)");
    await buttons[3].trigger("click");
    expect(wrapper.findAll("select option")).toHaveLength(4);
    await wrapper.get("select").setValue("initial");
    expect(editor.element.value).toBe("# 累加练习\nprint(1)");

    await editor.setValue("changed");
    await buttons[2].trigger("click");
    expect(editor.element.value).toBe("# 累加练习\nprint(1)");

    editor.element.selectionStart = 0;
    editor.element.selectionEnd = 0;
    await editor.trigger("keydown", { key: "Tab" });
    expect(editor.element.value.startsWith("    ")).toBe(true);

    const expected = wrapper.findAll("textarea")[1];
    await expected.setValue("2");
    expect(wrapper.text()).toContain("测试通过：输出符合预期");
    await expected.setValue("3");
    expect(wrapper.text()).toContain("未通过：预期与实际不一致");

    await wrapper.get(".tutor-button").trigger("click");
    expect(wrapper.find(".tutor-stub").exists()).toBe(true);

    const running = mount(CodingResourceCard, {
      props: { content: "while True: pass", loading: true },
    });
    await running.findAll("button")[1].trigger("click");
    expect(running.emitted("stop")).toHaveLength(1);
  });

  it("delegates mind map and quiz content to specialized widgets", () => {
    const mindMap = shallowMount(MindMapResourceCard, {
      props: { content: "mindmap\n root" },
    });
    expect(mindMap.html()).toContain("mindmap");

    const quiz = shallowMount(QuizResourceCard, {
      props: { content: "[]", userId: 1, learningSessionId: 2 },
    });
    expect(quiz.findComponent({ name: "QuizInteractive" }).exists()).toBe(true);
  });
});
