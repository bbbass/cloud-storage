<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  abortUpload,
  completeUpload,
  fetchTaskStats,
  fetchTasks,
  initUpload,
  replayTask,
  streamUploadProgress,
  uploadPart
} from '@/api/demo'
import { formatBytes, formatDateTime } from '@/utils/format'

const MAX_DEMO_FILE_BYTES = 128 * 1024 * 1024
const DEFAULT_CHUNK_SIZE = 5 * 1024 * 1024
const TASK_STATUSES = ['PENDING', 'RUNNING', 'DONE', 'DEAD']
const PHASE_TEXT = {
  idle: '待开始',
  hashing: '计算哈希',
  init: '初始化',
  uploading: '上传中',
  paused: '已暂停',
  merging: '合并中',
  done: '已完成',
  error: '失败'
}

const fileInput = ref(null)
const selectedFile = ref(null)
const phase = ref('idle')
const message = ref('选择文件后可开始演示')
const errorMessage = ref('')
const uploadId = ref('')
const fileId = ref('')
const objectId = ref('')
const chunkSize = ref(DEFAULT_CHUNK_SIZE)
const totalParts = ref(0)
const uploadedParts = ref([])
const currentPart = ref(0)
const currentPartPercent = ref(0)
const percent = ref(0)
const result = ref(null)
const debugText = ref('')
const sseConnected = ref(false)
const serverProgress = ref(null)

const taskStats = ref({})
const tasks = ref([])
const taskLoading = ref(false)
const taskError = ref('')

let currentPartController = null
let sseController = null
let taskTimer = null
let uploadRunId = 0
let pauseRequested = false
let cancelRequested = false

const isBusy = computed(() => ['hashing', 'init', 'uploading', 'merging'].includes(phase.value))
const canStart = computed(() => Boolean(selectedFile.value) && !isBusy.value)
const canPause = computed(() => phase.value === 'uploading')
const canResume = computed(() => Boolean(uploadId.value) && ['paused', 'error'].includes(phase.value))
const canCancel = computed(() => Boolean(uploadId.value) && ['uploading', 'paused', 'merging', 'error'].includes(phase.value))
const statusText = computed(() => PHASE_TEXT[phase.value] || phase.value)
const statusTagType = computed(() => {
  if (phase.value === 'done') return 'success'
  if (phase.value === 'error') return 'danger'
  if (phase.value === 'paused') return 'warning'
  if (phase.value === 'uploading' || phase.value === 'hashing' || phase.value === 'init' || phase.value === 'merging') return 'primary'
  return 'info'
})

function setDebug(value) {
  debugText.value = value ? JSON.stringify(value, null, 2) : ''
}

function abortCurrentPart() {
  if (currentPartController) {
    currentPartController.abort()
    currentPartController = null
  }
}

function stopProgressStream() {
  if (sseController) {
    sseController.abort()
    sseController = null
  }
  sseConnected.value = false
}

function resetState(keepFile = true) {
  uploadRunId += 1
  abortCurrentPart()
  stopProgressStream()
  if (!keepFile) {
    selectedFile.value = null
  }
  phase.value = 'idle'
  message.value = '选择文件后可开始演示'
  errorMessage.value = ''
  uploadId.value = ''
  fileId.value = ''
  objectId.value = ''
  totalParts.value = 0
  uploadedParts.value = []
  currentPart.value = 0
  currentPartPercent.value = 0
  percent.value = 0
  result.value = null
  debugText.value = ''
  serverProgress.value = null
  pauseRequested = false
  cancelRequested = false
}

function selectFile(file) {
  if (!file) {
    return
  }
  if (file.size > MAX_DEMO_FILE_BYTES) {
    ElMessage.error(`演示文件请控制在 ${formatBytes(MAX_DEMO_FILE_BYTES)} 以内`)
    return
  }
  resetState(false)
  selectedFile.value = file
  message.value = '已选择文件，点击开始上传'
}

function openFilePicker() {
  fileInput.value?.click()
}

function onFileChange(event) {
  selectFile(event.target.files?.[0])
  event.target.value = ''
}

function onDrop(event) {
  selectFile(event.dataTransfer?.files?.[0])
}

async function sha256File(file) {
  const buffer = await file.arrayBuffer()
  const digest = await window.crypto.subtle.digest('SHA-256', buffer)
  return Array.from(new Uint8Array(digest))
    .map((byte) => byte.toString(16).padStart(2, '0'))
    .join('')
}

