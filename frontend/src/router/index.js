import { createRouter, createWebHashHistory, createWebHistory } from "vue-router";
import api from "../api/index";
import { useAuthStore } from "../stores/authStore";
import { useUserProfileStore } from "../stores/userProfile";
import { toastWarning } from "../utils/toast";
import LandingView from "../views/LandingView.vue";
import HomeView from "../views/HomeView.vue";
import OnboardingView from "../views/OnboardingView.vue";
import AuthView from "../views/AuthView.vue";
import AssessmentFillView from "../views/AssessmentFillView.vue";
import LearningReportView from "../views/LearningReportView.vue";

// 次要页面仍懒加载；核心学习路径静态导入，避免 OneDrive 下 dynamic import 缓存失败
const ProfileView = () => import("../views/ProfileView.vue");
const ProfileFillView = () => import("../views/ProfileFillView.vue");
const LibraryView = () => import("../views/LibraryView.vue");
const LibraryDetailView = () => import("../views/LibraryDetailView.vue");
const AdminUgcReviewView = () => import("../views/AdminUgcReviewView.vue");
const AdminRagEvalView = () => import("../views/AdminRagEvalView.vue");
const MemoryManagementView = () => import("../views/MemoryManagementView.vue");
const ResourcesView = () => import("../views/ResourcesView.vue");
const LearningMaterialsView = () =>
  import("../views/LearningMaterialsView.vue");
const PersonalizedPracticeView = () =>
  import("../views/PersonalizedPracticeView.vue");
const InteractiveLabView = () => import("../views/InteractiveLabView.vue");
const CodeSandboxView = () => import("../views/CodeSandboxView.vue");
const LearningPlanView = () => import("../views/LearningPlanView.vue");
const LearningOutcomesView = () => import("../views/LearningOutcomesView.vue");
const MyLearningView = () => import("../views/MyLearningView.vue");
const AgentTraceView = () => import("../views/AgentTraceView.vue");
const PrivacyCenterView = () => import("../views/PrivacyCenterView.vue");
const GuideView = () => import("../views/GuideView.vue");
const QuestionBankView = () => import("../views/QuestionBankView.vue");
const FixedExamView = () => import("../views/FixedExamView.vue");
const FixedExamReportView = () => import("../views/FixedExamReportView.vue");
const SystemKnowledgeContentView = () =>
  import("../views/SystemKnowledgeContentView.vue");
const LegalView = () => import("../views/LegalView.vue");
const NotFoundView = () => import("../views/NotFoundView.vue");

const router = createRouter({
  history: import.meta.env.VITE_NATIVE_APP === "true"
    ? createWebHashHistory()
    : createWebHistory(),
  routes: [
    {
      path: "/",
      name: "landing",
      component: LandingView,
      meta: {
        title: "首页",
        description: "AI 学习答疑、知识资源推荐、知识测评与个性化学习报告。",
      },
    },
    {
      path: "/learn",
      name: "learn",
      component: HomeView,
      meta: { title: "AI 辅导" },
    },
    {
      path: "/onboarding",
      name: "onboarding",
      component: OnboardingView,
      meta: { title: "对话建档", requiresRegistered: true },
    },
    {
      path: "/profile",
      name: "profile",
      component: ProfileView,
      meta: { title: "学习档案", requiresRegistered: true },
    },
    {
      path: "/profile-fill",
      name: "profile-fill",
      component: ProfileFillView,
      meta: { title: "个人中心", requiresRegistered: true },
    },
    {
      path: "/assessment-fill",
      name: "assessment-fill",
      component: AssessmentFillView,
      meta: { title: "知识测评" },
    },
    {
      path: "/auth",
      name: "auth",
      component: AuthView,
      meta: { title: "登录与注册" },
    },
    {
      path: "/questions",
      name: "questions",
      component: QuestionBankView,
      meta: { title: "题库与测评", requiresRegistered: true },
    },
    {
      path: "/questions/personalized",
      name: "personalized-practice",
      component: PersonalizedPracticeView,
      meta: { title: "AI 个性化专项练习", requiresRegistered: true },
    },
    {
      path: "/questions/papers/:paperCode",
      name: "fixed-exam",
      component: FixedExamView,
      meta: { title: "固定题卷作答", requiresRegistered: true },
    },
    {
      path: "/questions/attempts/:attemptId/report",
      name: "fixed-exam-report",
      component: FixedExamReportView,
      meta: { title: "固定题卷报告", requiresRegistered: true },
    },
    {
      path: "/guide",
      name: "guide",
      component: GuideView,
      meta: { title: "使用指南", skipOnboarding: true },
    },
    {
      path: "/legal",
      name: "legal",
      component: LegalView,
      meta: { title: "用户协议与隐私说明" },
    },
    {
      path: "/resources",
      name: "resources",
      component: ResourcesView,
      meta: { title: "AI 资源中心", requiresRegistered: true },
    },
    {
      path: "/learning-materials",
      name: "learning-materials",
      component: LearningMaterialsView,
      meta: { title: "我的学习资料", requiresRegistered: true },
    },
    {
      path: "/labs",
      name: "interactive-lab",
      component: InteractiveLabView,
      meta: { title: "互动实验", requiresRegistered: true },
    },
    {
      path: "/code-sandbox",
      name: "code-sandbox",
      component: CodeSandboxView,
      meta: { title: "代码沙箱", requiresRegistered: true },
    },
    {
      path: "/learning-plan",
      name: "learning-plan",
      component: LearningPlanView,
      meta: { title: "学习规划", requiresRegistered: true },
    },
    {
      path: "/learning-outcomes",
      name: "learning-outcomes",
      component: LearningOutcomesView,
      meta: { title: "学习成果", requiresRegistered: true },
    },
    {
      path: "/my-learning",
      name: "my-learning",
      component: MyLearningView,
      meta: { title: "我的学习", requiresRegistered: true },
    },
    {
      path: "/library",
      name: "library",
      component: LibraryView,
      meta: { title: "教材中心", requiresRegistered: true },
    },
    {
      path: "/library/:id",
      name: "library-detail",
      component: LibraryDetailView,
      meta: { title: "教材阅读", requiresRegistered: true },
    },
    {
      path: "/knowledge-content/:slug",
      name: "system-knowledge-content",
      component: SystemKnowledgeContentView,
      meta: { title: "系统内容深度阅读", requiresRegistered: true },
    },
    {
      path: "/admin/ugc-review",
      name: "admin-ugc-review",
      component: AdminUgcReviewView,
      meta: {
        title: "UGC Review",
        requiresRegistered: true,
        requiresAdmin: true,
      },
    },
    {
      path: "/admin/rag-eval",
      name: "admin-rag-eval",
      component: AdminRagEvalView,
      meta: {
        title: "RAG Evaluation",
        requiresRegistered: true,
        requiresAdmin: true,
      },
    },
    {
      path: "/memory",
      name: "memory",
      component: MemoryManagementView,
      meta: { title: "记忆管理", requiresRegistered: true },
    },
    {
      path: "/privacy",
      name: "privacy",
      component: PrivacyCenterView,
      meta: { title: "隐私中心", requiresRegistered: true },
    },
    {
      path: "/agent-trace",
      name: "agent-trace",
      component: AgentTraceView,
      meta: { title: "Agent Trace", requiresRegistered: true },
    },
    {
      path: "/learning-report",
      name: "learning-report",
      component: LearningReportView,
      meta: { title: "学习报告", requiresRegistered: true },
    },
    {
      path: "/:pathMatch(.*)*",
      name: "not-found",
      component: NotFoundView,
      meta: { title: "页面未找到", skipOnboarding: true },
    },
  ],
  scrollBehavior() {
    return { top: 0 };
  },
});

