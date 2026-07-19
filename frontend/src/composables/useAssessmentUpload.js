import { reactive, ref, computed } from 'vue'
import { useAuthStore } from '../stores/authStore'
import { useLearningSessionStore } from '../stores/learningSession'
import { useUserProfileStore } from '../stores/userProfile'
import { assessUploadedFile } from '../api/ai'

const VISUAL_ASSESSMENT_TIMEOUT_MS = 120_000

export function formatAssessmentError(error) {
  if (error?.code === 'ECONNABORTED' || error?.code === 'ETIMEDOUT') {
    return '评估耗时超过 2 分钟，可能是模型服务繁忙。主题和图片已保留，可直接重新提交。'
  }
  if (!error?.response) {
    return '暂时无法连接评估服务。请检查网络后重试，主题和图片已保留。'
  }
  if ([502, 503, 504].includes(error.response.status)) {
    return 'AI 评估服务暂时繁忙，请稍后重新提交；主题和图片已保留。'
  }
  if (error.response.status === 401 || error.response.status === 403) {
    return '当前会话已失效，请重新登录后提交评估。'
  }
  const serverMessage = error.response.data?.message
  if (typeof serverMessage === 'string' && /[\u3400-\u9fff]/u.test(serverMessage)) {
    return `${serverMessage}；主题和图片已保留，可直接重试。`
  }
  return '作业评估未完成，请稍后重新提交；主题和图片已保留。'
}

/**
 * Assessment Upload Composable
 * Encapsulates drag-and-drop upload, Base64 conversion, validation, and API submission.
 * Provides a clean, reusable interface for image-based assessment workflows.
 *
 * @param {Object} options - Configuration options
 * @param {Function} options.onSuccess - Callback on successful submission
 * @param {Function} options.onError - Callback on error
 * @returns {Object} Upload state and handlers
 */
