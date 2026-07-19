import { mount } from "@vue/test-utils";
import { afterEach, describe, expect, it, vi } from "vitest";
import CnnConvolutionLab from "../../src/components/resource/cards/CnnConvolutionLab.vue";

describe("CnnConvolutionLab", () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it("renders the formula, matrices and the first convolution result", () => {
    const wrapper = mount(CnnConvolutionLab);
    expect(wrapper.text()).toContain("CNN 卷积滑窗实验");
    expect(wrapper.text()).toContain("= 5");
    expect(wrapper.findAll(".input-matrix .cell")).toHaveLength(49);
    expect(wrapper.findAll(".output-matrix .cell")).toHaveLength(25);
    expect(wrapper.findAll(".input-matrix .cell.active")).toHaveLength(9);
    expect(wrapper.findAll(".output-matrix .cell.revealed")).toHaveLength(1);
  });

  it("changes padding and stride and advances the active window", async () => {
    const wrapper = mount(CnnConvolutionLab);
    const ranges = wrapper.findAll<HTMLInputElement>('input[type="range"]');

    await ranges[0].setValue(0);
    await ranges[1].setValue(2);
    expect(wrapper.text()).toContain("= 2");
    expect(wrapper.findAll(".input-matrix .cell")).toHaveLength(25);
    expect(wrapper.findAll(".output-matrix .cell")).toHaveLength(4);

    await wrapper.find("button.vt-btn-primary").trigger("click");
    expect(wrapper.findAll(".output-matrix .cell.revealed")).toHaveLength(2);
    expect(wrapper.text()).toContain("当前位置输出");

    await wrapper.findAll("button")[2].trigger("click");
    expect(wrapper.findAll(".output-matrix .cell.revealed")).toHaveLength(1);
  });

  it("supports autoplay and cleans up its timer", async () => {
    vi.useFakeTimers();
    const wrapper = mount(CnnConvolutionLab);
    const autoplay = wrapper.findAll("button")[1];
    await autoplay.trigger("click");
    expect(autoplay.text()).toContain("暂停");
    await vi.advanceTimersByTimeAsync(1400);
    expect(wrapper.findAll(".output-matrix .cell.revealed")).toHaveLength(3);
    await autoplay.trigger("click");
    expect(autoplay.text()).toContain("自动播放");
    wrapper.unmount();
  });
});
