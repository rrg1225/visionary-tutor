import { describe, expect, it } from "vitest";
import { normalizeMarkdownSource } from "../../src/utils/sanitizeAssistantContent";
import { renderSimpleMarkdown } from "../../src/utils/simpleMarkdown";

describe("learner-facing markdown rendering", () => {
  it("repairs headings and fenced code joined by streaming chunks", () => {
    const source =
      "卷积说明##输出尺寸\n```pythonprint((224 - 3 + 2) // 2 + 1)\n```";
    const normalized = normalizeMarkdownSource(source);
    const html = renderSimpleMarkdown(source);

    expect(normalized).toContain("\n\n## 输出尺寸");
    expect(normalized).toContain("```python\nprint");
    expect(html).toContain('<h2 id="section-2">输出尺寸</h2>');
    expect(html).toContain('<pre><code class="language-python">');
  });

  it("renders inline and display math without exposing unsafe html", () => {
    const html = renderSimpleMarkdown(
      "公式 $H_{out}$：\n\n$$\n<script>alert(1)</script>\n$$",
    );

    expect(html).toContain('<span class="katex">');
    expect(html).toContain(
      '<div class="math-block"><span class="katex-display">',
    );
    expect(html).toContain("&lt;script&gt;alert(1)&lt;/script&gt;");
    expect(html).not.toContain("<script>");
  });

  it("renders safe markdown links and drops unsafe protocols", () => {
    const html = renderSimpleMarkdown(
      "查看[课程资料](https://example.com/lesson)和[站内指南](/guide)，不要打开[危险链接](javascript:alert(1))。",
    );

    expect(html).toContain(
      '<a href="https://example.com/lesson" target="_blank" rel="noopener noreferrer">课程资料</a>',
    );
    expect(html).toContain('<a href="/guide">站内指南</a>');
    expect(html).toContain("危险链接");
    expect(html).not.toContain("javascript:");
  });
});
