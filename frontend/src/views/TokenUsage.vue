<template>
  <div>
    <div class="section-head">
      <div>
        <h2>Token 用量</h2>
        <p>评审、导入知识库、重建索引、编码规范索引的每一次模型调用都记在这里。没有返回用量的失败调用也会留下记录，token 为空。</p>
      </div>
    </div>
    <div class="grid grid-4" style="margin-bottom: 16px">
      <div class="card stat-card blue">
        <div class="stat-label">累计 Token</div>
        <div class="stat-value">{{ formatTokens(summary.totalTokens) }}</div>
      </div>
      <div class="card stat-card green">
        <div class="stat-label">输入</div>
        <div class="stat-value">{{ formatTokens(summary.promptTokens) }}</div>
      </div>
      <div class="card stat-card amber">
        <div class="stat-label">输出</div>
        <div class="stat-value">{{ formatTokens(summary.completionTokens) }}</div>
      </div>
      <div class="card stat-card blue">
        <div class="stat-label">调用次数</div>
        <div class="stat-value">{{ summary.calls }}</div>
      </div>
    </div>
    <div class="project-tabs">
      <button v-for="tab in tabs" :key="String(tab.id)" class="project-tab" :class="{ active: scene === tab.id }" type="button" @click="selectScene(tab.id)">
        {{ tab.label }}
      </button>
    </div>
    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>时间</th>
            <th>场景</th>
            <th>类型</th>
            <th>标题</th>
            <th>模型</th>
            <th>输入</th>
            <th>输出</th>
            <th>合计</th>
            <th>错误</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in rows" :key="row.id">
            <td>{{ formatTime(row.createdAt) }}</td>
            <td>{{ sceneText(row.scene) }}</td>
            <td>{{ row.callType === 2 ? '向量' : '对话' }}</td>
            <td>{{ row.title || '-' }}<span v-if="row.refId"> #{{ row.refId }}</span></td>
            <td>{{ row.modelName || '-' }}</td>
            <td class="mono">{{ formatTokens(row.promptTokens) }}</td>
            <td class="mono">{{ formatTokens(row.completionTokens) }}</td>
            <td class="mono">{{ formatTokens(row.totalTokens) }}</td>
            <td>{{ row.errorMessage || '-' }}</td>
          </tr>
        </tbody>
      </table>
      <div v-if="rows.length === 0" class="empty-state">还没有用量记录。新的评审和索引起才会记到这里。</div>
    </div>
    <div class="pager">
      <button class="btn btn-ghost btn-sm" :disabled="page <= 1" @click="changePage(page - 1)">上一页</button>
      <span>第 {{ page }} 页 / 共 {{ total }} 条</span>
      <button class="btn btn-ghost btn-sm" :disabled="page * size >= total" @click="changePage(page + 1)">下一页</button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import http from '../api/http'
import { formatTime, formatTokens } from '../labels'

const tabs = [
  { id: null as number | null, label: '全部' },
  { id: 1, label: '评审' },
  { id: 2, label: '知识库导入' },
  { id: 3, label: '知识库重建' },
  { id: 4, label: '编码规范索引' },
  { id: 5, label: '其他' }
]
const scene = ref<number | null>(null)
const rows = ref<any[]>([])
const page = ref(1)
const size = 20
const total = ref(0)
const summary = reactive({ promptTokens: 0, completionTokens: 0, totalTokens: 0, calls: 0 })

function sceneText(value: number) {
  return ({ 1: '评审', 2: '知识库导入', 3: '知识库重建', 4: '编码规范索引', 5: '其他' } as Record<number, string>)[value] || String(value)
}

async function load() {
  const [listRes, summaryRes] = await Promise.all([
    http.get('/token-usages', { params: { scene: scene.value || undefined, page: page.value, size } }),
    http.get('/token-usages/summary')
  ])
  rows.value = listRes.data.records || []
  total.value = listRes.data.total || 0
  Object.assign(summary, summaryRes.data)
}

function selectScene(id: number | null) {
  scene.value = id
  page.value = 1
  load()
}

function changePage(next: number) {
  page.value = next
  load()
}

onMounted(load)
</script>

<style scoped>
.project-tabs { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 12px; }
.project-tab {
  padding: 8px 12px; border-radius: 8px; border: 1px solid var(--border-strong);
  background: #fff; color: var(--text); cursor: pointer;
}
.project-tab.active { background: var(--primary); border-color: var(--primary); color: #fff; }
</style>
