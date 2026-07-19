<template>
  <div class="app-shell">
    <!-- Ambient gradient blobs (glassmorphism backdrop) -->
    <div class="app-bg-blobs" aria-hidden="true">
      <span class="app-blob app-blob--violet"></span>
      <span class="app-blob app-blob--teal"></span>
      <span class="app-blob app-blob--blue"></span>
    </div>

    <!-- Navigation Bar -->
    <header class="nav-header vt-glass">
      <div class="nav-container">
        <!-- Brand -->
        <RouterLink class="brand" to="/" @click="closeNav">
          <div class="brand-icon">
            <svg
              viewBox="0 0 24 24"
              fill="none"
              xmlns="http://www.w3.org/2000/svg"
            >
              <path
                d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-2 15l-5-5 1.41-1.41L10 14.17l7.59-7.59L19 8l-9 9z"
                fill="currentColor"
              />
              <circle
                cx="12"
                cy="12"
                r="3"
                fill="currentColor"
                fill-opacity="0.3"
              />
            </svg>
          </div>
          <div class="brand-text">
            <span class="brand-title">VisionaryTutor</span>
            <span class="brand-subtitle">智眸学伴</span>
          </div>
        </RouterLink>

        <!-- Desktop Navigation -->
        <nav class="desktop-nav" aria-label="Primary navigation">
          <div class="nav-links">
            <template v-for="item in navItems" :key="item.path">
              <span
                v-if="item.dividerBefore"
                class="nav-divider"
                aria-hidden="true"
              ></span>
              <RouterLink
                :to="resolveNavTarget(item)"
                class="nav-link"
                :class="{ 'nav-link-active': isActiveRoute(item) }"
                :title="
                  !authStore.isRegistered && item.requiresRegistered
                    ? '需登录后使用'
                    : undefined
                "
              >
                <span class="nav-link-text">{{ item.label }}</span>
                <svg
                  v-if="!authStore.isRegistered && item.requiresRegistered"
                  class="nav-lock-icon"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="2"
                  aria-label="需登录"
                >
                  <rect x="5" y="11" width="14" height="10" rx="2" />
                  <path d="M8 11V8a4 4 0 118 0v3" />
                </svg>
              </RouterLink>
            </template>
          </div>

          <div class="user-section">
            <button
              v-if="!authStore.isRegistered"
              class="nav-cta-btn"
              @click="showLoginModal"
            >
              <svg
                width="16"
                height="16"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                stroke-width="2"
              >
                <path
                  d="M15 3h4a2 2 0 012 2v14a2 2 0 01-2 2h-4M10 17l5-5-5-5M13.8 12H3"
                />
              </svg>
              登录 / 注册
            </button>

            <div v-else class="user-info">
              <RouterLink
                class="user-name user-center-link"
                to="/profile-fill"
                title="进入个人中心"
              >
                我的 · {{ authStore.displayName }}
              </RouterLink>
              <button
                class="nav-ghost-btn"
                aria-label="退出登录"
                title="退出登录"
                @click="handleLogout"
              >
                <svg
                  width="16"
                  height="16"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="2"
                >
                  <path
                    d="M9 21H5a2 2 0 01-2-2V5a2 2 0 012-2h4M16 17l5-5-5-5M21 12H9"
                  />
                </svg>
              </button>
            </div>
          </div>
        </nav>

        <!-- Mobile Menu Toggle -->
        <button
          class="mobile-toggle"
          type="button"
          :aria-expanded="navOpen"
          aria-controls="mobile-navigation"
          aria-label="Toggle navigation menu"
          @click="toggleNav"
        >
          <span
            class="toggle-bar"
            :class="{ 'toggle-bar-active': navOpen }"
          ></span>
          <span
            class="toggle-bar"
            :class="{ 'toggle-bar-active': navOpen }"
          ></span>
          <span
            class="toggle-bar"
            :class="{ 'toggle-bar-active': navOpen }"
          ></span>
        </button>
      </div>

      <!-- Mobile Navigation Drawer -->
      <Transition name="drawer">
        <nav
          v-show="navOpen"
          id="mobile-navigation"
          class="mobile-nav"
          aria-label="Mobile navigation"
        >
          <div class="mobile-nav-content">
            <!-- 移动端用户操作始终位于菜单顶部，避免长菜单把登录入口挤出视口。 -->
            <div class="mobile-user-actions">
              <button
                v-if="!authStore.isRegistered"
                class="mobile-nav-link mobile-nav-link-primary"
                @click="showLoginModalMobile"
              >
                <span class="mobile-nav-text">登录 / 注册</span>
                <span class="mobile-nav-arrow">→</span>
              </button>

              <button
                v-else
                class="mobile-nav-link"
                @click="handleLogoutMobile"
              >
                <span class="mobile-nav-text">退出登录</span>
                <span class="mobile-nav-arrow">→</span>
              </button>
            </div>

            <div
              v-for="group in navGroups"
              :key="group.label"
              class="mobile-nav-group"
            >
              <span class="mobile-nav-group-label">{{ group.label }}</span>
              <RouterLink
                v-for="item in group.items"
                :key="item.path"
                :to="resolveNavTarget(item)"
                class="mobile-nav-link"
                :class="{ 'mobile-nav-link-active': isActiveRoute(item) }"
                @click="closeNav"
              >
                <span class="mobile-nav-text">{{ item.label }}</span>
                <span
                  v-if="!authStore.isRegistered && item.requiresRegistered"
                  class="nav-lock"
                  >需登录</span
                >
                <span
                  v-else-if="
                    item.requiresProfile && !userProfile.onboardingComplete
                  "
                  class="nav-lock"
                  >建档后解锁</span
                >
                <span class="mobile-nav-arrow">→</span>
              </RouterLink>
            </div>
          </div>
        </nav>
      </Transition>
    </header>

    <!-- Main Content -->
    <main
      class="main-content"
      :class="{ 'main-content--landing': route.name === 'landing' }"
    >
      <RouterView v-slot="{ Component }">
        <Transition name="page" mode="out-in">
          <component :is="Component" />
        </Transition>
      </RouterView>
    </main>

    <Transition name="page">
      <aside
        v-if="confusionOffer.active"
        class="global-learning-offer vt-card"
        role="dialog"
        aria-live="polite"
        aria-label="学习状态辅助建议"
      >
        <div>
          <strong>要调整一下当前学习节奏吗？</strong>
          <p>{{ confusionOffer.message }} 系统不会替你判断真实情绪。</p>
        </div>
        <div class="global-learning-offer__actions">
          <button
            type="button"
            class="vt-btn vt-btn-primary vt-btn-sm"
            :disabled="confusionOffer.isAccepting"
            @click="handleDifferentExplanation"
          >
            换一种讲法
          </button>
          <button
            type="button"
            class="vt-btn vt-btn-outline vt-btn-sm"
            @click="takeLearningBreak"
          >
            休息一下
          </button>
          <button
            type="button"
            class="vt-btn vt-btn-ghost vt-btn-sm"
            @click="confusionOffer.dismiss()"
          >
            继续学习
          </button>
        </div>
      </aside>
    </Transition>

    <footer class="site-footer">
      <div class="site-footer-links">
        <RouterLink to="/guide">使用指南</RouterLink>
        <RouterLink to="/legal">用户协议与隐私说明</RouterLink>
        <a
          href="https://beian.miit.gov.cn/"
          target="_blank"
          rel="noopener noreferrer"
          >苏ICP备2026015754号</a
        >
      </div>
      <span>VisionaryTutor · 智眸学伴</span>
    </footer>
  </div>
