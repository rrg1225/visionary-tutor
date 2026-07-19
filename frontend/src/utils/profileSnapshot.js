/**
 * Backend DTOs expect profile snapshots as JSON strings, not nested objects.
 */
export function serializeProfileSnapshot(snapshot, dimensionsFallback = null, aiTeacherPreferences = null) {
  let payload = null
  if (typeof snapshot === 'string') {
    const trimmed = snapshot.trim()
    if (trimmed) {
      try {
        payload = JSON.parse(trimmed)
      } catch {
        return trimmed
      }
    }
  }
  if (snapshot && typeof snapshot === 'object') {
    payload = { ...snapshot }
  }
  if (!payload && Array.isArray(dimensionsFallback)) {
    payload = { dimensions: dimensionsFallback }
  }
  if (!payload && aiTeacherPreferences) payload = {}
  if (payload && aiTeacherPreferences) payload.aiTeacherPreferences = aiTeacherPreferences
  try {
    return payload ? JSON.stringify(payload) : ''
  } catch {
    return ''
  }
}
