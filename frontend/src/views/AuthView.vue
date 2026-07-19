<template>
  <section class="auth-page vt-card vt-container-narrow">
    <header class="page-header">
      <span class="vt-eyebrow">账户</span>
      <h1 class="vt-title">
        {{ isResetMode ? "找回密码" : isLoginMode ? "登录" : "注册" }}
      </h1>
      <p class="vt-text-muted">使用独立页面完成认证，无弹窗遮罩。</p>
    </header>

    <div v-if="restrictedTarget" class="restricted-notice" role="status">
      <strong>当前为游客体验</strong>
      <p>
        该功能会保存个人学习数据，需要登录或注册。注册成功后会回到原页面，并迁移本次游客对话与学习记录。
      </p>
      <RouterLink to="/guide">查看公开使用指南</RouterLink>
    </div>

    <div
      v-if="authStore.guestSessionError && !authStore.isLoggedIn"
      class="guest-session-alert"
      role="alert"
    >
      <p>{{ authStore.guestSessionError }}</p>
      <button
        type="button"
        class="vt-btn vt-btn-outline vt-btn-sm"
        :disabled="authStore.isLoading"
        @click="retryGuest"
      >
        {{ authStore.isLoading ? "连接中..." : "重试连接" }}
      </button>
    </div>

    <div v-if="!isResetMode" class="form-tabs">
      <button
        type="button"
        class="tab-btn"
        :class="{ 'tab-active': isLoginMode }"
        @click="switchMode('login')"
      >
        登录
      </button>
      <button
        type="button"
        class="tab-btn"
        :class="{ 'tab-active': !isLoginMode }"
        @click="switchMode('register')"
      >
        注册
      </button>
    </div>

    <form
      v-if="isResetMode"
      class="auth-form"
      @submit.prevent="handlePasswordReset"
    >
      <template v-if="!resetToken">
        <p class="field-hint reset-hint">
          输入注册邮箱，我们会发送一封 15
          分钟内有效的重置邮件。为保护账号隐私，无论邮箱是否存在，页面都会显示相同结果。
        </p>
        <label class="vt-label" for="reset-email">注册邮箱</label>
        <input
          id="reset-email"
          v-model.trim="resetForm.email"
          class="vt-input"
          type="email"
          autocomplete="email"
          required
          :disabled="resetLoading"
        />
      </template>
      <template v-else>
        <label class="vt-label" for="reset-password">新密码</label>
        <div class="password-control">
          <input
            id="reset-password"
            v-model="resetForm.newPassword"
            class="vt-input"
            :type="showResetPassword ? 'text' : 'password'"
            minlength="8"
            maxlength="128"
            autocomplete="new-password"
            required
            :disabled="resetLoading"
          />
          <button
            type="button"
            :aria-label="showResetPassword ? '隐藏密码' : '显示密码'"
            @click="showResetPassword = !showResetPassword"
          >
            {{ showResetPassword ? "隐藏" : "显示" }}
          </button>
        </div>
        <label class="vt-label" for="reset-password-confirm">确认新密码</label>
        <div class="password-control">
          <input
            id="reset-password-confirm"
            v-model="resetForm.confirmPassword"
            class="vt-input"
            :type="showResetConfirmPassword ? 'text' : 'password'"
            minlength="8"
            maxlength="128"
            autocomplete="new-password"
            required
            :disabled="resetLoading"
          />
          <button
            type="button"
            :aria-label="
              showResetConfirmPassword ? '隐藏确认密码' : '显示确认密码'
            "
            @click="showResetConfirmPassword = !showResetConfirmPassword"
          >
            {{ showResetConfirmPassword ? "隐藏" : "显示" }}
          </button>
        </div>
      </template>

      <p v-if="resetMessage" class="reset-message" role="status">
        {{ resetMessage }}
      </p>
      <p v-if="resetError" class="form-error" role="alert">{{ resetError }}</p>
      <button
        type="submit"
        class="vt-btn vt-btn-primary"
        :disabled="resetLoading || !resetReady"
      >
        {{
          resetLoading
            ? "处理中..."
            : resetToken
              ? "确认重置密码"
              : "发送重置邮件"
        }}
      </button>
      <button type="button" class="vt-btn vt-btn-ghost" @click="leaveResetMode">
        返回登录
      </button>
    </form>

    <form
      v-else-if="isLoginMode"
      class="auth-form"
      @submit.prevent="handleLogin"
    >
      <label class="vt-label" for="login-username">用户名</label>
      <input
        id="login-username"
        v-model="loginForm.username"
        class="vt-input"
        type="text"
        autocomplete="username"
        required
        :disabled="authStore.isLoading"
      />

      <label class="vt-label" for="login-password">密码</label>
      <div class="password-control">
        <input
          id="login-password"
          v-model="loginForm.password"
          class="vt-input"
          :type="showLoginPassword ? 'text' : 'password'"
          autocomplete="current-password"
          required
          :disabled="authStore.isLoading"
        />
        <button
          type="button"
          :aria-label="showLoginPassword ? '隐藏密码' : '显示密码'"
          @click="showLoginPassword = !showLoginPassword"
        >
          {{ showLoginPassword ? "隐藏" : "显示" }}
        </button>
      </div>

      <p v-if="authStore.authError" class="form-error">
        {{ authStore.authError }}
      </p>

      <button
        type="submit"
        class="vt-btn vt-btn-primary"
        :disabled="
          authStore.isLoading || !loginForm.username || !loginForm.password
        "
      >
        {{ authStore.isLoading ? "登录中..." : "登录" }}
      </button>
      <button type="button" class="forgot-link" @click="enterResetMode">
        忘记密码？
      </button>
    </form>

    <form v-else class="auth-form" @submit.prevent="handleRegister">
      <label class="vt-label" for="reg-username">用户名</label>
      <input
        id="reg-username"
        v-model.trim="registerForm.username"
        class="vt-input"
        type="text"
        minlength="2"
        maxlength="12"
        pattern="[\p{L}\p{N}_]+"
        autocomplete="username"
        required
        :disabled="authStore.isLoading"
      />
      <span class="field-hint">
        支持中文、字母、数字和下划线，2–12 个字符（{{
          registerForm.username.length
        }}/12）
      </span>

      <label class="vt-label" for="reg-password">密码</label>
      <div class="password-control">
        <input
          id="reg-password"
          v-model="registerForm.password"
          class="vt-input"
          :type="showRegisterPassword ? 'text' : 'password'"
          minlength="8"
          maxlength="128"
          autocomplete="new-password"
          required
          :disabled="authStore.isLoading"
        />
        <button
          type="button"
          :aria-label="showRegisterPassword ? '隐藏密码' : '显示密码'"
          @click="showRegisterPassword = !showRegisterPassword"
        >
          {{ showRegisterPassword ? "隐藏" : "显示" }}
        </button>
      </div>
      <span class="field-hint">至少 8 位，建议同时使用字母、数字和符号</span>

      <label class="vt-label" for="reg-password-confirm">确认密码</label>
      <div class="password-control">
        <input
          id="reg-password-confirm"
          v-model="registerForm.confirmPassword"
          class="vt-input"
          :type="showRegisterConfirmPassword ? 'text' : 'password'"
          minlength="8"
          maxlength="128"
          autocomplete="new-password"
          required
          :aria-invalid="Boolean(registrationPasswordError)"
          :disabled="authStore.isLoading"
        />
        <button
          type="button"
          :aria-label="
            showRegisterConfirmPassword ? '隐藏确认密码' : '显示确认密码'
          "
          @click="showRegisterConfirmPassword = !showRegisterConfirmPassword"
        >
          {{ showRegisterConfirmPassword ? "隐藏" : "显示" }}
        </button>
      </div>
      <span v-if="registrationPasswordError" class="form-error" role="alert">{{
        registrationPasswordError
      }}</span>

      <label class="vt-label" for="reg-email"
        >邮箱（可选，仅用于找回密码）</label
      >
      <input
        id="reg-email"
        v-model.trim="registerForm.email"
        class="vt-input"
        type="email"
        autocomplete="email"
        :aria-invalid="Boolean(registrationEmailError)"
        :disabled="authStore.isLoading"
      />
      <span class="field-hint"
        >只检查邮箱格式，不发送验证码。QQ 邮箱的 @qq.com 前应为 5–12
        位数字，例如 1234567890@qq.com。</span
      >
      <span v-if="registrationEmailError" class="form-error" role="alert">{{
        registrationEmailError
      }}</span>

      <div class="captcha-field">
        <label class="vt-label" for="reg-captcha">图形验证码</label>
        <div class="captcha-row">
          <input
            id="reg-captcha"
            v-model.trim="registerForm.captchaAnswer"
            class="vt-input"
            type="text"
            inputmode="text"
            maxlength="8"
            autocomplete="off"
            required
            :disabled="authStore.isLoading || captchaLoading"
          />
          <button
            type="button"
            class="captcha-image-button"
            :disabled="captchaLoading"
            title="看不清，点击刷新"
            aria-label="刷新图形验证码"
            @click="loadCaptcha"
          >
            <img
              v-if="captcha.imageDataUrl"
              :src="captcha.imageDataUrl"
              alt="图形验证码，点击可刷新"
            />
            <span v-else>{{ captchaLoading ? "加载中…" : "点击刷新" }}</span>
          </button>
        </div>
      </div>

      <label class="human-check">
        <input
          v-model="registerForm.termsAccepted"
          type="checkbox"
          :disabled="authStore.isLoading"
        />
        <span>
          我已阅读并同意平台的
          <RouterLink to="/legal">用户协议与隐私说明</RouterLink>
        </span>
      </label>

      <p v-if="authStore.authError" class="form-error">
        {{ authStore.authError }}
      </p>

      <button
        type="submit"
        class="vt-btn vt-btn-primary"
        :disabled="authStore.isLoading || !registerReady"
      >
        {{ authStore.isLoading ? "注册中..." : "注册" }}
      </button>
    </form>

    <RouterLink class="vt-btn vt-btn-outline auth-back" to="/"
      >返回首页</RouterLink
    >
  </section>