</template>

<script setup>
import { onMounted, onUnmounted, ref, watch } from "vue";
import { useRoute, useRouter, RouterLink, RouterView } from "vue-router";
import { useAuthStore } from "./stores/authStore.js";
import { useUserProfileStore } from "./stores/userProfile.js";
import { useNotificationStore } from "./stores/notification.js";
import { useLearningSessionStore } from "./stores/learningSession.js";
import { useConfusionOfferStore } from "./stores/confusionOffer.js";
import { toastError, toastSuccess } from "./utils/toast.js";

const route = useRoute();
const router = useRouter();
const authStore = useAuthStore();
const userProfile = useUserProfileStore();
const notificationStore = useNotificationStore();
const learningSession = useLearningSessionStore();
const confusionOffer = useConfusionOfferStore();
const navOpen = ref(false);
let fatigueTimer = null;
let activeLearningStartedAt = Date.now();

const navItems = [
  { path: "/", label: "首页" },
  { path: "/learn", label: "AI 辅导" },
  {
    path: "/questions",
    label: "题库与测评",
    requiresRegistered: true,
    activePrefixes: ["/questions", "/assessment-fill"],
  },
  { path: "/library", label: "教材中心", requiresRegistered: true },
  {
    path: "/labs",
    label: "互动实验",
    requiresRegistered: true,
    activePrefixes: ["/labs", "/code-sandbox"],
  },
  {
    path: "/my-learning",
    label: "我的学习",
    requiresRegistered: true,
    activePrefixes: [
      "/my-learning",
      "/resources",
      "/learning-materials",
      "/learning-plan",
      "/learning-outcomes",
      "/learning-report",
      "/profile",
      "/memory",
      "/privacy",
    ],
  },
];

