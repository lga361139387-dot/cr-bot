<template>
  <el-dialog :model-value="jobId != null" title="评审日志" width="760px" @close="emit('close')">
    <div class="log-tabs">
      <button v-for="tab in tabs" :key="tab.id" type="button" class="log-tab" :class="{ active: active === tab.id }" @click="active = tab.id">
        {{ tab.label }}
      </button>
    </div>
    <div class="log-tabs">
      <button v-for="item in rounds" :key="item" type="button" class="log-tab" :class="{ active: batch === item }" @click="batch = item">
        {{ item === 0 ? '首次评审' : '第 ' + item + ' 次重跑' }}
      </button>
    </div>
    <p v-if="shown.length === 0" class="log-empty">这一类还没有记录</p>
    <article v-for="row in shown" :key="row.id" class="log-item">
      <div class="log-title">{{ kindText(row.kind) }} · {{ row.title }}</div>
      <p v-if="row.detail">{{ row.detail }}</p>
    </article>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import http from '../api/http'

const props = defineProps<{ jobId: number | null }>()
const emit = defineEmits<{ close: [] }>()
const rows = ref<any[]>([])
const active = ref(0)
const batch = ref(0)
const tabs = [
  { id: 0, label: '全部' },
  { id: 1, label: '模型调用' },
  { id: 2, label: '文件读取' },
  { id: 3, label: '搜索' },
  { id: 4, label: '过滤文件' }
]

const rounds = computed(() => {
  const values = Array.from(new Set(rows.value.map(row => Number(row.round || 0)))).sort((a, b) => a - b)
  return values.length ? values : [0]
})

const shown = computed(() => {
  const inBatch = rows.value.filter(row => Number(row.round || 0) === batch.value)
  if (active.value === 0) {
    return inBatch
  }
  return inBatch.filter(row => Number(row.kind) === active.value)
})

watch(() => props.jobId, async (id) => {
  active.value = 0
  rows.value = []
  if (id == null) {
    return
  }
  const { data } = await http.get('/reviews/' + id + '/logs')
  rows.value = data
  batch.value = rounds.value[rounds.value.length - 1] || 0
})

function kindText(value: number) {
  const kind = Number(value)
  if (kind === 1) return '模型调用'
  if (kind === 2) return '文件读取'
  if (kind === 3) return '搜索'
  return '过滤文件'
}
</script>

<style scoped>
.log-tabs { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 12px; }
.log-tab {
  border: 1px solid var(--el-border-color);
  background: transparent;
  border-radius: 999px;
  padding: 4px 12px;
  cursor: pointer;
}
.log-tab.active { background: var(--el-color-primary); border-color: var(--el-color-primary); color: #fff; }
.log-empty { color: var(--el-text-color-secondary); }
.log-item { padding: 12px 0; border-bottom: 1px solid var(--el-border-color-lighter); }
.log-title { font-weight: 600; margin-bottom: 4px; }
.log-item p { margin: 0; line-height: 1.5; }
</style>
