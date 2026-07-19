import { test, expect } from '../../../frontend/node_modules/@playwright/test'

const enabled = process.env.REAL_BACKEND_E2E === 'true'
const backend = process.env.REAL_BACKEND_BASE_URL || 'http://127.0.0.1:8080'
const username = process.env.REAL_BACKEND_USERNAME || 'demo_student'
const password = process.env.REAL_BACKEND_PASSWORD || 'Demo@2026'

test.describe('real backend generation loop', () => {
  test.skip(!enabled, 'Set REAL_BACKEND_E2E=true with MySQL, Redis, Chroma and model stubs/services ready')

  test('persists one idempotent, versioned, auditable multi-agent run', async ({ request }) => {
    const login = await request.post(`${backend}/api/auth/login`, {
      data: { username, password, guestId: null },
    })
    expect(login.ok(), await login.text()).toBeTruthy()
    const auth = await login.json()
    const headers = { Authorization: `Bearer ${auth.token}` }

    const sessionResponse = await request.post(`${backend}/api/learning-sessions/new`, {
      headers,
      data: { topic: 'CNN padding and stride real backend E2E' },
    })
    expect(sessionResponse.ok(), await sessionResponse.text()).toBeTruthy()
    const session = await sessionResponse.json()
    const requestId = `playwright-${Date.now()}`
    const payload = {
      learningSessionId: session.id,
      requestId,
      topic: 'CNN padding and stride',
      learnerProfileSnapshot: '{"knowledgeLevel":"beginner","style":"visual"}',
      weakPointsSnapshot: 'output shape calculation',
      emotionSnapshot: 'focused',
      resourceTypes: ['HANDOUT', 'QUIZ'],
    }

    const first = await request.post(`${backend}/api/resources/generate`, { headers, data: payload, timeout: 240_000 })
    expect(first.ok(), await first.text()).toBeTruthy()
    const firstRun = await first.json()
    expect(firstRun.artifacts.length).toBeGreaterThanOrEqual(2)
    for (const artifact of firstRun.artifacts) {
      const envelope = JSON.parse(artifact.contentJson)
      if (envelope.schema === 'generated-quiz/v1') {
        expect(artifact.artifactType).toBe('QUIZ')
        expect(envelope.questions.length).toBeGreaterThanOrEqual(3)
        expect(envelope.questions.every((question: { id?: string; standardAnswer?: string }) => (
          Boolean(question.id) && Boolean(question.standardAnswer)
        ))).toBeTruthy()
      } else {
        expect(envelope.schema_version).toBe('1.0')
        expect(['LIVE', 'DEGRADED']).toContain(envelope.origin)
        expect(envelope.agent).toBeTruthy()
      }
    }

    const retry = await request.post(`${backend}/api/resources/generate`, { headers, data: payload, timeout: 240_000 })
    expect(retry.ok(), await retry.text()).toBeTruthy()
    expect((await retry.json()).runId).toBe(firstRun.runId)
  })
})
