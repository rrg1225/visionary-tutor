import { test, expect } from "../../../frontend/test-support/playwright.mjs";
import {
  installApiMocks,
  registeredAuthInitScript,
  seedLocalStorage,
} from "./support/api-mocks";

test.describe("guide and independent code sandbox", () => {
  test("guide body uses a comfortable reading rhythm", async ({ page }) => {
    await installApiMocks(page);
    await page.goto("/guide");

    const firstSummary = page.locator(".guide-card header p").first();
    const firstStep = page.locator(".instruction-list li").first();
    await expect(firstSummary).toBeVisible();
    await expect(firstStep).toBeVisible();

    for (const locator of [firstSummary, firstStep]) {
      const rhythm = await locator.evaluate((element) => {
        const style = getComputedStyle(element);
        return (
          Number.parseFloat(style.lineHeight) /
          Number.parseFloat(style.fontSize)
        );
      });
      expect(rhythm).toBeGreaterThanOrEqual(1.6);
    }
  });

  test("registered learner can open the complete sandbox workspace on desktop and mobile", async ({
    page,
  }) => {
    await installApiMocks(page);
    await seedLocalStorage(page, registeredAuthInitScript());

    await page.goto("/code-sandbox");
    await expect(page).toHaveURL(/\/code-sandbox$/);
    await expect(page.locator(".history-panel")).toBeVisible();
    await expect(page.locator(".code-editor")).toBeVisible();
    await expect(page.locator(".test-card")).toBeVisible();
    await expect(page.locator(".tutor-panel")).toBeVisible();

    const desktop = await Promise.all([
      page.locator(".history-panel").boundingBox(),
      page.locator(".workspace-panel").boundingBox(),
      page.locator(".tutor-panel").boundingBox(),
    ]);
    expect(desktop.every(Boolean)).toBeTruthy();
    expect(desktop[0]!.x).toBeLessThan(desktop[1]!.x);
    expect(desktop[1]!.x).toBeLessThan(desktop[2]!.x);

    await page.setViewportSize({ width: 390, height: 844 });
    const mobile = await Promise.all([
      page.locator(".history-panel").boundingBox(),
      page.locator(".workspace-panel").boundingBox(),
      page.locator(".tutor-panel").boundingBox(),
    ]);
    expect(mobile.every(Boolean)).toBeTruthy();
    expect(mobile[0]!.y).toBeLessThan(mobile[1]!.y);
    expect(mobile[1]!.y).toBeLessThan(mobile[2]!.y);
  });
});
