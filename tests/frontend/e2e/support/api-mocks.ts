import type { Page, Route } from '../../../../frontend/node_modules/@playwright/test'

export const MOCK_GUEST_TOKEN = 'e2e-guest-jwt'
export const MOCK_USER_TOKEN = 'e2e-user-jwt'
export const MOCK_GUEST_ID = 'gst_e2e_guest_001'
export const MOCK_USER_ID = 42

export const MOCK_GUEST_SESSION = {
  token: MOCK_GUEST_TOKEN,
  guestId: MOCK_GUEST_ID,
  expiresAt: new Date(Date.now() + 3_600_000).toISOString(),
  chatQuota: { usedTurns: 0, maxTurns: 5, remainingTurns: 5, sessionTtlSeconds: 3600 },
}

export const MOCK_USER = {
  id: MOCK_USER_ID,
  username: 'e2e_user',
  email: 'e2e@visionary.test',
  displayName: 'E2E Tester',
}

export const MOCK_LOGIN_RESPONSE = {
  token: MOCK_USER_TOKEN,
  tokenType: 'Bearer',
  expiresIn: 3600,
  isGuest: false,
  user: MOCK_USER,
  migration: { migrated: true, fromGuestId: MOCK_GUEST_ID, migratedSessionsCount: 1, migratedReportsCount: 0 },
}

export const MOCK_RAG_CITATIONS = [
  {
    citationId: 'CV-001',
    layer: 'concept',
    source: 'cnn-padding-stride',
    excerpt: 'padding 与 stride 决定特征图尺寸',
  },
  {
    citationId: 'CV-002',
    layer: 'math',
    source: 'conv-output-size',
    excerpt: '输出尺寸 = (输入 - 核 + 2*填充) / 步长 + 1',
  },
]

export const MOCK_GENERATED_ARTIFACTS = [
  {
    id: 101,
    artifactType: 'HANDOUT',
    title: 'CNN 卷积讲义',
    contentMarkdown: '# 卷积与特征图\n\n输出尺寸公式推导示例。',
    contentJson: JSON.stringify({
      origin: 'LIVE',
      generation_mode: 'REACT_MULTI_AGENT',
      agent: 'DocAgent',
      degraded: false,
    }),
    validationStatus: 'VERIFIED',
    publishStatus: 'PUBLISHED',
  },
  {
    id: 102,
    artifactType: 'QUIZ',
    title: '卷积尺寸练习',
    contentMarkdown: '1. 给定 32×32 输入，3×3 核，padding=1，stride=2，输出尺寸？',
    contentJson: JSON.stringify({
      origin: 'DEGRADED',
      generation_mode: 'LEGACY_FALLBACK',
      agent: 'QuizAgent',
      degraded: true,
      fallback_reason: 'E2E fallback evidence',
    }),
    validationStatus: 'NEEDS_HUMAN_REVIEW',
    publishStatus: 'DEGRADED',
  },
  {
    id: 103,
    artifactType: 'VIDEO_SCRIPT',
    title: 'CNN 卷积教学分镜',
    contentMarkdown: '## 镜头 1\n\n用网格动画展示卷积核滑动。\n\n## 镜头 2\n\n对比 padding 与 stride。',
    contentJson: JSON.stringify({
      origin: 'LIVE',
      generation_mode: 'REACT_MULTI_AGENT',
      agent: 'VideoScriptAgent',
      degraded: false,
      summary: '用动态网格演示卷积核、padding 与 stride 对特征图尺寸的影响。',
      script_content: '## 镜头 1\n\n用网格动画展示卷积核滑动。\n\n## 镜头 2\n\n对比 padding 与 stride。',
    }),
    validationStatus: 'VERIFIED',
    publishStatus: 'PUBLISHED',
    mediaStatus: 'UNCONFIGURED',
  },
]

export type MockHandlers = Record<string, (route: Route) => Promise<void> | void>