const personalItems = [
  { path: "/profile-fill", label: "个人中心", requiresRegistered: true },
  { path: "/onboarding", label: "对话建档", requiresRegistered: true },
  { path: "/profile", label: "学习档案", requiresRegistered: true },
  { path: "/memory", label: "记忆管理", requiresRegistered: true },
  { path: "/privacy", label: "隐私中心", requiresRegistered: true },
  { path: "/guide", label: "使用指南" },
];

const navGroups = [
  {
    label: "主要功能",
    items: navItems,
  },
  {
    label: "我的",
    items: personalItems,
  },
];

// Check if route is active
function isActiveRoute(item) {
  if (item.path === "/") {
    return route.path === "/";
  }
  const prefixes = item.activePrefixes || [item.path];
  return prefixes.some((path) => route.path.startsWith(path));
}

function resolveNavTarget(item) {
  if (item.requiresProfile && !userProfile.onboardingComplete) {
    return { path: "/onboarding", query: { unlock: item.path } };
  }
  return item.path;
}

function toggleNav() {
  navOpen.value = !navOpen.value;
}

function closeNav() {
  navOpen.value = false;
}

function showLoginModal() {
  router.push({ name: "auth", query: { mode: "register" } });
  closeNav();
}

function showLoginModalMobile() {
  showLoginModal();
  closeNav();
}

async function handleLogout() {
  const previousUserId = Number(authStore.currentUserId);
  notificationStore.disconnect();
  await learningSession.clearUserState(previousUserId);
  confusionOffer.dismiss();
  userProfile.$reset();
  await authStore.logout();
  userProfile.hydrateFromStorage();
  closeNav();
}

function handleLogoutMobile() {
  handleLogout();
}

function takeLearningBreak() {
  confusionOffer.dismiss();
  sessionStorage.setItem("vt_last_learning_break", new Date().toISOString());
  toastSuccess("已暂停状态提示。活动一下或看看远处，准备好后再继续。");
  activeLearningStartedAt = Date.now();
}

async function handleDifferentExplanation() {
  if (confusionOffer.hasExecutor) {
    await confusionOffer.accept();
    return;
  }
  sessionStorage.setItem(
    "vt_suggested_tutor_prompt",
    "请把我刚才学习的内容换成生活化比喻和最小例子，再给一道自检题。",
  );
  confusionOffer.dismiss();
  await router.push("/learn");
  toastSuccess("已切换到 AI 辅导，可继续用更简单的讲法学习。");
}

onMounted(() => {
  fatigueTimer = window.setInterval(() => {
    const learningRoute =
      /^\/(learn|questions|library|knowledge-content|labs|code-sandbox)/.test(
        route.path,
      );
    if (!learningRoute || document.visibilityState !== "visible") {
      activeLearningStartedAt = Date.now();
      return;
    }
    if (
      Date.now() - activeLearningStartedAt >= 40 * 60 * 1000 &&
      !confusionOffer.active
    ) {
      confusionOffer.signalOffer(
        "本次连续学习已接近 40 分钟，可以休息一下，或让 AI 换一种更轻松的讲法。",
      );
      activeLearningStartedAt = Date.now();
    }
  }, 60 * 1000);
});
onUnmounted(() => {
  if (fatigueTimer) window.clearInterval(fatigueTimer);
});

// Auto-close nav on route change
watch(
  () => route.fullPath,
  () => {
    closeNav();
  },
);

watch(
  () => [authStore.authInitialized, authStore.isRegistered],
  ([initialized, registered]) => {
    if (!initialized) return;
    if (registered) {
      notificationStore.connect();
    } else {
      notificationStore.disconnect();
    }
  },
  { immediate: true },
);

