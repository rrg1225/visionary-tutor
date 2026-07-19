/**
 * 浏览器端 Python 沙箱：Pyodide（CPython 编译到 WebAssembly）在 Web Worker 中执行。
 *
 * 为什么放浏览器：
 * - 云端宝塔环境没有 Docker/Python，服务端沙箱永远不可用；
 * - 浏览器沙箱本身就是隔离边界（无文件系统、无宿主网络权限），学生代码只影响自己的页面；
 * - Worker 可被 terminate，超时强制终止是真实可执行的（服务端方案还要 kill 容器）；
 * - 手机端同样可用，不再受服务器配置限制。
 *
 * 返回的报告结构与后端 SandboxReport 对齐（status/output/error/execution_time_ms），
 * ResourceCardBody 无需区分执行来自哪里。
 */

const PYODIDE_VERSION = '0.26.4'
const PYODIDE_LOCAL = `${import.meta.env.BASE_URL || '/'}vendor/pyodide/${PYODIDE_VERSION}/`
const PYODIDE_CDN = `https://cdn.jsdelivr.net/pyodide/v${PYODIDE_VERSION}/full/`
const BOOT_TIMEOUT_MS = 90000
const EXECUTION_TIMEOUT_MS = 30000
const MAX_OUTPUT_CHARS = 20000
const MAX_SOURCE_CHARS = 50000
const BLOCKED_IMPORTS = new Set(['micropip', 'pyodide', 'js', 'requests', 'urllib', 'socket', 'subprocess', 'multiprocessing', 'ctypes'])
const ALLOWED_EXTERNAL_IMPORTS = new Set(['numpy', 'matplotlib', 'pandas', 'scipy', 'sklearn'])

let worker = null
let bootPromise = null
let requestSeq = 0
const pendingRequests = new Map()

export function isBrowserSandboxSupported() {
  return typeof WebAssembly !== 'undefined' && typeof Worker !== 'undefined' && typeof Blob !== 'undefined'
}

function buildWorkerSource() {
  return `
let pyodideIndexUrl = '${PYODIDE_LOCAL}';
try {
  importScripts(pyodideIndexUrl + 'pyodide.js');
} catch (_localError) {
  pyodideIndexUrl = '${PYODIDE_CDN}';
  importScripts(pyodideIndexUrl + 'pyodide.js');
}
const pyodideReady = loadPyodide({ indexURL: pyodideIndexUrl });
pyodideReady
  .then(() => self.postMessage({ kind: 'ready' }))
  .catch((err) => self.postMessage({ kind: 'boot-error', error: String((err && err.message) || err) }));

self.onmessage = async (event) => {
  const { id, code } = event.data || {};
  if (id == null) return;
  const lines = [];
  try {
    const pyodide = await pyodideReady;
    pyodide.setStdout({ batched: (line) => lines.push(line) });
    pyodide.setStderr({ batched: (line) => lines.push(line) });
    try {
      await pyodide.loadPackagesFromImports(code);
    } catch (packageErr) {
      lines.push('[提示] 部分依赖包加载失败，将尝试直接执行: ' + String((packageErr && packageErr.message) || packageErr));
    }
    await pyodide.runPythonAsync(code);
    self.postMessage({ kind: 'result', id, status: 'SUCCESS', output: lines.join('\\n') });
  } catch (err) {
    self.postMessage({
      kind: 'result',
      id,
      status: 'ERROR',
      output: lines.join('\\n'),
      error: String((err && err.message) || err),
    });
  }
};
`
}

function validateSource(code) {
  const source = String(code || '')
  if (source.length > MAX_SOURCE_CHARS) {
    return `代码长度超过 ${MAX_SOURCE_CHARS} 字符限制`
  }
  const imports = [...source.matchAll(/^\s*(?:from|import)\s+([a-zA-Z_][\w.]*)/gm)]
    .map((match) => match[1].split('.')[0])
  const blocked = imports.find((name) => BLOCKED_IMPORTS.has(name))
  if (blocked) return `为保护浏览器环境，不允许导入 ${blocked}`
  const external = imports.find((name) => !isLikelyStdlib(name) && !ALLOWED_EXTERNAL_IMPORTS.has(name))
  if (external) return `依赖 ${external} 不在实验白名单中`
  return ''
}