</template>

<script setup>
import { computed, onMounted, ref, watch } from "vue";
import { useRoute, useRouter, RouterLink } from "vue-router";
import { useAuthStore } from "../stores/authStore";
import { useUserProfileStore } from "../stores/userProfile";
import {
  confirmPasswordReset,
  fetchRegistrationCaptcha,
  requestPasswordReset,
} from "../api/auth";
import { safeNavigate } from "../utils/safeNavigate";

const route = useRoute();
const router = useRouter();
const authStore = useAuthStore();
const userProfile = useUserProfileStore();
const restrictedTarget = computed(
  () =>
    typeof route.query.redirect === "string" &&
    route.query.redirect.startsWith("/"),
);

const loginForm = ref({ username: "", password: "" });
const registerForm = ref({
  username: "",
  password: "",
  confirmPassword: "",
  email: "",
  displayName: "",
  captchaAnswer: "",
  termsAccepted: false,
});
const captcha = ref({ captchaId: "", imageDataUrl: "" });
const captchaLoading = ref(false);
const isResetMode = ref(route.query.mode === "reset");
const resetLoading = ref(false);
const resetMessage = ref("");
const resetError = ref("");
const resetForm = ref({ email: "", newPassword: "", confirmPassword: "" });
const showLoginPassword = ref(false);
const showRegisterPassword = ref(false);
const showRegisterConfirmPassword = ref(false);
const showResetPassword = ref(false);
const showResetConfirmPassword = ref(false);