const MOCK_GENERATION_RESPONSE = {
  runId: 'run-e2e-001',
  reviewSummary: '2 项真实生成资源已通过审查；1 项降级资源需复核后使用。',
  steps: [
    {
      id: 'run-e2e-001-1-SupervisorAgent',
      agentName: 'SupervisorAgent',
      stepOrder: 1,
      outputSummary: '已检索 CNN 讲义并分派 QuizAgent',
      critique: 'query_chroma_db → delegate_to_quiz_agent',
    },
    {
      id: 'run-e2e-001-2-QuizAgent',
      agentName: 'QuizAgent',
      stepOrder: 2,
      outputSummary: '已生成 3 道卷积尺寸练习题',
      critique: '降级模板，需复核',
    },
  ],
  artifacts: MOCK_GENERATED_ARTIFACTS,
}

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({
    status,
    contentType: 'application/json',
    body: JSON.stringify(body),
  })
}

function sse(route: Route, events: Array<{ event: string; data: string }>) {
  const body = events.map((item) => `event: ${item.event}\ndata: ${item.data}\n\n`).join('')
  return route.fulfill({
    status: 200,
    headers: {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache',
      Connection: 'keep-alive',
    },
    body,
  })
}

function apiPath(url: string): string {
  const parsed = new URL(url)
  return parsed.pathname.replace(/^\/api/, '') || '/'
}