function missingParts() {
  const uploaded = new Set(uploadedParts.value.map(Number))
  const parts = []
  for (let partNo = 1; partNo <= totalParts.value; partNo += 1) {
    if (!uploaded.has(partNo)) {
      parts.push(partNo)
    }
  }
  return parts
}

function addUploadedPart(partNo) {
  if (!uploadedParts.value.includes(partNo)) {
    uploadedParts.value.push(partNo)
    uploadedParts.value.sort((a, b) => a - b)
  }
  percent.value = totalParts.value
    ? Math.round((uploadedParts.value.length / totalParts.value) * 100)
    : 0
}

function formatUploadError(error) {
  if (error?.code === 50001 || error?.httpStatus === 429) {
    return `触发限流，请 ${error.retryAfter || '稍后'} 秒后重试`
  }
  if (error?.code === 50002) {
    return '配额不足，无法上传'
  }
  return error?.message || '上传失败'
}

function startProgressStream(id) {
  stopProgressStream()
  sseController = new AbortController()
  streamUploadProgress(id, {
    onOpen: () => {
      sseConnected.value = true
    },
    onEvent: (event) => {
      serverProgress.value = event
      sseConnected.value = true
      if (event.message) {
        message.value = event.message
      }
    }
  }, sseController.signal)
    .catch((error) => {
      if (error.name !== 'AbortError') {
        sseConnected.value = false
      }
    })
    .finally(() => {
      sseConnected.value = false
    })
}

async function runUpload(runId) {
  try {
    for (const partNo of missingParts()) {
      if (runId !== uploadRunId || pauseRequested || cancelRequested) {
        return
      }
      currentPart.value = partNo
      currentPartPercent.value = 0
      const start = (partNo - 1) * chunkSize.value
      const end = Math.min(start + chunkSize.value, selectedFile.value.size)
      const chunk = selectedFile.value.slice(start, end)
      currentPartController = new AbortController()
      const partResult = await uploadPart(uploadId.value, partNo, chunk, {
        signal: currentPartController.signal,
        onUploadProgress: (event) => {
          if (event.total) {
            currentPartPercent.value = Math.round((event.loaded / event.total) * 100)
          }
        }
      })
      currentPartController = null
      if (runId !== uploadRunId) {
        return
      }
      addUploadedPart(partNo)
      setDebug(partResult)
      message.value = `分片 ${partNo}/${totalParts.value} 已上传`
    }

    if (pauseRequested) {
      phase.value = 'paused'
      message.value = '已暂停，可继续上传'
      return
    }
    if (runId !== uploadRunId || cancelRequested) {
      return
    }

    phase.value = 'merging'
    currentPart.value = 0
    message.value = '服务端合并与校验中...'
    const complete = await completeUpload(uploadId.value)
    result.value = complete
    fileId.value = complete.fileId || fileId.value
    objectId.value = complete.objectId || objectId.value
    percent.value = 100
    phase.value = 'done'
    message.value = complete.deduped ? '上传完成（服务端复用已有对象）' : '上传完成'
    stopProgressStream()
    setDebug(complete)
    await loadTasks()
  } catch (error) {
    if (runId !== uploadRunId || pauseRequested || cancelRequested) {
      return
    }
    phase.value = 'error'
    errorMessage.value = formatUploadError(error)
    message.value = errorMessage.value
    setDebug({ error: errorMessage.value, code: error.code, retryAfter: error.retryAfter })
  }
}

async function startUpload() {
  if (!selectedFile.value) {
    return
  }
  resetState(true)
  const file = selectedFile.value
  try {
    phase.value = 'hashing'
    message.value = `计算 SHA-256（${formatBytes(file.size)}）...`
    const sha256 = await sha256File(file)

    phase.value = 'init'
    message.value = '初始化上传...'
    const init = await initUpload({
      fileName: file.name,
      fileSize: file.size,
      sha256,
      chunkSize: DEFAULT_CHUNK_SIZE,
      contentType: file.type || 'application/octet-stream'
    })
    setDebug(init)

    if (init.instantUpload) {
      fileId.value = init.fileId || ''
      objectId.value = init.objectId || ''
      result.value = init
      percent.value = 100
      phase.value = 'done'
      message.value = init.message || '秒传成功'
      await loadTasks()
      return
    }

    uploadId.value = init.uploadId || ''
    chunkSize.value = init.chunkSize || DEFAULT_CHUNK_SIZE
    totalParts.value = init.chunkTotal || 0
    uploadedParts.value = (init.uploadedParts || []).map(Number).sort((a, b) => a - b)
    percent.value = totalParts.value
      ? Math.round((uploadedParts.value.length / totalParts.value) * 100)
      : 0
    message.value = init.message || '开始上传分片'
    startProgressStream(uploadId.value)
    phase.value = 'uploading'
    await runUpload(uploadRunId)
  } catch (error) {
    phase.value = 'error'
    errorMessage.value = formatUploadError(error)
    message.value = errorMessage.value
    setDebug({ error: errorMessage.value, code: error.code, retryAfter: error.retryAfter })
  }
}