const isLoginMode = computed(() => authStore.authModalMode === "login");
const resetToken = computed(() =>
  typeof route.query.token === "string" ? route.query.token.trim() : "",
);
const resetReady = computed(() =>
  resetToken.value
    ? resetForm.value.newPassword.length >= 8 &&
      resetForm.value.newPassword === resetForm.value.confirmPassword
    : /\S+@\S+\.\S+/.test(resetForm.value.email),
);
const registrationEmailError = computed(() => {
  const email = registerForm.value.email.trim();
  if (!email) return "";
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) return "邮箱格式不正确";

  const separator = email.lastIndexOf("@");
  const localPart = email.slice(0, separator);
  const domain = email.slice(separator + 1).toLowerCase();
  if (domain === "qq.com" && !/^[1-9]\d{4,11}$/.test(localPart)) {
    return "QQ 邮箱的 @qq.com 前应为 5–12 位数字";
  }
  return "";
});
const registrationPasswordError = computed(() => {
  if (!registerForm.value.confirmPassword) return "";
  return registerForm.value.password === registerForm.value.confirmPassword
    ? ""
    : "两次输入的密码不一致";
});
const registerReady = computed(
  () =>
    registerForm.value.username.length >= 2 &&
    registerForm.value.password.length >= 8 &&
    registerForm.value.password === registerForm.value.confirmPassword &&
    registerForm.value.captchaAnswer.length >= 4 &&
    registerForm.value.termsAccepted &&
    Boolean(captcha.value.captchaId) &&
    !registrationEmailError.value,
);

