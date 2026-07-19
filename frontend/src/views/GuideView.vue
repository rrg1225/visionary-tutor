<template>
  <section class="guide-view vt-section">
    <header class="guide-hero vt-container">
      <div>
        <span class="vt-eyebrow">使用指南</span>
        <h1 class="vt-title">从第一次提问，到用报告开始下一轮学习</h1>
        <p class="vt-text-muted">
          这份指南按真实任务说明入口、操作步骤和结果去向。首页只保留三步提示，完整说明统一放在这里。
        </p>
      </div>
      <button type="button" class="vt-btn vt-btn-primary" @click="restartTour">
        重新播放三步引导
      </button>
    </header>

    <section
      class="quick-start vt-container vt-card"
      aria-labelledby="quick-start-title"
    >
      <header>
        <span class="vt-eyebrow">首次使用</span>
        <h2 id="quick-start-title">只记住三个动作</h2>
      </header>
      <ol>
        <li v-for="item in quickStart" :key="item.id">
          <span>{{ item.id }}</span>
          <div>
            <strong>{{ item.title }}</strong>
            <p>{{ item.description }}</p>
            <RouterLink :to="item.to">{{ item.action }} →</RouterLink>
          </div>
        </li>
      </ol>
    </section>

    <div class="guide-layout vt-container">
      <nav class="guide-nav vt-card" aria-label="指南目录">
        <strong>目录</strong>
        <a
          v-for="item in guideItems"
          :key="item.id"
          :href="`#guide-${item.id}`"
        >
          {{ item.id }}. {{ item.title }}
        </a>
      </nav>

      <div class="guide-content">
        <article
          v-for="item in guideItems"
          :id="`guide-${item.id}`"
          :key="item.id"
          class="guide-card vt-card"
        >
          <header>
            <span class="guide-index">{{ item.id }}</span>
            <div>
              <h2>{{ item.title }}</h2>
              <p>{{ item.summary }}</p>
            </div>
          </header>
          <ol class="instruction-list">
            <li v-for="step in item.steps" :key="step">{{ step }}</li>
          </ol>
          <p v-if="item.notice" class="guide-notice">{{ item.notice }}</p>
          <RouterLink class="vt-btn vt-btn-outline vt-btn-sm" :to="item.to">
            {{ item.action }}
          </RouterLink>
        </article>
      </div>
    </div>

    <section class="privacy-summary vt-container vt-card">
      <div>
        <span class="vt-eyebrow">数据边界</span>
        <h2>摄像头、代码与学习数据如何处理</h2>
      </div>
      <ul>
        <li>
          <strong>摄像头：</strong
          >原始视频只在浏览器本地处理，不上传、不保存；服务端只接收聚合后的状态指标。
        </li>
        <li>
          <strong>代码：</strong>浏览器实验在隔离 Worker
          中执行；服务端实验进入受限沙箱，不允许任意网络和依赖安装。
        </li>
        <li>
          <strong>学习数据：</strong
          >对话、作答、资源使用和报告按账号保存，用于进度恢复、错题复习和个性化建议。
        </li>
        <li>
          <strong>用户控制：</strong
          >可在隐私中心导出数据、删除学习记忆或注销账号。
        </li>
      </ul>
      <div class="privacy-actions">
        <RouterLink class="vt-btn vt-btn-outline vt-btn-sm" to="/legal">
          查看用户协议与隐私说明
        </RouterLink>
        <RouterLink class="vt-btn vt-btn-ghost vt-btn-sm" to="/privacy">
          管理我的数据
        </RouterLink>
      </div>
    </section>
  </section>
</template>

<script setup>
import { RouterLink, useRouter } from "vue-router";
import { useGuideStore } from "../stores/guide";

const router = useRouter();
const guideStore = useGuideStore();

const quickStart = [
  {
    id: 1,
    title: "选择内容或直接提问",
    description: "可以从 AI 老师、系统教材或综合题卷任意一个入口开始。",
    action: "向 AI 老师提问",
    to: "/learn",
  },
  {
    id: 2,
    title: "在学习中按需开启辅助",
    description:
      "阅读或做题时自行决定是否开启学习状态辅助，未开启也能正常学习。",
    action: "打开教材中心",
    to: "/library",
  },
  {
    id: 3,
    title: "完成任务后查看报告",
    description: "提交题卷或自考后，用薄弱点、错题和推荐内容继续下一轮学习。",
    action: "进入题库与测评",
    to: "/questions",
  },
];