const defaultHandlers: MockHandlers = {
  'GET /tts/health': (route) => json(route, { available: false, provider: 'mock' }),

  'GET /health': (route) => json(route, {
    status: 'UP',
    chromaAvailable: true,
    timestamp: new Date().toISOString(),
  }),

  'POST /auth/guest': (route) => json(route, MOCK_GUEST_SESSION),

  'GET /auth/guest/quota': (route) => json(route, {
    usedTurns: 0,
    maxTurns: 5,
    remainingTurns: 5,
    sessionTtlSeconds: 3600,
  }),

  'GET /auth/validate': (route) => {
    const auth = route.request().headers().authorization || ''
    const isGuest = auth.includes(MOCK_GUEST_TOKEN)
    const isUser = auth.includes(MOCK_USER_TOKEN)
    if (!isGuest && !isUser) {
      return json(route, { valid: false, message: 'invalid token' }, 401)
    }
    return json(route, {
      valid: true,
      message: 'ok',
      type: isGuest ? 'guest' : 'user',
      subject: isGuest ? MOCK_GUEST_ID : String(MOCK_USER_ID),
    })
  },

  'POST /auth/login': (route) => json(route, MOCK_LOGIN_RESPONSE),

  'POST /auth/register': (route) => json(route, MOCK_LOGIN_RESPONSE),

  'GET /auth/captcha': (route) => json(route, {
    captchaId: 'e2e-captcha-id',
    imageDataUrl: 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M/wHwAF/gL+XhFzWQAAAABJRU5ErkJggg==',
  }),

  'POST /auth/refresh': (route) => json(route, {
    token: route.request().headers().authorization?.includes(MOCK_USER_TOKEN)
      ? MOCK_USER_TOKEN
      : MOCK_GUEST_TOKEN,
    tokenType: 'Bearer',
    expiresIn: 3600,
  }),

  'POST /auth/logout': (route) => json(route, { success: true, message: 'ok' }),

  'GET /learner/state': (route) => json(route, {
    userId: MOCK_USER_ID,
    profileVersion: 2,
    pathVersion: 1,
    onboardingComplete: true,
    profileSnapshot: {
      goal: '掌握 CNN 卷积与特征图尺寸推导',
      cognitiveStyle: '视觉型',
      weakPoints: ['卷积核维度计算'],
    },
    learningGoal: '掌握 CNN 卷积与特征图尺寸推导',
    lastPolicyReason: '',
    pendingRecommendationPush: null,
  }),

  'POST /profile/extract': (route) => json(route, {
    extracted: true,
    profileVersion: 2,
  }),

  'POST /profile/validate-onboarding-answer': (route) => json(route, {
    valid: true,
    reason: '',
    aiUsed: false,
  }),

  'GET /learning-sessions': (route) => json(route, [{
    id: 9001,
    userId: MOCK_USER_ID,
    topic: 'CNN 特征图尺寸',
    status: 'ACTIVE',
    currentPhase: 'RESOURCE_GENERATION',
    streamingHandout: '',
  }]),

  'POST /learning-sessions': (route) => json(route, {
    id: 9001,
    userId: MOCK_USER_ID,
    topic: 'CNN 特征图尺寸',
    status: 'ACTIVE',
    currentPhase: 'STUDENT_PROFILE',
    streamingHandout: '',
  }),

  'PUT /learning-sessions/9001': (route) => json(route, { id: 9001, updated: true }),

  'GET /diagnostic-reports': (route) => json(route, []),

  'GET /resources': (route) => json(route, MOCK_GENERATED_ARTIFACTS),

  'GET /resources/recommendations': (route) => json(route, {
    recommendations: [{
      id: 'rec-quiz-1',
      artifactType: 'QUIZ',
      title: '卷积尺寸小测',
      reason: '薄弱点：卷积核维度计算',
      score: 0.92,
    }],
  }),

  'POST /resources/metrics/record': (route) => json(route, { recorded: true }),

  'POST /resources/generate/jobs': (route) => json(route, {
    taskId: 'res-e2e-001',
    learningSessionId: 9001,
    status: 'QUEUED',
    progressPercent: 0,
    message: '资源生成任务已创建',
    events: [],
    estimatedRemainingSeconds: 60,
    retryable: false,
  }),

  'GET /resources/generate/jobs/:taskId': (route) => json(route, {
    taskId: 'res-e2e-001',
    learningSessionId: 9001,
    status: 'SUCCEEDED',
    progressPercent: 100,
    message: MOCK_GENERATION_RESPONSE.reviewSummary,
    runId: MOCK_GENERATION_RESPONSE.runId,
    events: [
      {
        runId: 'run-e2e-001',
        phase: 'RETRIEVE',
        agentName: 'SupervisorAgent',
        stepOrder: 1,
        message: '已检索 CNN 讲义并分派 QuizAgent',
        detail: 'query_chroma_db → delegate_to_quiz_agent',
        progressPercent: 35,
      },
      {
        runId: 'run-e2e-001',
        phase: 'GENERATE',
        agentName: 'QuizAgent',
        stepOrder: 2,
        message: '已生成 3 道卷积尺寸练习题',
        detail: 'degraded_review_required',
        progressPercent: 72,
      },
    ],
    response: MOCK_GENERATION_RESPONSE,
    estimatedRemainingSeconds: 0,
    retryable: false,
  }),

  'POST /resources/learning/assess': (route) => json(route, {
    summary: '前测后系统识别出卷积尺寸公式薄弱点；完成讲义、练习与路径后，后测表现明显提升。',
    metricsSummary: '前测 52% → 后测 78%（+26%）',
    llmUsed: false,
    detail: {
      insufficientData: false,
      meaningfulMetricCount: 6,
      prePostSummary: {
        preTestAverage: 52,
        postTestAverage: 78,
        delta: 26,
        preTestCount: 1,
        postTestCount: 1,
        comparable: true,
      },
      masteryCurve: [
        { concept: 'CNN 卷积尺寸', metricType: 'PRE_TEST', masteryPercent: 52, eventTime: '2026-06-01T10:00:00Z' },
        { concept: 'CNN 卷积尺寸', metricType: 'QUIZ_ACCURACY', masteryPercent: 65, eventTime: '2026-06-02T10:00:00Z' },
        { concept: 'CNN 卷积尺寸', metricType: 'POST_TEST', masteryPercent: 78, eventTime: '2026-06-03T10:00:00Z' },
      ],
      radarData: [
        { axis: '概念理解', value: 78 },
        { axis: '公式推导', value: 72 },
        { axis: '代码实操', value: 66 },
        { axis: '练习正确率', value: 78 },
        { axis: '学习持续性', value: 74 },
      ],
      suggestions: ['继续完成两道变式题，巩固 stride 与 padding 的组合计算。'],
      shouldReplan: false,
      replanReason: '',
    },
  }),

  'GET /resources/knowledge-tracing/radar': (route) => json(route, {
    insufficientData: false,
    meaningfulCount: 6,
    concepts: [
      { concept: '卷积尺寸公式', score: 0.78 },
      { concept: 'Padding', score: 0.82 },
      { concept: 'Stride', score: 0.74 },
    ],
  }),

  'POST /stream/chat': (route) => sse(route, [
    { event: 'memory_status', data: '{"status":"searching"}' },
    {
      event: 'rag_context',
      data: JSON.stringify({
        grounded: true,
        ragStatus: 'GROUNDED',
        citations: MOCK_RAG_CITATIONS,
      }),
    },
    { event: 'content', data: JSON.stringify({ chunk: '卷积输出尺寸可用公式 ' }) },
    { event: 'content', data: JSON.stringify({ chunk: '(N-K+2P)/S+1 推导。' }) },
    { event: 'complete', data: '{"finishReason":"stop"}' },
  ]),

  'POST /resources/generate/stream': (route) => sse(route, [
    {
      event: 'agent_step',
      data: JSON.stringify({
        runId: 'run-e2e-001',
        stepOrder: 1,
        agentName: 'SupervisorAgent',
        message: '已检索 CNN 讲义并分派 QuizAgent',
        detail: 'query_chroma_db → delegate_to_quiz_agent',
        progressPercent: 35,
      }),
    },
    {
      event: 'agent_step',
      data: JSON.stringify({
        runId: 'run-e2e-001',
        stepOrder: 2,
        agentName: 'QuizAgent',
        message: '已生成 3 道卷积尺寸练习题',
        detail: 'critic_passed',
        progressPercent: 72,
      }),
    },
    {
      event: 'complete',
      data: JSON.stringify({
        runId: 'run-e2e-001',
        reviewSummary: '8 类资源已通过引用校验，可进入学习循环。',
        steps: [
          {
            id: 'run-e2e-001-1-SupervisorAgent',
            agentName: 'SupervisorAgent',
            stepOrder: 1,
            outputSummary: '已检索 CNN 讲义并分派 QuizAgent',
            critique: 'query_chroma_db → delegate_to_quiz_agent',
          },
          {
            id: 'run-e2e-001-2-QuizAgent',
            agentName: 'QuizAgent',
            stepOrder: 2,
            outputSummary: '已生成 3 道卷积尺寸练习题',
            critique: 'critic_passed',
          },
        ],
        artifacts: MOCK_GENERATED_ARTIFACTS,
      }),
    },
  ]),
}

