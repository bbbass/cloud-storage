<template>
  <el-container class="layout">
    <el-header class="header">
      <div class="brand">云盘后端演示台</div>

      <div class="status">
        <el-tag :type="healthTagType" effect="light" size="small">{{ healthText }}</el-tag>
        <span class="meta">存储 {{ health?.storageType || '-' }}</span>
        <span class="meta">DB {{ health?.db || '-' }}</span>
        <span class="meta">Redis {{ health?.redis || '-' }}</span>

        <el-divider direction="vertical" />

        <el-input
          v-model="userId"
          class="identity-input"
          size="small"
          placeholder="用户 ID"
          @change="saveIdentity"
        >
          <template #prepend>用户</template>
        </el-input>
        <el-input
          v-model="deviceId"
          class="identity-input"
          size="small"
          placeholder="设备 ID"
          @change="saveIdentity"
        >
          <template #prepend>设备</template>
        </el-input>

        <div class="quota">
          <div class="quota-text">
            配额 {{ formatBytes(quota?.usedBytes) }} / {{ formatBytes(quota?.quotaBytes) }}
          </div>
          <el-progress
            :percentage="quotaPercent"
            :show-text="false"
            :stroke-width="6"
            :color="quotaColor"
          />
        </div>

        <el-button size="small" :loading="loading" @click="loadStatus">刷新</el-button>
      </div>
    </el-header>

    <el-alert
      v-if="lastError"
      class="error-bar"
      :title="lastError"
      type="error"
      show-icon
      :closable="false"
    />

    <el-main>
      <router-view />
    </el-main>
  </el-container>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getDemoIdentity, saveDemoIdentity } from '@/api/http'
import { fetchHealth, fetchQuota } from '@/api/demo'
import { formatBytes, percentOf } from '@/utils/format'

const identity = getDemoIdentity()
const userId = ref(identity.userId)
const deviceId = ref(identity.deviceId)
const health = ref(null)
const quota = ref(null)
const loading = ref(false)
const lastError = ref('')

let refreshTimer = null

const healthOk = computed(() => {
  const db = health.value?.db || ''
  const redis = health.value?.redis || ''
  return Boolean(health.value) && !db.startsWith('down') && !redis.startsWith('down')
})

const healthText = computed(() => {
  if (loading.value) {
    return '检测中'
  }
  if (!health.value) {
    return '后端未连接'
  }
  return healthOk.value ? '运行正常' : '存在异常'
})

const healthTagType = computed(() => {
  if (!health.value || !healthOk.value) {
    return 'danger'
  }
  return 'success'
})

const quotaPercent = computed(() => percentOf(quota.value?.usedBytes, quota.value?.quotaBytes))
const quotaColor = computed(() => (quotaPercent.value >= 90 ? '#f56c6c' : '#409eff'))

function saveIdentity() {
  saveDemoIdentity({ userId: userId.value, deviceId: deviceId.value })
  const saved = getDemoIdentity()
  userId.value = saved.userId
  deviceId.value = saved.deviceId
  ElMessage.success('身份已保存，状态已刷新')
  loadStatus()
}

async function loadStatus() {
  loading.value = true
  lastError.value = ''

  const [healthResult, quotaResult] = await Promise.allSettled([fetchHealth(), fetchQuota()])
  if (healthResult.status === 'fulfilled') {
    health.value = healthResult.value
  }
  if (quotaResult.status === 'fulfilled') {
    quota.value = quotaResult.value
  }

  const failed = [healthResult, quotaResult].find((result) => result.status === 'rejected')
  if (failed) {
    lastError.value = failed.reason?.message || '状态加载失败'
  }
  loading.value = false
}

onMounted(() => {
  loadStatus()
  refreshTimer = window.setInterval(loadStatus, 15000)
})

onBeforeUnmount(() => {
  window.clearInterval(refreshTimer)
})
</script>

<style>
body {
  margin: 0;
  background: #f5f7fa;
  color: #303133;
}
.header {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 16px;
  height: auto;
  min-height: 60px;
  padding: 10px 20px;
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
}
.brand {
  flex: none;
  font-weight: 600;
  font-size: 15px;
  white-space: nowrap;
}
.status {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  flex: 1;
  gap: 10px;
}
.meta {
  color: #606266;
  font-size: 12px;
  white-space: nowrap;
}
.identity-input {
  width: 150px;
}
.quota {
  width: 180px;
}
.quota-text {
  margin-bottom: 3px;
  color: #606266;
  font-size: 12px;
  white-space: nowrap;
}
.error-bar {
  border-radius: 0;
}
.layout {
  min-height: 100vh;
}
</style>