const guideItems = [
  {
    id: 1,
    title: "如何向 AI 老师提问",
    summary:
      "自由问答适合概念、公式、代码报错和学习规划；教材与题目页面中的 AI 老师会自动带上当前上下文。",
    steps: [
      "进入“AI 辅导”，输入一个具体问题。",
      "说明希望得到提示、检查思路或分步骤解释。",
      "继续追问，回答会保存在当前学习会话中。",
    ],
    notice: "当证据不足时，回答会明确提示降级状态，不会假装已经检索或验证。",
    action: "立即问 AI 老师",
    to: "/learn",
  },
  {
    id: 2,
    title: "如何选择固定题卷",
    summary:
      "平台精选综合题卷使用预先审核的题目、答案和解析，不在答题时临时生成。",
    steps: [
      "进入“题库与测评”。",
      "在“平台精选综合题卷”中选择主题和难度。",
      "进入做题页后查看题号、作答状态与整卷计时。",
    ],
    notice:
      "固定题卷来自已发布的结构化版本；提交后会生成报告，并把需要巩固的题目关联到错题与学习证据。",
    action: "进入题库与测评",
    to: "/questions",
  },
  {
    id: 3,
    title: "如何生成 AI 专项练习",
    summary: "专项练习只围绕当前薄弱点生成，不需要在七种资源中做复杂多选。",
    steps: [
      "从题库页进入“AI 个性化专项练习”。",
      "填写要强化的知识点。",
      "选择生成后等待质量校验，再进入作答。",
    ],
    action: "生成专项练习",
    to: "/questions/personalized",
  },
  {
    id: 4,
    title: "如何查看答案和解析",
    summary:
      "正常流程是在提交后自动显示，也允许提前查看；提前查看会作为学习行为记录。",
    steps: [
      "作答时可点击“只给提示”或询问 AI 老师。",
      "提交后查看标准答案、得分点和分步解析。",
      "提前查看答案的题目会进入报告并按需加入错题本。",
    ],
    action: "打开题库",
    to: "/questions",
  },
  {
    id: 5,
    title: "如何阅读系统教材",
    summary:
      "系统教材是平台审核的主要教学内容；社区教材会单独标明作者、来源和授权。",
    steps: [
      "进入“教材中心”，优先选择“系统教材”。",
      "按章节目录阅读，系统保存完成度和上次位置。",
      "划选段落或在右侧 AI 老师中继续追问。",
    ],
    action: "进入教材中心",
    to: "/library",
  },
  {
    id: 6,
    title: "如何完成教材自考题",
    summary:
      "章节思考题与全书自考使用固定答案和得分点评分，并与教材原段落联动。",
    steps: [
      "阅读到章节底部后填写答案并保存草稿。",
      "需要帮助时选择“只给提示”，不默认展示答案。",
      "提交自考后查看得分、解析和需要重读的章节。",
    ],
    notice:
      "系统教材自测已接入评分与错题记录；社区分享只有在内容具备结构化自测时才展示该入口。",
    action: "查看教材中心",
    to: "/library",
  },
  {
    id: 7,
    title: "如何开启学习状态辅助",
    summary: "这是一项按需开启的辅助能力，不是进入页面后自动录像。",
    steps: [
      "在教材、题卷或 AI 学习页面点击“开启学习状态辅助”。",
      "阅读隐私说明并授予摄像头权限。",
      "等待状态从“初始化”变为“正在采集”，继续正常学习。",
    ],
    notice: "原始视频只在本地处理；未开启时报告不会包含专注或困惑判断。",
    action: "查看隐私说明",
    to: "/legal",
  },
  {
    id: 8,
    title: "如何关闭辅助并生成报告",
    summary: "关闭不是只停摄像头，而是结束观察、汇总本次数据并打开状态报告。",
    steps: [
      "点击“关闭并生成状态报告”。",
      "等待状态变为“正在生成报告”。",
      "查看有效时长、趋势、卡壳位置和复习建议。",
    ],
    notice: "数据不足时仍会生成报告，但只说明“数据不足”，不会硬判断情绪。",
    action: "查看学习报告",
    to: "/learning-report",
  },
  {
    id: 9,
    title: "如何使用动画和代码实验",
    summary:
      "动画用来观察参数变化，代码用来验证结果；动画在互动实验中学习，代码可进入独立沙箱获得完整工作区。",
    steps: [
      "动画实验：进入“互动实验”，播放、暂停、逐步操作并调节参数。",
      "代码实验：进入“代码沙箱”，编辑 Python 并添加表达式测试用例。",
      "运行后对照预期值、实际值和错误信息，需要时让右侧 AI 老师结合当前代码解释。",
      "从 AI 资源生成同时创建代码与动画时，两类内容按上下两个独立大卡片展示。",
    ],
    action: "打开代码沙箱",
    to: "/code-sandbox",
  },
  {
    id: 10,
    title: "如何管理摄像头、代码和学习数据",
    summary:
      "平台将设备权限、学习记忆和账户数据分开管理，用户可以查看、导出和删除。",
    steps: [
      "在浏览器地址栏管理摄像头权限。",
      "在隐私中心查看摄像头策略并导出账户数据。",
      "在记忆管理中审核、修改或删除个性化学习记忆。",
    ],
    action: "进入隐私中心",
    to: "/privacy",
  },
];