function resolveHandler(method: string, path: string, handlers: MockHandlers) {
  const exact = handlers[`${method} ${path}`]
  if (exact) return exact

  if (method === 'PUT' && path.startsWith('/learning-sessions/')) {
    return handlers['PUT /learning-sessions/9001']
  }

  if (method === 'GET' && /^\/resources\/generate\/jobs\/[^/]+$/.test(path)) {
    return handlers['GET /resources/generate/jobs/:taskId']
  }

  return null
}

export async function installApiMocks(page: Page, overrides: MockHandlers = {}) {
  const handlers = { ...defaultHandlers, ...overrides }

  await page.route(/\/api\//, async (route) => {
    const request = route.request()
    const path = apiPath(request.url())
    const handler = resolveHandler(request.method(), path, handlers)

    if (handler) {
      await handler(route)
      return
    }

    await json(route, {
      mocked: true,
      method: request.method(),
      path,
      message: 'E2E fallback mock',
    })
  })
}

export function guestAuthInitScript() {
  return {
    vt_token: MOCK_GUEST_TOKEN,
    vt_guest_id: MOCK_GUEST_ID,
    vt_is_guest: 'true',
    vt_user: JSON.stringify({
      id: MOCK_GUEST_ID,
      username: 'Guest',
      displayName: '游客',
    }),
  }
}

export function registeredAuthInitScript() {
  return {
    vt_token: MOCK_USER_TOKEN,
    vt_guest_id: '',
    vt_is_guest: 'false',
    vt_user: JSON.stringify(MOCK_USER),
    'visionary-tutor-user-profile:user:42': JSON.stringify({
      goal: '掌握 CNN 卷积与特征图尺寸推导',
      cognitiveStyle: '视觉型',
      weakPoints: ['卷积核维度计算'],
      onboardingComplete: true,
      isComplete: true,
      onboardingAnswerCount: 6,
      profileSource: 'local',
      profileVersion: 2,
      pathVersion: 1,
      emotionState: '专注',
      attentionState: '稳定',
      learningPace: '中等',
      errorPatterns: [],
      lastQuizAccuracy: null,
      pendingPostOnboardingGeneration: false,
      profileSyncNotice: '',
      lastPolicyReason: '',
    }),
  }
}

export async function seedLocalStorage(page: Page, entries: Record<string, string>) {
  await page.addInitScript((storageEntries) => {
    for (const [key, value] of Object.entries(storageEntries)) {
      localStorage.setItem(key, value)
    }
  }, entries)
}
