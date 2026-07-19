import { computed, ref } from "vue";
import { useRouter } from "vue-router";
import { useAuthStore } from "../stores/authStore";
import { useUserProfileStore } from "../stores/userProfile";
import { useLearningSessionStore } from "../stores/learningSession";
import {
  fetchResourceRecommendations,
  formatApiErrorMessage,
} from "../api/resources";
import { serializeProfileSnapshot } from "../utils/profileSnapshot";
import { toastError, toastSuccess, toastWarning } from "../utils/toast";
import { RESOURCE_TYPE_OPTIONS } from "../constants/resourceTypes";
import { useLearningMetrics } from "./useLearningMetrics";

export function useResourceGeneration(getTopic = null) {
  const router = useRouter();
  const authStore = useAuthStore();
  const userProfile = useUserProfileStore();
  const learningSession = useLearningSessionStore();
  const metrics = useLearningMetrics();
  const recommendations = ref([]);

  const defaultResourceTopic = computed(() => {
    const fromHook = typeof getTopic === "function" ? getTopic() : "";
    return (
      fromHook ||
      learningSession.currentSession?.topic ||
      userProfile.goal ||
      ""
    );
  });

  async function refreshRecommendations() {
    if (!authStore.isRegistered || !learningSession.currentSessionId) {
      recommendations.value = [];
      return;
    }
    try {
      const response = await fetchResourceRecommendations(
        {
          learningSessionId: learningSession.currentSessionId,
          learnerProfileSnapshot: serializeProfileSnapshot(
            userProfile.profileSnapshot,
            userProfile.profileDimensions,
            userProfile.aiTeacherPreferences,
          ),
          weakPointsSnapshot:
            learningSession.weakNodes.map((node) => node.name).join("、") ||
            userProfile.weakPoints.join("、"),
          cognitiveStyle: userProfile.cognitiveStyle,
          recentQuizLow: userProfile.recentQuizLow,
        },
        { silent: true },
      );
      recommendations.value = response.recommendations || [];
    } catch {
      recommendations.value = [];
    }
  }

  async function generateResources(
    options = null,
    { redirectToLibrary = true, startNewSession = false } = {},
  ) {
    if (!authStore.isRegistered) {
      toastWarning("请先注册账号，以生成并保存学习资源", 4000);
      router.push({
        path: "/auth",
        query: { mode: "register", redirect: "/resources" },
      });
      return false;
    }

    const topic = options?.topic?.trim();
    const resourceTypes = options?.resourceTypes;
    if (!topic || !Array.isArray(resourceTypes) || !resourceTypes.length) {
      if (redirectToLibrary) {
        router.push("/resources");
      }
      toastWarning("请在资源库填写主题并选择要生成的资源类型", 4000);
      return false;
    }

    try {
      // Scoped resource workspaces use one durable session per generation,
      // so previous resources behave like separate conversations and retain
      // their own topic instead of being overwritten by the newest request.
      if (startNewSession) {
        await learningSession.startNewSession(topic);
      }
      const response = await learningSession.generatePersonalizedResources({
        topic,
        resourceTypes,
        profileSnapshot: serializeProfileSnapshot(
          userProfile.profileSnapshot,
          userProfile.profileDimensions,
          userProfile.aiTeacherPreferences,
        ),
        weakPointsSnapshot: learningSession.weakNodes
          .map((node) => `${node.name}(${node.layer})`)
          .join("、"),
        emotionSnapshot: `${userProfile.emotionState} / ${userProfile.attentionState}`,
      });
      metrics.record("RESOURCE_GENERATION", {
        textValue: resourceTypes.join(","),
        source: "resource_factory",
      });
      await refreshRecommendations();
      const labels = resourceTypes
        .map(
          (type) =>
            RESOURCE_TYPE_OPTIONS.find((item) => item.type === type)?.label ||
            type,
        )
        .join("、");
      const returnedArtifacts = Array.isArray(response?.artifacts)
        ? response.artifacts
        : [];
      const visibleArtifacts = returnedArtifacts.filter(
        (item) => String(item?.publishStatus || "").toUpperCase() !== "BLOCKED",
      );
      if (returnedArtifacts.length > 0 && visibleArtifacts.length === 0) {
        toastWarning(
          "生成任务已完成，但结果未通过发布校验，因此没有进入资源库。请重试；若持续出现，请确认后端已更新为“模型直出、RAG 可选”版本。",
          7000,
        );
      } else {
        toastSuccess(`已生成：${labels}。资源已自动打开`, 5000);
      }
      if (redirectToLibrary) {
        router.push("/resources");
      }
      return true;
    } catch (error) {
      const message = formatApiErrorMessage(
        error,
        "资源生成失败，请确认后端服务已启动后重试",
      );
      toastError(
        message.includes("502") || message.includes("Failed to fetch")
          ? "后端服务未响应（502），请确认 Spring Boot 已在 8080 端口运行"
          : message,
        6000,
      );
      console.error("[useResourceGeneration] generateResources failed:", error);
      return false;
    }
  }

  return {
    recommendations,
    defaultResourceTopic,
    refreshRecommendations,
    generateResources,
  };
}