async function restartTour() {
  guideStore.start({ force: true });
  await router.push({ path: "/", query: { guide: "1" } });
}
</script>

<style scoped>
.guide-view,
.guide-hero,
.quick-start,
.guide-content,
.privacy-summary {
  display: grid;
  gap: var(--vt-space-6);
}

.guide-view {
  max-width: 1180px;
}

.guide-hero {
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: end;
}

.guide-hero p {
  max-width: 780px;
  line-height: 1.75;
}

.quick-start,
.privacy-summary {
  padding: var(--vt-space-6);
}

.quick-start h2,
.privacy-summary h2 {
  margin: var(--vt-space-1) 0 0;
  color: var(--vt-text-primary);
}

.quick-start ol {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--vt-space-4);
  margin: 0;
  padding: 0;
  list-style: none;
}

.quick-start li {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  gap: var(--vt-space-4);
  padding: var(--vt-space-4);
  border: 1px solid var(--vt-border-light);
  border-radius: var(--vt-radius-md);
  background: var(--vt-bg-primary);
}

.quick-start strong {
  font-size: var(--vt-text-base);
  line-height: 1.55;
}

.quick-start li > span,
.guide-index {
  display: grid;
  width: 2rem;
  height: 2rem;
  place-items: center;
  border-radius: 999px;
  background: rgba(13, 148, 136, 0.12);
  color: var(--vt-accent-teal-dark);
  font-weight: var(--vt-font-bold);
}

.quick-start p,
.guide-card header p {
  margin: var(--vt-space-3) 0;
  color: var(--vt-text-secondary);
  font-size: var(--vt-text-base);
  line-height: 1.85;
}

.quick-start a {
  color: var(--vt-accent-teal-dark);
  font-weight: var(--vt-font-semibold);
  text-decoration: none;
}

.guide-layout {
  display: grid;
  grid-template-columns: 230px minmax(0, 1fr);
  align-items: start;
  gap: var(--vt-space-5);
}

.guide-nav {
  position: sticky;
  top: 92px;
  display: grid;
  gap: var(--vt-space-2);
  padding: var(--vt-space-4);
}

.guide-nav a {
  color: var(--vt-text-secondary);
  font-size: var(--vt-text-sm);
  line-height: 1.65;
  text-decoration: none;
}

.guide-nav a:hover {
  color: var(--vt-accent-teal-dark);
}

.guide-card {
  display: grid;
  justify-items: start;
  gap: var(--vt-space-5);
  padding: calc(var(--vt-space-6) + var(--vt-space-2));
  scroll-margin-top: 96px;
}

.guide-card header {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  gap: var(--vt-space-4);
}

.guide-card h2 {
  margin: 0;
  color: var(--vt-text-primary);
  font-size: var(--vt-text-xl);
  line-height: 1.45;
}

.instruction-list {
  display: grid;
  gap: var(--vt-space-3);
  margin: 0;
  padding-left: 1.5rem;
  color: var(--vt-text-secondary);
  font-size: var(--vt-text-base);
  line-height: 1.85;
}

.instruction-list li {
  padding-left: var(--vt-space-2);
}

.guide-notice {
  margin: 0;
  padding: var(--vt-space-4);
  border-left: 3px solid #f59e0b;
  background: rgba(245, 158, 11, 0.08);
  color: var(--vt-text-secondary);
  font-size: var(--vt-text-base);
  line-height: 1.8;
}

.privacy-summary ul {
  display: grid;
  gap: var(--vt-space-4);
  margin: 0;
  padding-left: 1.4rem;
  color: var(--vt-text-secondary);
  font-size: var(--vt-text-base);
  line-height: 1.85;
}

.privacy-actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--vt-space-3);
}

@media (max-width: 860px) {
  .guide-hero,
  .quick-start ol,
  .guide-layout {
    grid-template-columns: 1fr;
  }

  .guide-hero {
    align-items: start;
  }

  .guide-nav {
    position: static;
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .guide-nav strong {
    grid-column: 1 / -1;
  }
}

@media (max-width: 560px) {
  .guide-nav {
    grid-template-columns: 1fr;
  }

  .guide-card,
  .quick-start,
  .privacy-summary {
    padding: var(--vt-space-4);
  }

  .guide-card {
    gap: var(--vt-space-4);
  }

  .guide-card header {
    gap: var(--vt-space-3);
  }
}
</style>