// Close nav on escape key
function handleEscape(e) {
  if (e.key === "Escape" && navOpen.value) {
    closeNav();
  }
}

// Add escape key listener
if (typeof window !== "undefined") {
  window.addEventListener("keydown", handleEscape);

  // Listen for session expired event
  window.addEventListener("auth:sessionExpired", () => {
    router.push({ name: "auth", query: { mode: "login" } });
  });

  window.addEventListener("auth:profileSyncRequested", async (event) => {
    await userProfile.syncFromLearnerOsBackend(event.detail?.userId);
  });

  window.addEventListener("api:error", (event) => {
    const detail = event.detail || {};
    const code = detail.errorCode || "UNKNOWN";
    const status = detail.status || 0;

    // 后端刚启动时常出现 502/503，避免一进页面就弹出干扰 Toast
    if (
      status === 502 ||
      status === 503 ||
      code === "HTTP-502" ||
      code === "HTTP-503"
    ) {
      console.warn("[API] Backend not ready:", detail.message || code);
      return;
    }

    console.warn("[API]", { code, status, traceId: detail.traceId });
    toastError(toUserFacingApiMessage(status, detail.message));
  });
}

function toUserFacingApiMessage(status, fallback) {
  if (status === 400) return fallback || "提交的信息不完整，请检查后重新提交。";
  if (status === 401) return "登录状态已失效，请重新登录。";
  if (status === 403) {
    return authStore.isRegistered
      ? "当前账号没有执行此操作的权限。"
      : "当前为游客身份，请登录或注册后继续。";
  }
  if (status === 404) return "没有找到对应内容，它可能已被移动或删除。";
  if (status >= 500)
    return "服务暂时出现问题，请稍后重试；你的输入内容仍会保留。";
  if (!status) return "网络连接失败，请检查网络后重试。";
  return fallback || "操作未完成，请稍后重试。";
}
</script>

<style scoped>
/* App Shell */
.app-shell {
  position: relative;
  isolation: isolate;
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  background: var(--vt-bg-secondary);
  overflow-x: clip;
}

.site-footer {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--vt-space-4);
  padding: var(--vt-space-5) clamp(var(--vt-space-4), 5vw, var(--vt-space-8));
  border-top: 1px solid var(--vt-border-light);
  color: var(--vt-text-tertiary);
  font-size: var(--vt-text-xs);
}

.site-footer-links {
  display: flex;
  flex-wrap: wrap;
  gap: var(--vt-space-4);
}

.site-footer a {
  color: inherit;
  text-decoration: none;
}

.site-footer a:hover {
  color: var(--vt-accent-teal-dark);
}

/* Soft color blobs behind glass panels */
.app-bg-blobs {
  position: fixed;
  inset: 0;
  z-index: 0;
  pointer-events: none;
  overflow: hidden;
}

.app-blob {
  position: absolute;
  border-radius: 50%;
  filter: blur(88px);
  opacity: 0.5;
  will-change: transform;
}

.app-blob--violet {
  width: min(52vw, 560px);
  height: min(52vw, 560px);
  top: -12%;
  left: -8%;
  background: radial-gradient(
    circle at 40% 40%,
    rgba(196, 181, 253, 0.75) 0%,
    rgba(167, 139, 250, 0.35) 45%,
    transparent 72%
  );
}

.app-blob--teal {
  width: min(48vw, 520px);
  height: min(48vw, 520px);
  top: 28%;
  right: -10%;
  background: radial-gradient(
    circle at 50% 50%,
    rgba(45, 212, 191, 0.55) 0%,
    rgba(13, 148, 136, 0.28) 50%,
    transparent 70%
  );
}

.app-blob--blue {
  width: min(44vw, 480px);
  height: min(44vw, 480px);
  bottom: -14%;
  left: 22%;
  background: radial-gradient(
    circle at 50% 50%,
    rgba(147, 197, 253, 0.6) 0%,
    rgba(99, 102, 241, 0.25) 48%,
    transparent 72%
  );
}

/* Navigation Header */
.nav-header {
  position: sticky;
  top: 0;
  z-index: var(--vt-z-fixed);
  border-bottom: 1px solid rgba(0, 0, 0, 0.06);
  transition: box-shadow var(--vt-transition-base);
}

