/**
 * Strip RAG / citation / system injection from learner-visible assistant output.
 */
export function sanitizeAssistantContent(text) {
  if (!text || typeof text !== 'string') return ''

  let cleaned = text.replace(/\r\n/g, '\n')

  // Block-level RAG / memory injection markers — truncate at first occurrence
  const blockMarkers = [
    '=== Retrieved Knowledge Context',
    '=== Learner Memory Layers',
    '## Application Layer (course / concept / exercise / assessment)',
    '## Application Layer',
    '## Algorithm Layer (algorithm / code)',
    '## Algorithm Layer',
    '## Math Layer',
    '## UGC Layer',
    '[检索状态]',
    '[检索模式]',
    '[引用约束]',
    '[输出要求]',
    '[强制输出]',
    'layer=concept_layer | source=',
    'chunkId=bm25-fallback',
    'mustUseCitationIds',
  ]
  for (const marker of blockMarkers) {
    const idx = cleaned.indexOf(marker)
    if (idx >= 0) {
      cleaned = cleaned.slice(0, idx).trimEnd()
    }
  }

  // Inline citation ids — never show to learners
  cleaned = cleaned.replace(/\[(cite-[a-zA-Z0-9_-]+)\]/g, '')
  cleaned = cleaned.replace(/\bcite-[a-zA-Z0-9_-]{8,}\b/g, '')

  // System / offline footers
  // A horizontal rule is valid document structure (and YAML front matter also uses
  // `---`). Never truncate arbitrary learning material at the first rule.
  cleaned = cleaned.replace(/\*当前为离线演示模式[^*]*\*/g, '')
  cleaned = cleaned.replace(/配置 DEEPSEEK_API_KEY[^。\n]*[。\n]?/g, '')

  // Collapse excessive blank lines
  cleaned = cleaned.replace(/\n{3,}/g, '\n\n').trim()

  return cleaned
}

/**
 * Ensure markdown headings/lists start on their own line (fixes streamed chunk joins).
 */
export function normalizeMarkdownSource(text) {
  if (!text || typeof text !== 'string') return ''
  let normalized = text.replace(/\r\n/g, '\n')

  // Put streamed code fences and headings back on block boundaries.
  normalized = normalized.replace(/([^\n])(```)/g, '$1\n\n$2')
  normalized = normalized.replace(
    /(^|\n)```(javascript|typescript|java|python|js|ts|json|bash|shell|sql|html|css)(?=\S)/gi,
    (_, prefix, language) => `${prefix}\`\`\`${language}\n`,
  )
  normalized = normalized.replace(/([^\n#])(#{1,4})(?=[^#\s])/g, '$1\n\n$2')
  normalized = normalized.replace(/(^|\n)(#{1,4})(?=[^#\s])/g, '$1$2 ')
  normalized = normalized.replace(/([^\n#])(#{1,4}\s)/g, '$1\n\n$2')
  // Break inline list items glued to prior text
  normalized = normalized.replace(/([^\n])(\n?- \*\*)/g, '$1\n$2')
  normalized = normalized.replace(/([^\n])(\n?\d+\. )/g, '$1\n$2')

  // Keep display-math markers readable even when a provider joins chunks.
  normalized = normalized.replace(/([^\n])\$\$/g, (_, leading) => `${leading}\n\n$$`)
  normalized = normalized.replace(/\$\$([^\n])/g, (_, trailing) => `$$\n${trailing}`)
  normalized = normalized.replace(/\\\[\s*/g, '$$\n').replace(/\s*\\\]/g, '\n$$')
  normalized = normalized.replace(/\\\(([^\n]+?)\\\)/g, '$$$1$')

  return normalized.replace(/\n{3,}/g, '\n\n').trim()
}
