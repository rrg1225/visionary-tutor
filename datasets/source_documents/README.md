# 原始资料边界

原始 PDF、网页抓取副本和翻译仓库不在本目录重复提交。正式交付只包含经过规范化的 Markdown 文档及其 `manifest.json` 记录。

授权策略：

| 来源族 | 授权标识 | 提交策略 |
|---|---|---|
| Dive into Deep Learning | CC-BY-SA-4.0 | 允许，保留署名与相同方式共享说明 |
| PyTorch Tutorials | BSD-3-Clause | 允许，保留原项目版权说明 |
| Hello 算法 | CC-BY-NC-SA-4.0 | 仅比赛/非商业教学，保留署名与协议说明 |
| CS229、CS231n 翻译资料 | REVIEW_REQUIRED | 默认不进入提交包，取得明确许可后才能启用 |
| 商业出版书籍 PDF/转换稿 | PROPRIETARY | 禁止进入提交包 |

如果后续补充资料，必须先更新 `scripts/build-dataset-manifest.ps1` 的授权映射，再生成清单；禁止通过手工复制绕过清单。
