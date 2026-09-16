<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { fetchHealth } from '@/api/health'

const loading = ref(false)
const health = ref(null)

async function load() {
  loading.value = true
  try {
    health.value = await fetchHealth()
  } catch (e) {
    ElMessage.error(e.message || '后端不可用')
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <el-card v-loading="loading" class="panel">
    <template #header>
      <div class="card-header">
        <span>后端自检</span>
        <el-button type="primary" size="small" @click="load">重新检测</el-button>
      </div>
    </template>
    <el-descriptions v-if="health" :column="1" border>
      <el-descriptions-item label="应用">{{ health.app }}</el-descriptions-item>
      <el-descriptions-item label="服务器时间">{{ health.time }}</el-descriptions-item>
      <el-descriptions-item label="存储实现">{{ health.storageType }}</el-descriptions-item>
      <el-descriptions-item label="数据库">{{ health.db }}</el-descriptions-item>
    </el-descriptions>
    <el-empty v-else description="尚未获取到后端信息" />
  </el-card>
</template>

<style scoped>
.panel {
  max-width: 720px;
}
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
</style>