watch(
  () => route.query.mode,
  (mode) => {
    isResetMode.value = mode === "reset";
    if (mode === "login" || mode === "register") {
      authStore.authModalMode = mode;
    }
  },
  { immediate: true },
);

function switchMode(mode) {
  isResetMode.value = false;
  authStore.authModalMode = mode;
  authStore.authError = null;
  if (mode === "register" && !captcha.value.captchaId) {
    void loadCaptcha();
  }
}

function enterResetMode() {
  isResetMode.value = true;
  resetMessage.value = "";
  resetError.value = "";
  router.replace({
    path: "/auth",
    query: { ...route.query, mode: "reset", token: undefined },
  });
}

function leaveResetMode() {
  isResetMode.value = false;
  resetMessage.value = "";
  resetError.value = "";
  authStore.authModalMode = "login";
  router.replace({
    path: "/auth",
    query: { mode: "login", redirect: route.query.redirect },
  });
}

async function handlePasswordReset() {
  if (!resetReady.value || resetLoading.value) return;
  resetLoading.value = true;
  resetError.value = "";
  resetMessage.value = "";
  try {
    if (resetToken.value) {
      const result = await confirmPasswordReset(
        resetToken.value,
        resetForm.value.newPassword,
      );
      resetMessage.value = result.message || "密码已重置，请使用新密码登录";
      window.setTimeout(() => leaveResetMode(), 1200);
    } else {
      const result = await requestPasswordReset(resetForm.value.email);
      resetMessage.value =
        result.message || "如果该邮箱已注册，重置邮件将在几分钟内送达";
    }
  } catch (error) {
    resetError.value =
      error?.response?.data?.message ||
      error?.message ||
      "密码重置失败，请稍后重试";
  } finally {
    resetLoading.value = false;
  }
}

async function loadCaptcha() {
  if (captchaLoading.value) return;
  captchaLoading.value = true;
  registerForm.value.captchaAnswer = "";
  try {
    captcha.value = await fetchRegistrationCaptcha();
  } catch {
    captcha.value = { captchaId: "", imageDataUrl: "" };
    authStore.authError = "验证码加载失败，请检查网络后重试";
  } finally {
    captchaLoading.value = false;
  }
}

function resolvePostAuthPath({ newRegistration = false } = {}) {
  const redirect = route.query.redirect;
  if (typeof redirect === "string" && redirect.startsWith("/")) {
    return redirect;
  }
  return newRegistration ? "/?welcome=1" : "/learn";
}

async function goAfterAuth(options = {}) {
  userProfile.hydrateFromStorage();
  userProfile.syncAccountFromAuth();
  await userProfile.bootstrapSession();
  // Keep the authenticated Pinia state alive; safeNavigate already performs a hard reload if routing fails.
  await safeNavigate(router, resolvePostAuthPath(options), {
    forceReload: false,
  });
}

async function retryGuest() {
  await authStore.retryGuestSession();
}

onMounted(async () => {
  const mode = route.query.mode;
  if (mode === "login" || mode === "register") {
    authStore.authModalMode = mode;
  }

  authStore.reloadFromStorage();
  userProfile.hydrateFromStorage();

  if (authStore.isRegistered) {
    await goAfterAuth();
    return;
  }

  if (!authStore.isLoggedIn) {
    await authStore.retryGuestSession();
  }
  if (!isLoginMode.value) {
    await loadCaptcha();
  }
});

async function handleLogin() {
  const ok = await authStore.signin({
    username: loginForm.value.username,
    password: loginForm.value.password,
  });
  if (ok) {
    loginForm.value = { username: "", password: "" };
    await goAfterAuth();
  }
}

