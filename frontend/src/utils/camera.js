/**
 * Camera constraints tuned for mobile portrait and desktop landscape.
 */
export class CameraAccessError extends Error {
  constructor(code, message, { retryable = true, cause } = {}) {
    super(message, { cause })
    this.name = 'CameraAccessError'
    this.code = code
    this.retryable = retryable
  }
}

function isLocalDevelopmentHost(hostname = '') {
  return hostname === 'localhost' || hostname === '127.0.0.1' || hostname === '[::1]'
}

export function getCameraSupportIssue() {
  if (typeof window === 'undefined' || typeof navigator === 'undefined') {
    return new CameraAccessError('UNAVAILABLE', '当前环境无法访问摄像头。', {
      retryable: false,
    })
  }

  const insecurePage =
    window.isSecureContext === false ||
    (window.location.protocol !== 'https:' && !isLocalDevelopmentHost(window.location.hostname))
  if (insecurePage) {
    return new CameraAccessError(
      'INSECURE_CONTEXT',
      '浏览器仅允许 HTTPS 页面使用摄像头。请通过 https:// 访问本站后再开启。',
      { retryable: false },
    )
  }

  if (!navigator.mediaDevices?.getUserMedia) {
    return new CameraAccessError(
      'UNSUPPORTED',
      '当前浏览器不支持摄像头访问，请升级浏览器或改用最新版 Chrome / Edge。',
      { retryable: false },
    )
  }

  return null
}

export function normalizeCameraAccessError(error) {
  if (error instanceof CameraAccessError) return error

  const name = error?.name || ''
  if (name === 'NotAllowedError' || name === 'PermissionDeniedError') {
    return new CameraAccessError(
      'PERMISSION_DENIED',
      '摄像头权限被拒绝。请点击地址栏左侧的权限图标，允许摄像头后重试。',
      { cause: error },
    )
  }
  if (name === 'NotFoundError' || name === 'DevicesNotFoundError') {
    return new CameraAccessError('DEVICE_NOT_FOUND', '未检测到可用摄像头，请确认设备已连接且未被系统禁用。', {
      cause: error,
    })
  }
  if (name === 'NotReadableError' || name === 'TrackStartError') {
    return new CameraAccessError('DEVICE_BUSY', '摄像头可能正被其他应用占用，请关闭占用摄像头的程序后重试。', {
      cause: error,
    })
  }

  return new CameraAccessError('UNKNOWN', error?.message || '无法访问摄像头，请检查浏览器权限与设备状态后重试。', {
    cause: error,
  })
}

export async function acquireUserCameraStream() {
  const supportIssue = getCameraSupportIssue()
  if (supportIssue) throw supportIssue

  const isMobile =
    typeof window !== 'undefined' &&
    typeof window.matchMedia === 'function' &&
    window.matchMedia('(max-width: 767px)').matches

  const primary = {
    audio: false,
    video: {
      facingMode: 'user',
      width: { ideal: isMobile ? 720 : 1280 },
      height: { ideal: isMobile ? 1280 : 720 },
    },
  }

  try {
    return await navigator.mediaDevices.getUserMedia(primary)
  } catch (error) {
    if (error?.name !== 'OverconstrainedError' && error?.name !== 'ConstraintNotSatisfiedError') {
      throw normalizeCameraAccessError(error)
    }
    try {
      return await navigator.mediaDevices.getUserMedia({
        audio: false,
        video: true,
      })
    } catch (fallbackError) {
      throw normalizeCameraAccessError(fallbackError)
    }
  }
}

export function stopMediaStream(stream) {
  stream?.getTracks().forEach((track) => track.stop())
}
