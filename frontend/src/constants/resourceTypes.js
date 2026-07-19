/** 面向学习者开放的 7 类资源；历史 VIDEO_SCRIPT 仅保留只读兼容。 */
export const RESOURCE_TYPE_OPTIONS = [
  { type: 'HANDOUT', label: '讲义', emoji: '📖', hint: '结构化讲解与例题' },
  { type: 'QUIZ', label: '题库', emoji: '📝', hint: '分层练习与自检' },
  { type: 'MINDMAP', label: '导图', emoji: '🗺️', hint: 'Mermaid 知识导图' },
  { type: 'LEARNING_PATH', label: '路径', emoji: '🛤️', hint: '分步学习计划' },
  { type: 'CODE_PRACTICE', label: '实操', emoji: '💻', hint: '可运行代码案例' },
  { type: 'EXTENDED_READING', label: '阅读', emoji: '📚', hint: '完整拓展教材章节' },
  { type: 'VISUALIZATION', label: '动画讲解', emoji: '▶️', hint: '本地演示动画 + 文字注解' },
]

export const ALL_RESOURCE_TYPES = RESOURCE_TYPE_OPTIONS.map((item) => item.type)