@media (max-width: 767px) {
  .nav-header {
    background: rgba(255, 255, 255, 0.97);
    -webkit-backdrop-filter: saturate(160%) blur(18px);
    backdrop-filter: saturate(160%) blur(18px);
    box-shadow: 0 8px 24px rgba(15, 23, 42, 0.06);
  }

  .nav-container {
    min-height: 68px;
    padding-top: max(var(--vt-space-3), env(safe-area-inset-top));
  }
}

.nav-header::before {
  content: "";
  position: absolute;
  inset: 0;
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
  pointer-events: none;
}

/* Navigation Container */
.nav-container {
  max-width: var(--vt-max-width);
  margin: 0 auto;
  padding: var(--vt-space-3) var(--vt-space-4);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--vt-space-4);
}

/* Brand */
.brand {
  display: flex;
  align-items: center;
  gap: var(--vt-space-3);
  text-decoration: none;
  transition: opacity var(--vt-transition-fast);
}

.brand:hover {
  opacity: 0.85;
}

.brand-icon {
  width: 36px;
  height: 36px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: var(--vt-radius-md);
  background: var(--vt-accent-teal);
  color: white;
  flex-shrink: 0;
  transition: transform var(--vt-transition-fast);
}

.brand:hover .brand-icon {
  transform: scale(1.05);
}

.brand-icon svg {
  width: 20px;
  height: 20px;
}

.brand-text {
  display: flex;
  align-items: baseline;
  gap: var(--vt-space-2);
  line-height: 1.2;
  white-space: nowrap;
}

.brand-title {
  font-size: var(--vt-text-base);
  font-weight: var(--vt-font-bold);
  color: var(--vt-text-primary);
  letter-spacing: -0.01em;
}

.brand-subtitle {
  font-size: var(--vt-text-xs);
  font-weight: var(--vt-font-medium);
  color: var(--vt-text-tertiary);
}

.brand-subtitle::before {
  content: "·";
  margin-right: var(--vt-space-2);
  color: var(--vt-border-medium);
}

/* Desktop Navigation */
.desktop-nav {
  display: none;
  flex: 1;
  align-items: center;
  justify-content: flex-end;
  gap: var(--vt-space-3);
  min-width: 0;
}

.nav-links {
  display: flex;
  align-items: center;
  flex-wrap: nowrap;
  gap: 2px;
  min-width: 0;
  overflow-x: auto;
  scrollbar-width: none;
}

.nav-links::-webkit-scrollbar {
  display: none;
}

.nav-divider {
  width: 1px;
  height: 16px;
  margin: 0 var(--vt-space-1);
  background: var(--vt-border-light);
  flex-shrink: 0;
}

.nav-lock-icon {
  width: 11px;
  height: 11px;
  flex-shrink: 0;
  opacity: 0.45;
}

.nav-link {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: var(--vt-space-2) var(--vt-space-2);
  font-size: 13px;
  font-weight: var(--vt-font-medium);
  color: var(--vt-text-secondary);
  text-decoration: none;
  border-radius: var(--vt-radius-md);
  transition: all var(--vt-transition-fast);
  position: relative;
  white-space: nowrap;
  flex-shrink: 0;
}

.nav-link:hover {
  color: var(--vt-text-primary);
  background: rgba(0, 0, 0, 0.03);
}

.nav-link-active {
  color: var(--vt-accent-teal);
  background: rgba(13, 148, 136, 0.08);
}

.nav-link-active::after {
  content: "";
  position: absolute;
  bottom: 2px;
  left: 50%;
  transform: translateX(-50%);
  width: 16px;
  height: 3px;
  background: var(--vt-accent-teal);
  border-radius: 2px;
}

/* User Section */
.user-section {
  display: flex;
  align-items: center;
  flex-shrink: 0;
  margin-left: var(--vt-space-2);
  padding-left: var(--vt-space-3);
  border-left: 1px solid var(--vt-border-light);
}

.nav-cta-btn {
  display: flex;
  align-items: center;
  gap: var(--vt-space-2);
  height: 34px;
  padding: 0 var(--vt-space-3);
  font-size: 13px;
  font-weight: var(--vt-font-medium);
  color: white;
  background: var(--vt-accent-teal);
  border: none;
  border-radius: var(--vt-radius-md);
  cursor: pointer;
  transition: all var(--vt-transition-fast);
}

