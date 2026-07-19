const BOILERPLATE_SNIPPETS = [
  '生成模式：本地降级模板',
  '配置 DeepSeek 后将由对应资源 Agent 生成更完整内容',
  '[HybridPlan]',
  'Selected agents:',
  '全量并行生成',
  '回溯模式',
]

/**
 * Strip repetitive fallback / audit boilerplate for timeline one-liners.
 */
export function compactAgentStepText(text, maxLen = 72) {
  if (!text) return ''
  let cleaned = String(text)
    .replace(/[#*`>]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()

  for (const snippet of BOILERPLATE_SNIPPETS) {
    const idx = cleaned.indexOf(snippet)
    if (idx >= 0) {
      cleaned = `${cleaned.slice(0, idx)} ${cleaned.slice(idx + snippet.length)}`.replace(/\s+/g, ' ').trim()
    }
  }

  if (/本地降级|fallback|降级模板|未在指定知识库检索/i.test(text)) {
    if (!cleaned || cleaned.length < 8) {
      return '降级占位 · 配置模型后可生成完整内容'
    }
  }

  if (cleaned.length <= maxLen) return cleaned
  return `${cleaned.slice(0, maxLen)}…`
}

export function agentStepStatusLine(step) {
  const critique = step.critique?.trim()
  if (critique && critique.length <= 40 && !critique.includes('##')) {
    return critique
  }
  const summary = compactAgentStepText(step.outputSummary || step.status)
  if (summary) return summary
  return critique ? compactAgentStepText(critique, 48) : '已完成'
}

/**
 * Collapse verbose multi-agent runs into grouped rows for the resource panel.
 */
export function groupAgentSteps(steps = []) {
  if (!steps.length) return []

  const groups = []
  for (const step of steps) {
    const agentName = step.agentName || 'Agent'
    const last = groups[groups.length - 1]
    if (last && last.agentName === agentName) {
      last.steps.push(step)
      last.statusLine = agentStepStatusLine(step)
    } else {
      groups.push({
        agentName,
        steps: [step],
        statusLine: agentStepStatusLine(step),
      })
    }
  }

  return groups.map((group) => ({
    ...group,
    count: group.steps.length,
    key: `${group.agentName}-${group.steps[0]?.stepOrder ?? 0}`,
  }))
}