function isLikelyStdlib(name) {
  return [
    'abc', 'argparse', 'array', 'asyncio', 'base64', 'bisect', 'collections', 'copy',
    'csv', 'datetime', 'decimal', 'enum', 'functools', 'hashlib', 'heapq', 'html',
    'io', 'itertools', 'json', 'logging', 'math', 'operator', 'pathlib', 'random',
    're', 'statistics', 'string', 'sys', 'textwrap', 'time', 'typing', 'uuid',
  ].includes(name)
}

function destroyWorker(reason) {
  if (worker) {
    worker.terminate()
    worker = null
  }
  bootPromise = null
  for (const [, request] of pendingRequests) {
    clearTimeout(request.timer)
    request.resolve({
      status: 'UNAVAILABLE',
      output: '',
      error: reason || '浏览器沙箱已重置',
      execution_time_ms: 0,
    })
  }
  pendingRequests.clear()
}

function ensureWorker() {
  if (bootPromise) return bootPromise
  if (!isBrowserSandboxSupported()) {
    return Promise.reject(new Error('当前浏览器不支持 WebAssembly，无法启动本地 Python 沙箱'))
  }
  bootPromise = new Promise((resolve, reject) => {
    let bootTimer = null
    try {
      const blob = new Blob([buildWorkerSource()], { type: 'application/javascript' })
      worker = new Worker(URL.createObjectURL(blob))
    } catch (err) {
      bootPromise = null
      reject(err)
      return
    }

    bootTimer = setTimeout(() => {
      destroyWorker('Python 运行时下载超时')
      reject(new Error('Python 运行时下载超时，请检查网络后重试'))
    }, BOOT_TIMEOUT_MS)

    worker.onmessage = (event) => {
      const data = event.data || {}
      if (data.kind === 'ready') {
        clearTimeout(bootTimer)
        resolve()
        return
      }
      if (data.kind === 'boot-error') {
        clearTimeout(bootTimer)
        destroyWorker(data.error)
        reject(new Error(data.error || 'Python 运行时加载失败'))
        return
      }
      if (data.kind === 'result' && pendingRequests.has(data.id)) {
        const request = pendingRequests.get(data.id)
        pendingRequests.delete(data.id)
        clearTimeout(request.timer)
        request.resolve({
          status: data.status,
          output: truncate(data.output),
          error: truncate(data.error),
          execution_time_ms: Date.now() - request.startedAt,
        })
      }
    }

    worker.onerror = (event) => {
      clearTimeout(bootTimer)
      const message = event?.message || 'Python 沙箱 Worker 异常'
      destroyWorker(message)
      reject(new Error(message))
    }
  })
  return bootPromise
}

function truncate(text) {
  const value = text == null ? '' : String(text)
  return value.length > MAX_OUTPUT_CHARS ? `${value.slice(0, MAX_OUTPUT_CHARS)}\n…（输出过长已截断）` : value
}

/**
 * 预热运行时（可在进入 CODE_PRACTICE 卡片时调用，摊平首次运行的下载耗时）。
 */
export function warmupBrowserSandbox() {
  if (!isBrowserSandboxSupported()) return Promise.resolve(false)
  return ensureWorker().then(() => true).catch(() => false)
}

/**
 * 在浏览器沙箱中执行 Python 代码。
 * 永不 reject：任何失败都折叠成 SandboxReport 形状的对象。
 */
export async function runPythonInBrowser(code) {
  const startedAt = Date.now()
  const validationError = validateSource(code)
  if (validationError) {
    return {
      status: 'BLOCKED',
      output: '',
      error: validationError,
      execution_time_ms: 0,
    }
  }
  try {
    await ensureWorker()
  } catch (err) {
    return {
      status: 'UNAVAILABLE',
      output: '',
      error: err?.message || '本地 Python 沙箱启动失败',
      execution_time_ms: Date.now() - startedAt,
    }
  }

  return new Promise((resolve) => {
    const id = ++requestSeq
    const timer = setTimeout(() => {
      pendingRequests.delete(id)
      // Worker 内可能是死循环，terminate 是唯一可靠的停止方式；下次执行会重新拉起。
      destroyWorker('执行超时已终止')
      resolve({
        status: 'TIMEOUT',
        output: '',
        error: `代码执行超过 ${EXECUTION_TIMEOUT_MS / 1000} 秒，已强制终止（请检查是否存在死循环）`,
        execution_time_ms: Date.now() - startedAt,
      })
    }, EXECUTION_TIMEOUT_MS)

    pendingRequests.set(id, { resolve, timer, startedAt })
    worker.postMessage({ id, code })
  })
}

/** Immediately terminates the current browser worker and every running Python task. */
export function stopPythonInBrowser() {
  destroyWorker('用户已停止本次执行')
}
