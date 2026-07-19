import { test, expect } from "../../../frontend/node_modules/@playwright/test";
import {
  installApiMocks,
  registeredAuthInitScript,
  seedLocalStorage,
} from "./support/api-mocks";

test.describe("Agent sandbox flow", () => {
  test("registered learner sees resource navigation hub with structural entry cards", async ({
    page,
  }) => {
    await installApiMocks(page);
    await seedLocalStorage(page, registeredAuthInitScript());

    // 批次 A：资源页从全功能展开页改为导航中心。
    await page.goto("/resources");

    // 骨架元素不变：主标题、眉眼文、功能入口标题无论数据是否加载都应可见。
    await expect(
      page.getByRole("heading", {
        name: "从学习任务进入资源，不在一个页面堆满所有功能",
      }),
    ).toBeVisible();
    await expect(page.getByText("AI 资源中心")).toBeVisible();

    // 功能入口网格始终渲染（resourceEntries 为组件内静态数组）。
    const grid = page.locator(".entry-grid");
    await expect(
      grid.getByRole("heading", { name: "我的学习资料" }),
    ).toBeVisible();
    await expect(grid.getByRole("heading", { name: "互动实验" })).toBeVisible();

    // "继续上次学习"区域始终渲染。
    await expect(page.getByText("继续上次学习")).toBeVisible();
  });

  test("registered learner can reach scoped workspace for resource generation", async ({
    page,
  }) => {
    await installApiMocks(page);
    await seedLocalStorage(page, registeredAuthInitScript());

    // 资源生成移交到专属功能页：学习资料页只生成讲义和深度阅读。
    await page.goto("/learning-materials");

    await expect(page.getByText("我的学习资料")).toBeVisible();
    await expect(
      page.getByRole("heading", {
        name: "讲义与深度阅读，各自回到阅读任务",
      }),
    ).toBeVisible();
  });

  test("interactive lab exposes independent generation controls and keeps history closed", async ({
    page,
  }) => {
    await installApiMocks(page);
    await seedLocalStorage(page, registeredAuthInitScript());

    await page.goto("/labs");

    await expect(
      page.getByRole("button", { name: "生成动画实验", exact: true }),
    ).toBeVisible();
    await expect(
      page.getByRole("button", { name: "生成代码实验", exact: true }),
    ).toBeVisible();
    await expect(page.locator('.type-picker input[type="checkbox"]')).toHaveCount(0);
    await expect(page.getByRole("button", { name: /历史/ })).toHaveAttribute(
      "aria-expanded",
      "false",
    );
    await expect(page.getByLabel("搜索历史")).toHaveCount(0);
  });

  test("question bank gives the mobile category card a comfortable lower gap", async ({
    page,
  }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await installApiMocks(page);
    await seedLocalStorage(page, registeredAuthInitScript());

    await page.goto("/questions");
    const marginTop = await page.locator("#fixed-papers").evaluate((element) =>
      Number.parseFloat(getComputedStyle(element).marginTop),
    );
    expect(marginTop).toBeGreaterThanOrEqual(16);
  });

  test("guest is blocked from agent resource generation with guidance", async ({
    page,
  }) => {
    await installApiMocks(page);
    await seedLocalStorage(page, {
      vt_token: "e2e-guest-jwt",
      vt_guest_id: "gst_e2e_guest_001",
      vt_is_guest: "true",
      vt_user: JSON.stringify({
        id: "gst_e2e_guest_001",
        username: "Guest",
        displayName: "游客",
      }),
    });

    await page.goto("/learn");
    await expect(page.getByTestId("workbench-ready")).toBeVisible();

    await page.getByTestId("btn-generate-resources").click();
    await expect(page.locator("#vt-global-toast")).toContainText(
      "请先注册账号，以生成并保存学习资源",
    );
    await expect(page.getByTestId("skeleton-resource-generation")).toHaveCount(
      0,
    );
  });
});
