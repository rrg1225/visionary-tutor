const AGENT_PREAMBLE = /^(好的[，,]?|收到(指令)?[，,]?|作为返修智能体|根据(指令|CriticAgent)|我将根据)/i

export function parseContentJson(raw) {
  if (!raw) return {}
  if (typeof raw === 'object') return raw
  try {
    return JSON.parse(raw)
  } catch {
    return {}
  }
}

export function stripAgentPreamble(text = '') {
  return text
    .split('\n')
    .filter((line) => !AGENT_PREAMBLE.test(line.trim()))
    .join('\n')
    .trim()
}

export function resolveVideoScriptDisplay(item) {
  const meta = parseContentJson(item?.contentJson)
  const summary = meta.summary
    || item?.summary
    || '教学视频分镜已生成'
  const scriptContent = meta.script_content
    || meta.scriptContent
    || meta.script
    || stripAgentPreamble(item?.content || '')
  const plan = meta.video_plan || {}
  const segmentCount = meta.segment_count || plan.segment_count || 0
  const estimatedSeconds = plan.estimated_seconds
    || (segmentCount > 0 ? segmentCount * 10 : 0)
  const videoSegments = (Array.isArray(meta.video_segments) ? meta.video_segments : []).map((segment) => ({
    ...segment,
    narrationAudioUrl: segment.narration_audio_url || segment.narrationAudioUrl || '',
  }))
  return {
    summary: stripAgentPreamble(summary),
    scriptContent: stripAgentPreamble(scriptContent),
    videoPrompt: meta.video_prompt || meta.videoPrompt || '',
    narrationAudioUrl: meta.narration_audio_url || meta.narrationAudioUrl || '',
    videoSegments,
    playlistMode: Boolean(meta.playlist_mode),
    segmentCount,
    narrationDriven: Boolean(meta.narration_driven),
    narrationSegments: Array.isArray(meta.narration_segments) ? meta.narration_segments : [],
    videoPlan: {
      tier: plan.tier || '',
      estimatedSeconds,
      reason: plan.reason || '',
      autoSelected: Boolean(plan.auto_selected),
    },
  }
}

/** 提取适合 TTS 的旁白：summary + 分镜 narration，避免整段 Markdown/JSON */
export function extractNarrationForTts(item) {
  const display = resolveVideoScriptDisplay(item)
  const meta = parseContentJson(item?.contentJson)
  const parts = []

  if (item?.title) {
    parts.push(String(item.title).trim())
  }
  if (display.summary) {
    parts.push(display.summary)
  }

  const scenes = meta.scenes
  if (Array.isArray(scenes)) {
    for (const scene of scenes) {
      const line = scene?.narration || scene?.voiceover
      if (line && String(line).trim()) {
        parts.push(String(line).trim())
      }
    }
  }

  if (parts.length <= 2 && display.scriptContent) {
    const flat = display.scriptContent
      .replace(/```[\s\S]*?```/g, ' ')
      .replace(/[#>*_`\-[\]()]/g, ' ')
      .replace(/\s+/g, ' ')
      .trim()
    if (flat) {
      parts.push(flat.slice(0, 1200))
    }
  }

  return parts.join('。').replace(/。+/g, '。').slice(0, 1800)
}