async function handleRegister() {
  const ok = await authStore.signup({
    username: registerForm.value.username,
    password: registerForm.value.password,
    email: registerForm.value.email || undefined,
    displayName: registerForm.value.displayName || undefined,
    captchaId: captcha.value.captchaId,
    captchaAnswer: registerForm.value.captchaAnswer,
    termsAccepted: registerForm.value.termsAccepted,
  });
  if (ok) {
    registerForm.value = {
      username: "",
      password: "",
      confirmPassword: "",
      email: "",
      displayName: "",
      captchaAnswer: "",
      termsAccepted: false,
    };
    await goAfterAuth({ newRegistration: true });
  } else {
    await loadCaptcha();
  }
}
</script>

<style scoped>
.auth-page {
  display: grid;
  gap: var(--vt-space-5);
  padding: var(--vt-space-8);
  max-width: 480px;
  margin: 0 auto;
}

.password-control {
  position: relative;
}

.password-control .vt-input {
  width: 100%;
  padding-right: 4rem;
}

.password-control button {
  position: absolute;
  top: 50%;
  right: var(--vt-space-3);
  transform: translateY(-50%);
  padding: 4px 6px;
  border: 0;
  background: transparent;
  color: var(--vt-accent-teal-dark);
  cursor: pointer;
  font: inherit;
  font-size: var(--vt-text-xs);
  font-weight: var(--vt-font-semibold);
}

.guest-session-alert {
  display: grid;
  gap: var(--vt-space-3);
  padding: var(--vt-space-4);
  border-radius: var(--vt-radius-md);
  background: rgba(245, 158, 11, 0.1);
  border: 1px solid rgba(245, 158, 11, 0.35);
  font-size: var(--vt-text-sm);
  color: #b45309;
}

.restricted-notice {
  padding: var(--vt-space-4);
  border: 1px solid rgba(13, 148, 136, 0.28);
  border-radius: var(--vt-radius-md);
  background: rgba(13, 148, 136, 0.06);
}
.restricted-notice p {
  margin: 0.35rem 0;
  color: var(--vt-text-secondary);
  font-size: var(--vt-text-sm);
  line-height: 1.55;
}
.restricted-notice a {
  color: var(--vt-accent-teal-dark);
  font-size: var(--vt-text-sm);
  font-weight: 650;
}

.form-tabs {
  display: flex;
  gap: var(--vt-space-2);
}

.tab-btn {
  flex: 1;
  padding: var(--vt-space-2) var(--vt-space-4);
  border: 1px solid var(--vt-border-light);
  border-radius: var(--vt-radius-md);
  background: transparent;
  cursor: pointer;
}

.tab-active {
  background: var(--vt-accent-teal);
  color: white;
  border-color: var(--vt-accent-teal);
}

.auth-form {
  display: grid;
  gap: var(--vt-space-4);
}

.form-error {
  color: #dc2626;
  font-size: var(--vt-text-sm);
}

.reset-message {
  margin: 0;
  padding: var(--vt-space-3);
  border-radius: var(--vt-radius-md);
  color: #0f766e;
  background: rgba(13, 148, 136, 0.1);
  font-size: var(--vt-text-sm);
}

.reset-hint {
  margin-top: 0;
  line-height: 1.6;
}

.forgot-link {
  justify-self: end;
  padding: 0;
  border: 0;
  color: var(--vt-accent-teal-dark);
  background: transparent;
  cursor: pointer;
}

.field-hint {
  margin-top: calc(var(--vt-space-3) * -1);
  color: var(--vt-text-tertiary);
  font-size: var(--vt-text-xs);
}

.captcha-field {
  display: grid;
  gap: var(--vt-space-2);
}

.captcha-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 132px;
  gap: var(--vt-space-2);
  align-items: stretch;
}

.captcha-image-button {
  min-height: 44px;
  padding: 0;
  overflow: hidden;
  border: 1px solid var(--vt-border-light);
  border-radius: var(--vt-radius-md);
  background: var(--vt-bg-secondary);
  cursor: pointer;
}

.captcha-image-button img {
  display: block;
  width: 100%;
  height: 44px;
}

@media (max-width: 480px) {
  .captcha-row {
    grid-template-columns: 1fr;
  }

  .captcha-image-button,
  .captcha-image-button img {
    width: 150px;
    height: 48px;
  }
}

.human-check {
  display: flex;
  align-items: flex-start;
  gap: var(--vt-space-2);
  color: var(--vt-text-secondary);
  font-size: var(--vt-text-sm);
  line-height: 1.5;
}

.auth-back {
  justify-self: start;
}
</style>