.nav-cta-btn:hover {
  background: var(--vt-accent-teal-dark);
  transform: translateY(-1px);
  box-shadow: 0 4px 12px rgba(13, 148, 136, 0.3);
}

.nav-cta-btn svg {
  width: 16px;
  height: 16px;
}

.user-info {
  display: flex;
  align-items: center;
  gap: var(--vt-space-3);
}

.user-name {
  font-size: var(--vt-text-sm);
  font-weight: var(--vt-font-medium);
  color: var(--vt-text-secondary);
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.nav-ghost-btn {
  width: 36px;
  height: 36px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: transparent;
  border: 1px solid var(--vt-border-light);
  border-radius: var(--vt-radius-md);
  color: var(--vt-text-secondary);
  cursor: pointer;
  transition: all var(--vt-transition-fast);
}

.nav-ghost-btn:hover {
  background: var(--vt-bg-tertiary);
  color: var(--vt-text-primary);
  border-color: var(--vt-border-medium);
}

.nav-ghost-btn svg {
  width: 16px;
  height: 16px;
}

/* Mobile Toggle Button */
.mobile-toggle {
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  gap: 5px;
  width: 44px;
  height: 44px;
  padding: 0;
  background: transparent;
  border: none;
  cursor: pointer;
  z-index: 1;
}

.toggle-bar {
  display: block;
  width: 22px;
  height: 2px;
  background: var(--vt-text-primary);
  border-radius: 2px;
  transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
  transform-origin: center;
}

.toggle-bar:nth-child(1) {
  transform-origin: left center;
}

.toggle-bar:nth-child(2) {
  opacity: 1;
}

.toggle-bar:nth-child(3) {
  transform-origin: left center;
}

.toggle-bar-active:nth-child(1) {
  transform: translateX(2px) rotate(45deg);
  width: 24px;
}

.toggle-bar-active:nth-child(2) {
  opacity: 0;
  transform: scaleX(0);
}

.toggle-bar-active:nth-child(3) {
  transform: translateX(2px) rotate(-45deg);
  width: 24px;
}

/* Mobile Navigation Drawer */
.mobile-nav {
  position: absolute;
  top: 100%;
  left: 0;
  right: 0;
  background: var(--vt-surface);
  border-bottom: 1px solid var(--vt-border-light);
  box-shadow: var(--vt-shadow-lg);
  max-height: calc(100dvh - 64px);
  overflow-x: hidden;
  overflow-y: auto;
}

.mobile-nav-content {
  padding: var(--vt-space-4);
  display: flex;
  flex-direction: column;
  gap: var(--vt-space-2);
}

.mobile-nav-group {
  display: flex;
  flex-direction: column;
  gap: var(--vt-space-1);
}

.mobile-nav-group + .mobile-nav-group {
  margin-top: var(--vt-space-2);
  padding-top: var(--vt-space-2);
  border-top: 1px solid var(--vt-border-light);
}

.mobile-nav-group-label {
  padding: 0 var(--vt-space-4);
  font-size: var(--vt-text-xs);
  font-weight: var(--vt-font-semibold);
  color: var(--vt-text-tertiary);
  text-transform: uppercase;
  letter-spacing: 0.04em;
}

.mobile-nav-link {
  display: flex;
  align-items: center;
  gap: var(--vt-space-3);
  padding: var(--vt-space-4);
  font-size: var(--vt-text-base);
  font-weight: var(--vt-font-medium);
  color: var(--vt-text-primary);
  text-decoration: none;
  border-radius: var(--vt-radius-md);
  transition: all var(--vt-transition-fast);
  background: none;
  border: none;
  width: 100%;
  cursor: pointer;
  text-align: left;
}

.mobile-nav-link:hover {
  background: var(--vt-bg-secondary);
}

.mobile-nav-link-active {
  background: rgba(13, 148, 136, 0.08);
  color: var(--vt-accent-teal);
}

.mobile-nav-link-primary {
  background: var(--vt-accent-teal);
  color: white;
}

.mobile-nav-link-primary:hover {
  background: var(--vt-accent-teal-dark);
}

.mobile-nav-arrow {
  margin-left: auto;
  opacity: 0;
  transform: translateX(-8px);
  transition: all var(--vt-transition-fast);
}

.mobile-nav-link:hover .mobile-nav-arrow {
  opacity: 1;
  transform: translateX(0);
}

.mobile-user-actions {
  margin-top: var(--vt-space-3);
  padding-top: var(--vt-space-3);
  border-top: 1px solid var(--vt-border-light);
}

/* Drawer Animation */
.drawer-enter-active,
.drawer-leave-active {
  transition: all 0.35s cubic-bezier(0.4, 0, 0.2, 1);
  transform-origin: top;
}

.drawer-enter-from,
.drawer-leave-to {
  opacity: 0;
  transform: translateY(-12px) scaleY(0.96);
}

/* Main Content */
.main-content {
  position: relative;
  z-index: 1;
  flex: 1;
  width: 100%;
  max-width: var(--vt-max-width);
  margin: 0 auto;
  padding: var(--vt-space-4);
}

.main-content--landing {
  max-width: none;
  padding: 0;
}

/* Page Transition */
.page-enter-active,
.page-leave-active {
  transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
}

.page-enter-from {
  opacity: 0;
  transform: translateY(8px);
}

.page-leave-to {
  opacity: 0;
  transform: translateY(-8px);
}

/* Responsive Breakpoints */
@media (min-width: 640px) {
  .nav-container {
    padding: var(--vt-space-3) var(--vt-space-6);
  }

  .main-content {
    padding: var(--vt-space-6);
  }

  .main-content--landing {
    padding: 0;
  }
}

@media (max-width: 639px) {
  .site-footer {
    align-items: flex-start;
    flex-direction: column;
  }
}

.global-learning-offer {
  position: fixed;
  right: clamp(1rem, 3vw, 2rem);
  bottom: clamp(1rem, 3vw, 2rem);
  z-index: 1200;
  width: min(440px, calc(100vw - 2rem));
  padding: 1rem;
  display: grid;
  gap: 0.8rem;
  border-color: rgba(13, 148, 136, 0.28);
  box-shadow: 0 18px 55px rgba(15, 23, 42, 0.2);
}

.global-learning-offer strong {
  color: var(--vt-text-primary);
}
.global-learning-offer p {
  margin: 0.35rem 0 0;
  color: var(--vt-text-secondary);
  font-size: 0.84rem;
  line-height: 1.55;
}
.global-learning-offer__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
}