function pauseUpload() {
  if (phase.value !== 'uploading') {
    return
  }
  pauseRequested = true
  phase.value = 'paused'
  message.value = '已暂停，等待当前分片结束'
  abortCurrentPart()
}

async function resumeUpload() {
  if (!uploadId.value) {
    return
  }
  pauseRequested = false
  cancelRequested = false
  uploadRunId += 1
  errorMessage.value = ''
  phase.value = 'uploading'
  message.value = '继续上传...'
  await runUpload(uploadRunId)
}

async function cancelUpload() {
  cancelRequested = true
  pauseRequested = false
  abortCurrentPart()
  stopProgressStream()
  const id = uploadId.value
  if (id) {
    try {
      await abortUpload(id)
    } catch (error) {
      ElMessage.warning(error.message || '清理上传会话失败')
    }
  }
  resetState(true)
  message.value = id ? '已取消并清理上传会话' : '已取消'
}

function clearFile() {
  if (isBusy.value) {
    return
  }
  resetState(false)
}

function taskStatusTagType(status) {
  return {
    PENDING: 'info',
    RUNNING: 'warning',
    DONE: 'success',
    DEAD: 'danger'
  }[status] || 'info'
}

function taskInfo(task) {
  if (task.errorMsg) {
    return task.errorMsg
  }
  if (!task.result) {
    return '-'
  }
  try {
    const parsed = JSON.parse(task.result)
    if (parsed.objectKey) {
      return `${parsed.objectKey}（${formatBytes(parsed.size)}）`
    }
    return Object.entries(parsed).map(([key, value]) => `${key}=${value}`).join(', ')
  } catch (error) {
    return task.result
  }
}

async function loadTasks() {
  taskLoading.value = true
  taskError.value = ''
  const [statsResult, tasksResult] = await Promise.allSettled([fetchTaskStats(), fetchTasks()])
  if (statsResult.status === 'fulfilled') {
    taskStats.value = statsResult.value || {}
  }
  if (tasksResult.status === 'fulfilled') {
    tasks.value = (tasksResult.value || []).slice(0, 50)
  }
  const failed = [statsResult, tasksResult].find((item) => item.status === 'rejected')
  if (failed) {
    taskError.value = failed.reason?.message || '任务加载失败'
  }
  taskLoading.value = false
}

async function replay(task) {
  try {
    await replayTask(task.id)
    ElMessage.success('已重新投递任务')
    await loadTasks()
  } catch (error) {
    ElMessage.error(error.message || '重放失败')
  }
}

onMounted(() => {
  loadTasks()
  taskTimer = window.setInterval(loadTasks, 5000)
})

onBeforeUnmount(() => {
  abortCurrentPart()
  stopProgressStream()
  window.clearInterval(taskTimer)
})
</script>