router.beforeEach(async (to) => {
  const authStore = useAuthStore();
  const userProfile = useUserProfileStore();
  authStore.reloadFromStorage();
  userProfile.hydrateFromStorage();

  if (to.path === "/auth" && authStore.isRegistered) {
    return "/learn";
  }

  if (to.meta.requiresRegistered && !authStore.isRegistered) {
    return {
      path: "/auth",
      query: { mode: "register", redirect: to.fullPath },
    };
  }

  if (to.meta.requiresAdmin) {
    try {
      const { data } = await api.get("/library/admin/status");
      if (!data?.admin) {
        toastWarning("需要管理员权限", 3000);
        return "/library";
      }
    } catch {
      toastWarning("需要管理员权限", 3000);
      return "/library";
    }
  }

  const openPaths = [
    "/",
    "/learn",
    "/auth",
    "/guide",
    "/legal",
    "/onboarding",
    "/profile-fill",
    "/profile",
    "/assessment-fill",
    "/questions",
    "/memory",
    "/privacy",
    "/resources",
    "/library",
    "/learning-materials",
    "/labs",
    "/code-sandbox",
    "/learning-plan",
    "/learning-outcomes",
    "/my-learning",
    "/agent-trace",
    "/admin/ugc-review",
    "/admin/rag-eval",
  ];
  const isOpenPath =
    openPaths.includes(to.path) ||
    to.path.startsWith("/library/") ||
    to.path.startsWith("/knowledge-content/") ||
    to.path.startsWith("/questions/");
  if (
    authStore.isRegistered &&
    !userProfile.onboardingComplete &&
    !isOpenPath &&
    !to.meta.skipOnboarding
  ) {
    toastWarning("请先完成 3 分钟对话建档，以解锁个性化学习资源", 4000);
    return "/onboarding";
  }
});

router.afterEach((to) => {
  const title = to.meta.title
    ? `${to.meta.title} · Visionary Tutor`
    : "Visionary Tutor";
  document.title = title;
  const description =
    to.meta.description ||
    "Visionary Tutor 提供 AI 学习答疑、知识资源、知识测评和个性化学习建议。";
  document
    .querySelector('meta[name="description"]')
    ?.setAttribute("content", description);
  document
    .querySelector('meta[property="og:title"]')
    ?.setAttribute("content", title);
  document
    .querySelector('meta[property="og:description"]')
    ?.setAttribute("content", description);
});

const CHUNK_RELOAD_KEY = "vt_chunk_reload_at";

router.onError((error) => {
  const message = error?.message || String(error);
  if (
    !/Failed to fetch dynamically imported module|Loading chunk|ERR_CACHE_READ_FAILURE|Importing a module script failed/i.test(
      message,
    )
  ) {
    return;
  }
  const now = Date.now();
  const last = Number(sessionStorage.getItem(CHUNK_RELOAD_KEY) || 0);
  if (last && now - last < 15_000) {
    console.error("[router] chunk load failed again, not reloading:", message);
    return;
  }
  sessionStorage.setItem(CHUNK_RELOAD_KEY, String(now));
  console.warn("[router] chunk load failed, reloading page:", message);
  window.location.reload();
});

export default router;