export function useAssessmentUpload(options = {}) {
  const authStore = useAuthStore()
  const learningSession = useLearningSessionStore()
  const userProfile = useUserProfileStore()

  // Reactive state
  const isDragging = ref(false)
  const selectedFile = ref(null)
  const selectedFileName = ref('')
  const isSubmitting = ref(false)
  const errorMessage = ref('')
  const statusMessage = ref('')
  const uploadProgress = ref(0)
  const lastSubmissionFailed = ref(false)

  // Form state
  const form = reactive({
    topic: '',
    contextPrompt: '',
  })

  // Computed properties
  const isValid = computed(() => {
    return form.topic.trim().length > 0 && selectedFile.value !== null
  })

  const canSubmit = computed(() => {
    return isValid.value && !isSubmitting.value
  })

  const hasFile = computed(() => selectedFile.value !== null)

  /**
   * Convert File to Base64 string
   * @param {File} file
   * @returns {Promise<string>} Base64 data URL
   */
  function fileToBase64(file) {
    return new Promise((resolve, reject) => {
      const reader = new FileReader()

      reader.onprogress = (event) => {
        if (event.lengthComputable) {
          uploadProgress.value = Math.round((event.loaded / event.total) * 100)
        }
      }

      reader.onload = () => {
        uploadProgress.value = 100
        resolve(reader.result)
      }

      reader.onerror = () => reject(new Error('Failed to read file'))
      reader.onabort = () => reject(new Error('File reading aborted'))

      reader.readAsDataURL(file)
    })
  }

  /**
   * Validate selected file
   * @param {File} file
   * @returns {boolean}
   */
  function validateFile(file) {
    if (!file) {
      errorMessage.value = '请选择要上传的文件'
      return false
    }

    const extension = file.name.split('.').pop()?.toLowerCase() || ''
    const allowedDocuments = ['pdf', 'docx', 'pptx', 'txt', 'md', 'zip']
    const isImage = file.type.startsWith('image/')
    if (!isImage && !allowedDocuments.includes(extension)) {
      errorMessage.value = '支持图片、PDF、DOCX、PPTX、TXT、Markdown 或 ZIP 文档包'
      return false
    }

    const maxSize = (isImage ? 10 : 20) * 1024 * 1024
    if (file.size > maxSize) {
      errorMessage.value = `文件大小不能超过 ${isImage ? 10 : 20}MB`
      return false
    }

    errorMessage.value = ''
    return true
  }

  /**
   * Select and validate a file
   * @param {File} file
   */
  function selectFile(file) {
    if (!validateFile(file)) return

    selectedFile.value = file
    selectedFileName.value = file.name
    uploadProgress.value = 0
    clearMessages()
  }

  /**
   * Handle file input change event
   * @param {Event} event
   */
  function handleFileSelect(event) {
    const file = event.target.files?.[0]
    if (file) selectFile(file)
  }

  /**
   * Handle drag enter
   * @param {DragEvent} event
   */
  function handleDragEnter(event) {
    event.preventDefault()
    isDragging.value = true
  }

  /**
   * Handle drag over
   * @param {DragEvent} event
   */
  function handleDragOver(event) {
    event.preventDefault()
    if (!isDragging.value) isDragging.value = true
  }

  /**
   * Handle drag leave
   * @param {DragEvent} event
   */
  function handleDragLeave(event) {
    event.preventDefault()
    // Only set to false if leaving the drop zone (not entering a child)
    if (!event.currentTarget.contains(event.relatedTarget)) {
      isDragging.value = false
    }
  }

  /**
   * Handle drop event
   * @param {DragEvent} event
   */
  function handleDrop(event) {
    event.preventDefault()
    isDragging.value = false

    const file = event.dataTransfer.files?.[0]
    if (file) selectFile(file)
  }

  /**
   * Clear selected file
   */
  function clearFile() {
    selectedFile.value = null
    selectedFileName.value = ''
    uploadProgress.value = 0
  }

  /**
   * Clear status messages
   */
  function clearMessages() {
    errorMessage.value = ''
    statusMessage.value = ''
  }

  /**
   * Reset entire form
   */
  function resetForm() {
    form.topic = ''
    form.contextPrompt = ''
    clearFile()
    clearMessages()
  }

  /**
   * Validate form before submission
   * @returns {boolean}
   */
  function validateForm() {
    clearMessages()

    if (!form.topic.trim()) {
      errorMessage.value = '请输入评估主题'
      return false
    }

    if (!selectedFile.value) {
      errorMessage.value = '请上传作业图片'
      return false
    }

    return true
  }

  /**
   * Ensure JWT is available before agent invocation (guest session bootstrap).
   */
  async function ensureAuthToken() {
    if (authStore.token) {
      return
    }
    const created = await authStore.createGuestSessionIfNeeded({ retries: 2 })
    if (!created || !authStore.token) {
      throw new Error('无法建立会话，请确认后端已启动')
    }
  }

  /**
   * Submit assessment to backend
   * @returns {Promise<Object>}
   */
  async function submitAssessment() {
    if (!validateForm()) {
      throw new Error(errorMessage.value)
    }

    isSubmitting.value = true
    lastSubmissionFailed.value = false
    uploadProgress.value = 0

    try {
      await ensureAuthToken()

      uploadProgress.value = 20

      // Update store with metadata
      learningSession.setUploadedAssessment({
        name: selectedFile.value.name,
        topic: form.topic.trim(),
        contextPrompt: form.contextPrompt.trim(),
      })
      await learningSession.ensureCurrentSession(form.topic.trim())

      let response = null

      try {
        response = await assessUploadedFile(
          selectedFile.value,
          [form.topic.trim(), form.contextPrompt.trim()].filter(Boolean).join('\n'),
          { timeout: VISUAL_ASSESSMENT_TIMEOUT_MS },
        )
        uploadProgress.value = 100
        learningSession.setAssessmentResult(response)

        if (authStore.isRegistered) {
          await userProfile.applyAssessmentResult(response)
          await learningSession.persistAssessmentReport(response, form.topic.trim())
          await learningSession.generatePersonalizedResources({
            topic: form.topic.trim(),
            profileSnapshot: userProfile.profileSnapshot
              ? JSON.stringify(userProfile.profileSnapshot)
              : JSON.stringify(userProfile.profileDimensions),
            weakPointsSnapshot: learningSession.weakNodes.map((node) => node.name).join('、'),
            emotionSnapshot: `${userProfile.emotionState || ''} / ${userProfile.attentionState || ''}`.trim(),
          })
          statusMessage.value = '评估提交成功！已根据评测结果触发个性化资源生成'
        } else {
          statusMessage.value = '游客：可预览 AI 评测；注册后永久保存'
        }
      } catch (apiError) {
        lastSubmissionFailed.value = true
        errorMessage.value = formatAssessmentError(apiError)
        options.onError?.(apiError)
        throw apiError
      }

      options.onSuccess?.({
        offline: false,
        response,
        payload: { fileName: selectedFile.value.name, topic: form.topic.trim() },
      })

      return {
        success: true,
        offline: false,
        response,
      }
    } catch (error) {
      const errorMsg = errorMessage.value || '读取图片失败，请重新选择图片后提交。'
      errorMessage.value = errorMsg

      options.onError?.(error)

      throw new Error(errorMsg)
    } finally {
      isSubmitting.value = false
    }
  }

  return {
    // State (refs)
    isDragging,
    selectedFile,
    selectedFileName,
    isSubmitting,
    errorMessage,
    statusMessage,
    uploadProgress,
    lastSubmissionFailed,
    form,

    // Computed
    isValid,
    canSubmit,
    hasFile,

    // Actions
    selectFile,
    handleFileSelect,
    handleDragEnter,
    handleDragOver,
    handleDragLeave,
    handleDrop,
    clearFile,
    clearMessages,
    resetForm,
    validateForm,
    submitAssessment,
    fileToBase64,
  }
}
