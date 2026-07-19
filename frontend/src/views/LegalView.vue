<template>
  <main class="legal-page vt-container-narrow vt-section">
    <header>
      <span class="vt-eyebrow">使用与隐私</span>
      <h1 class="vt-title">用户协议与隐私说明</h1>
      <p class="vt-text-muted">注册前即可阅读。最后更新：2026 年 7 月 15 日。</p>
    </header>

    <section class="vt-card legal-card">
      <h2>我们收集什么</h2>
      <p>为提供个性化辅导，平台会处理账号信息、学习对话、测评结果、学习画像和用户主动提交的文件。摄像头默认关闭；开启学习状态辅助时，原始视频仅在本地处理，不上传原始画面。</p>

      <h2>数据如何使用</h2>
      <p>数据仅用于身份认证、学习辅导、资源生成、效果评估和必要的故障审计。平台不会把个人学习数据出售给第三方。</p>

      <h2>你的权利</h2>
      <p>注册用户可以在隐私中心导出个人数据、查看摄像头策略并删除长期学习记忆。删除账号或其他数据请求可通过个人中心的意见反馈提交。</p>

      <h2>AI 能力边界</h2>
      <p>AI 输出可能存在错误或遗漏，不应替代教师评价、考试规则或专业意见。重要结论请结合课程教材和教师要求核验，并可通过反馈入口报告问题。</p>

      <h2>用户使用规则</h2>
      <p>请勿上传违法、侵权、恶意代码或他人的敏感信息；不得利用平台攻击服务、绕过访问控制或批量滥用生成能力。违反规则时平台可限制相关账号。</p>
    </section>

    <RouterLink class="vt-btn vt-btn-outline" to="/auth?mode=register">返回注册</RouterLink>
  </main>
</template>

<script setup>
import { RouterLink } from 'vue-router'
</script>

<style scoped>
.legal-page { display: grid; gap: var(--vt-space-5); }
.legal-card { display: grid; gap: var(--vt-space-3); padding: var(--vt-space-6); }
.legal-card h2 { margin: var(--vt-space-2) 0 0; font-size: var(--vt-text-lg); }
.legal-card p { margin: 0; line-height: 1.75; color: var(--vt-text-secondary); }
</style>
