import DOMPurify from 'dompurify'
import katex from 'katex'
import MarkdownIt from 'markdown-it'
import { normalizeMarkdownSource } from './sanitizeAssistantContent'

function renderMath(formula, displayMode) {
  try {
    return katex.renderToString(formula, {
      displayMode,
      throwOnError: false,
      strict: 'warn',
      trust: false,
      output: 'htmlAndMathml',
    })
  } catch {
    return `<code class="math-fallback">${md.utils.escapeHtml(formula)}</code>`
  }
}

function mathInline(state, silent) {
  if (state.src[state.pos] !== '$' || state.src[state.pos + 1] === '$') return false
  let end = state.pos + 1
  while ((end = state.src.indexOf('$', end)) !== -1) {
    if (state.src[end - 1] !== '\\') break
    end += 1
  }
  if (end === -1 || end === state.pos + 1) return false
  if (!silent) {
    const token = state.push('math_inline', 'math', 0)
    token.content = state.src.slice(state.pos + 1, end)
  }
  state.pos = end + 1
  return true
}

function mathBlock(state, startLine, endLine, silent) {
  const start = state.bMarks[startLine] + state.tShift[startLine]
  const maximum = state.eMarks[startLine]
  if (state.src.slice(start, maximum).trim() !== '$$') return false
  let nextLine = startLine + 1
  const content = []
  for (; nextLine < endLine; nextLine += 1) {
    const lineStart = state.bMarks[nextLine] + state.tShift[nextLine]
    const lineEnd = state.eMarks[nextLine]
    const line = state.src.slice(lineStart, lineEnd)
    if (line.trim() === '$$') break
    content.push(line)
  }
  if (nextLine >= endLine) return false
  if (silent) return true
  const token = state.push('math_block', 'math', 0)
  token.block = true
  token.content = content.join('\n')
  token.map = [startLine, nextLine + 1]
  state.line = nextLine + 1
  return true
}

const md = new MarkdownIt({
  html: false,
  linkify: true,
  typographer: false,
  breaks: true,
})

md.inline.ruler.before('escape', 'math_inline', mathInline)
md.block.ruler.before('fence', 'math_block', mathBlock, {
  alt: ['paragraph', 'reference', 'blockquote', 'list'],
})
md.renderer.rules.math_inline = (tokens, index) => renderMath(tokens[index].content, false)
md.renderer.rules.math_block = (tokens, index) =>
  `<div class="math-block">${renderMath(tokens[index].content, true)}</div>`
md.renderer.rules.heading_open = (tokens, index, options, _env, self) => {
  const line = tokens[index].map?.[0]
  if (Number.isInteger(line)) tokens[index].attrSet('id', markdownHeadingId(line))
  return self.renderToken(tokens, index, options)
}

export function markdownHeadingId(lineNumber) {
  return `section-${Math.max(0, Number(lineNumber) || 0)}`
}

const defaultLinkOpen = md.renderer.rules.link_open
  || ((tokens, index, options, _env, self) => self.renderToken(tokens, index, options))
md.renderer.rules.link_open = (tokens, index, options, env, self) => {
  const hrefIndex = tokens[index].attrIndex('href')
  const href = hrefIndex >= 0 ? tokens[index].attrs[hrefIndex][1] : ''
  if (/^https?:\/\//i.test(href)) {
    tokens[index].attrSet('target', '_blank')
    tokens[index].attrSet('rel', 'noopener noreferrer')
  }
  return defaultLinkOpen(tokens, index, options, env, self)
}

/**
 * Shared learner-facing Markdown pipeline. MarkdownIt owns block parsing,
 * KaTeX owns math rendering and DOMPurify is the final XSS boundary.
 */
export function renderSimpleMarkdown(source) {
  const normalized = typeof source === 'string'
    ? source
    : source == null
      ? ''
      : JSON.stringify(source, null, 2)
  if (!normalized.trim()) return ''
  let renderable = normalizeMarkdownSource(normalized)
  // markdown-it intentionally leaves disallowed links as literal Markdown. That
  // is safe in the browser, but still exposes a copyable javascript:/data:
  // payload to learners. Neutralise the destination before parsing so unsafe
  // protocols never survive as either an href or visible source text.
  renderable = renderable.replace(
    /(\]\(\s*)(?:javascript|vbscript|data):[^)\s]*(?:\([^)]*\)[^)]*)?(\))/gi,
    '$1#$2',
  )
  const mathFenceCount = (renderable.match(/(^|\n)\s*\$\$\s*(?=\n|$)/g) || []).length
  if (mathFenceCount % 2 === 1) renderable += '\n$$'
  const codeFenceCount = (renderable.match(/```/g) || []).length
  if (codeFenceCount % 2 === 1) renderable += '\n```'
  const html = md.render(renderable)
  return DOMPurify.sanitize(html, {
    USE_PROFILES: { html: true, svg: true, mathMl: true },
    ADD_ATTR: ['target', 'rel', 'aria-hidden'],
  })
}
