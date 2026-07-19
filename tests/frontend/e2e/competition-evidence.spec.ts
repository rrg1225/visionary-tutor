import { test, expect } from "../../../frontend/node_modules/@playwright/test";
import {
  installApiMocks,
  registeredAuthInitScript,
  seedLocalStorage,
} from "./support/api-mocks";

test.describe("competition evidence", () => {
  test.beforeEach(async ({ page }) => {
    await installApiMocks(page);
    await seedLocalStorage(page, registeredAuthInitScript());
  });

  test("learning report shows a measurable pre-test to post-test loop", async ({
    page,
  }) => {
    await page.goto("/learning-report");

    const prePostGrid = page.locator(".pre-post-grid");
    await expect(prePostGrid.getByText("前测均分")).toBeVisible();
    await expect(prePostGrid.getByText("52%", { exact: true })).toBeVisible();
    await expect(prePostGrid.getByText("78%", { exact: true })).toBeVisible();
    await expect(prePostGrid.getByText("+26%", { exact: true })).toBeVisible();
    await expect(
      page.getByRole("img", { name: "掌握度变化折线图" }),
    ).toBeVisible();
    await expect(page.getByText("正在整理评估摘要…")).toHaveCount(0);
  });

  test("resource hub and content center show structural layering regardless of mock data", async ({
    page,
  }) => {
    // 资源导航中心骨架。
    await page.goto("/resources");
    await expect(page.getByText("AI 资源中心").first()).toBeVisible();
    await expect(page.getByText("继续上次学习").first()).toBeVisible();

    // 内容中心（LibraryView）顶层骨架：页面描述 + 分区标签。
    await page.goto("/library");
    await expect(
      page.getByText("从开放原典、论文和工程文章中学习"),
    ).toBeVisible();

    // 分区标签始终渲染（限定在 .center-tabs 内，避免匹配到"提交社区内容"按钮）。
    const tabs = page.locator(".center-tabs");
    await expect(tabs.getByRole("button", { name: /系统内容/ })).toBeVisible();
    await expect(tabs.getByRole("button", { name: /社区内容/ })).toBeVisible();
  });
});
