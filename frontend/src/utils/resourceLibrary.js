import { RESOURCE_TYPE_OPTIONS } from '../constants/resourceTypes'

/** 资源库四大类 — 用于分组展示 */
export const RESOURCE_LIBRARY_GROUPS = [
  {
    id: 'materials',
    label: '学习材料',
    description: '讲义与拓展阅读',
    emoji: '📖',
    types: ['HANDOUT', 'EXTENDED_READING'],
  },
  {
    id: 'practice',
    label: '练习与实操',
    description: '题库与代码案例',
    emoji: '📝',
    types: ['QUIZ', 'CODE_PRACTICE'],
  },
  {
    id: 'planning',
    label: '规划与导图',
    description: '学习路径与知识导图',
    emoji: '🗺️',
    types: ['LEARNING_PATH', 'MINDMAP'],
  },
  {
    id: 'media',
    label: '动画讲解',
    description: '本地演示动画与文字注解（含历史视频只读资源）',
    emoji: '▶️',
    types: ['VIDEO_SCRIPT', 'VISUALIZATION'],
  },
]

const TYPE_LABEL_MAP = Object.fromEntries(
  RESOURCE_TYPE_OPTIONS.map((item) => [item.type, item.label]),
)
TYPE_LABEL_MAP.VIDEO_SCRIPT = '历史视频脚本'

export function labelForArtifactType(type) {
  return TYPE_LABEL_MAP[type] || type || '资源'
}

function normalizeSearchText(value) {
  return String(value || '').trim().toLowerCase()
}

/** 关键字匹配：标题、摘要、正文、类型名、会话主题 */
export function matchesResourceSearch(resource, query) {
  const needle = normalizeSearchText(query)
  if (!needle) return true
  const haystack = [
    resource.title,
    resource.summary,
    resource.content,
    resource.reviewNotes,
    resource.type,
    resource.artifactType,
    labelForArtifactType(resource.artifactType),
    resource.sessionTopic,
    resource.runId,
  ]
    .filter(Boolean)
    .join('\n')
    .toLowerCase()
  return haystack.includes(needle)
}

export function filterResources(resources, { query = '', typeFilter = 'ALL' } = {}) {
  let list = Array.isArray(resources) ? [...resources] : []
  if (typeFilter && typeFilter !== 'ALL') {
    list = list.filter((item) => item.artifactType === typeFilter)
  }
  if (normalizeSearchText(query)) {
    list = list.filter((item) => matchesResourceSearch(item, query))
  }
  return list
}

export function groupResourcesByCategory(resources) {
  const filtered = Array.isArray(resources) ? resources : []
  return RESOURCE_LIBRARY_GROUPS
    .map((group) => ({
      ...group,
      items: filtered.filter((item) => group.types.includes(item.artifactType)),
    }))
    .filter((group) => group.items.length > 0)
}

export function countResourcesByType(resources) {
  const counts = { ALL: resources.length }
  for (const item of resources) {
    counts[item.artifactType] = (counts[item.artifactType] || 0) + 1
  }
  return counts
}

export function buildFilterChips(resources, { query = '' } = {}) {
  const base = filterResources(resources, { query, typeFilter: 'ALL' })
  const counts = countResourcesByType(base)
  const chips = [{ type: 'ALL', label: '全部', count: counts.ALL }]
  for (const option of RESOURCE_TYPE_OPTIONS) {
    chips.push({
      type: option.type,
      label: option.label,
      count: counts[option.type] || 0,
    })
  }
  return chips
}