<template>
  <div class="workspace">
    <el-card class="panel">
      <template #header>
        <div class="panel-header">
          <span>上传演示</span>
          <el-tag :type="statusTagType" size="small">{{ statusText }}</el-tag>
        </div>
      </template>

      <input ref="fileInput" class="file-input" type="file" @change="onFileChange" />

      <div
        class="drop-zone"
        :class="{ active: Boolean(selectedFile) }"
        @click="openFilePicker"
        @dragover.prevent
        @drop.prevent="onDrop"
      >
        <template v-if="selectedFile">
          <div class="file-name">{{ selectedFile.name }}</div>
          <div class="file-meta">
            {{ formatBytes(selectedFile.size) }} · {{ selectedFile.type || 'application/octet-stream' }}
          </div>
        </template>
        <template v-else>
          <div class="drop-title">点击选择或拖拽文件</div>
          <div class="drop-hint">
            演示文件 ≤ {{ formatBytes(MAX_DEMO_FILE_BYTES) }}；GB 级上传用 smoke 脚本
          </div>
        </template>
      </div>

      <div class="actions">
        <el-button type="primary" size="small" :disabled="!canStart" :loading="phase === 'hashing' || phase === 'init'" @click="startUpload">
          开始上传
        </el-button>
        <el-button size="small" :disabled="!canPause" @click="pauseUpload">暂停</el-button>
        <el-button size="small" :disabled="!canResume" @click="resumeUpload">继续</el-button>
        <el-button size="small" :disabled="!canCancel" @click="cancelUpload">取消</el-button>
        <el-button size="small" :disabled="isBusy" @click="clearFile">清空</el-button>
      </div>

      <div class="progress-block">
        <el-progress :percentage="percent" :status="phase === 'error' ? 'exception' : undefined" />
        <div class="progress-meta">
          <span>{{ message }}</span>
          <span v-if="totalParts">
            分片 {{ uploadedParts.length }}/{{ totalParts }}
            <template v-if="currentPart">· 当前 {{ currentPart }}（{{ currentPartPercent }}%）</template>
          </span>
        </div>
        <div class="progress-meta">
          <el-tag v-if="sseConnected" type="success" size="small">SSE 已连接</el-tag>
          <span v-if="serverProgress">服务端进度 {{ serverProgress.percent }}%</span>
          <span v-if="uploadId">uploadId {{ uploadId }}</span>
          <span v-if="fileId">fileId {{ fileId }}</span>
          <span v-if="objectId">objectId {{ objectId }}</span>
        </div>
      </div>

      <el-alert
        v-if="errorMessage"
        class="error"
        :title="errorMessage"
        type="error"
        :closable="false"
        show-icon
      />

      <el-collapse class="debug">
        <el-collapse-item title="调试信息" name="debug">
          <pre>{{ debugText || '暂无' }}</pre>
        </el-collapse-item>
      </el-collapse>
    </el-card>

    <el-card class="panel">
      <template #header>
        <div class="panel-header">
          <span>任务演示</span>
          <el-button size="small" :loading="taskLoading" @click="loadTasks">刷新</el-button>
        </div>
      </template>

      <div class="stats">
        <div v-for="status in TASK_STATUSES" :key="status" class="stat">
          <span class="stat-label">{{ status }}</span>
          <span class="stat-value">{{ taskStats[status] ?? 0 }}</span>
        </div>
      </div>

      <el-alert
        v-if="taskError"
        class="error"
        :title="taskError"
        type="error"
        :closable="false"
        show-icon
      />

      <el-table v-loading="taskLoading && tasks.length === 0" :data="tasks" size="small" height="420" empty-text="暂无任务">
        <el-table-column prop="taskType" label="类型" width="92" />
        <el-table-column label="状态" width="88">
          <template #default="{ row }">
            <el-tag :type="taskStatusTagType(row.status)" size="small">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="fileId" label="文件 ID" width="130" show-overflow-tooltip />
        <el-table-column label="创建时间" width="156">
          <template #default="{ row }">{{ formatDateTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column prop="retryCount" label="重试" width="60" />
        <el-table-column label="信息" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ taskInfo(row) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="72" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.status === 'DEAD'" link type="danger" size="small" @click="replay(row)">重放</el-button>
            <span v-else>-</span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<style scoped>
.workspace {
  display: grid;
  grid-template-columns: minmax(0, 1.05fr) minmax(0, 0.95fr);
  gap: 16px;
  align-items: start;
}

.panel {
  min-width: 0;
}

.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.file-input {
  display: none;
}

.drop-zone {
  padding: 22px 16px;
  border: 1px dashed #c0c4cc;
  border-radius: 8px;
  background: #fafafa;
  text-align: center;
  cursor: pointer;
}

.drop-zone.active {
  border-color: #409eff;
  background: #f4f8ff;
}

.drop-title {
  color: #303133;
  font-size: 14px;
}

.drop-hint,
.file-meta {
  margin-top: 6px;
  color: #909399;
  font-size: 12px;
}

.file-name {
  color: #303133;
  font-size: 14px;
  font-weight: 600;
  word-break: break-all;
}

.actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin: 14px 0;
}

.progress-block {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.progress-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  color: #606266;
  font-size: 12px;
  word-break: break-all;
}

.error {
  margin-top: 12px;
}

.debug {
  margin-top: 14px;
}

.debug pre {
  max-height: 220px;
  margin: 0;
  padding: 10px;
  overflow: auto;
  border-radius: 6px;
  background: #f5f7fa;
  color: #303133;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
}

.stats {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
  margin-bottom: 12px;
}

.stat {
  padding: 8px 10px;
  border-radius: 6px;
  background: #f5f7fa;
}

.stat-label {
  display: block;
  color: #909399;
  font-size: 12px;
}

.stat-value {
  display: block;
  margin-top: 2px;
  color: #303133;
  font-size: 18px;
  font-weight: 600;
}

@media (max-width: 1260px) {
  .workspace {
    grid-template-columns: 1fr;
  }
}
</style>