@media (min-width: 768px) {
  .mobile-toggle,
  .mobile-nav {
    display: none;
  }

  .desktop-nav {
    display: flex;
  }

  .nav-container {
    padding: var(--vt-space-3) var(--vt-space-5);
    gap: var(--vt-space-3);
  }
}

@media (min-width: 1024px) {
  .nav-link {
    padding: var(--vt-space-2) var(--vt-space-3);
  }

  .nav-container {
    padding: var(--vt-space-3) var(--vt-space-6);
  }

  .main-content {
    padding: var(--vt-space-8);
  }

  .main-content--landing {
    padding: 0;
  }
}

@media (min-width: 1280px) {
  .nav-container {
    padding: var(--vt-space-3) var(--vt-space-8);
  }
}

/* Dark mode: soften blob intensity on deep backgrounds */
@media (prefers-color-scheme: dark) {
  .app-blob {
    opacity: 0.38;
  }

  .app-blob--violet {
    background: radial-gradient(
      circle at 40% 40%,
      rgba(139, 92, 246, 0.45) 0%,
      rgba(109, 40, 217, 0.2) 45%,
      transparent 72%
    );
  }

  .app-blob--teal {
    background: radial-gradient(
      circle at 50% 50%,
      rgba(20, 184, 166, 0.35) 0%,
      rgba(13, 148, 136, 0.18) 50%,
      transparent 70%
    );
  }

  .app-blob--blue {
    background: radial-gradient(
      circle at 50% 50%,
      rgba(96, 165, 250, 0.35) 0%,
      rgba(59, 130, 246, 0.15) 48%,
      transparent 72%
    );
  }
}

/* High contrast: hide decorative blobs, keep readable structure */
@media (prefers-contrast: more) {
  .app-bg-blobs {
    display: none;
  }
}

/* Reduced Motion */
@media (prefers-reduced-motion: reduce) {
  .toggle-bar,
  .nav-link,
  .mobile-nav-link,
  .brand-icon,
  .brand {
    transition: none;
  }

  .drawer-enter-active,
  .drawer-leave-active,
  .page-enter-active,
  .page-leave-active {
    transition: none;
  }
}
</style>
