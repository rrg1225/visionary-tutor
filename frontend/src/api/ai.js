import api from './index'

export function invokeAiAgent(payload, options = {}) {
  return api.post('/ai/invoke', payload, options).then((response) => response.data)
}

export function resolveAiRoute(payload) {
  return api.post('/ai/route', payload).then((response) => response.data)
}

export function assessUploadedFile(file, prompt, options = {}) {
  const form = new FormData()
  form.append('file', file)
  if (prompt) form.append('prompt', prompt)
  return api.post('/assessment/upload-and-assess', form, {
    timeout: options.timeout || 180_000,
  }).then((response) => response.data)
}
