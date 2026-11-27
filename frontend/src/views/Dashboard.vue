<template>
  <div>
    <div class="section-head">
      <div>
        <h2>累计概览</h2>
      </div>
    </div>

    <div class="grid grid-4" style="margin-bottom: 16px">
      <div class="card stat-card blue">
        <div class="stat-icon">↑</div>
        <div class="stat-label">评审总数</div>
        <div class="stat-value">{{ stats.total }}</div>
        <div class="stat-sub">Push 与 Merge Request</div>
      </div>
      <div class="card stat-card green">
        <div class="stat-icon">✓</div>
        <div class="stat-label">已完成</div>
        <div class="stat-value">{{ stats.byStatus.SUCCESS || 0 }}</div>
        <div class="stat-sub">已写出报告</div>
      </div>
      <button class="card stat-card red" style="text-align: left; cursor: pointer" @click="router.push('/reviews?status=FAILED')">
        <div class="stat-icon">!</div>
        <div class="stat-label">失败</div>
        <div class="stat-value">{{ stats.failed }}</div>
        <div class="stat-sub">失败任务</div>
      </button>
      <div class="card stat-card amber">
        <div class="stat-icon">%</div>
        <div class="stat-label">失败率</div>
        <div class="stat-value">{{ (stats.failureRate * 100).toFixed(1) }}%</div>
        <div class="stat-sub">失败数 / 评审总数</div>
      </div>
    </div>

    <div class="grid grid-4" style="margin-bottom: 16px">
      <div class="card stat-card blue">
        <div class="stat-icon">T</div>
        <div class="stat-label">累计 Token</div>
        <div class="stat-value">{{ formatTokens(stats.tokens.totalTokens) }}</div>
        <div class="stat-sub">已统计 {{ stats.tokens.countedReviews }} 次评审</div>
      </div>
      <div class="card stat-card green">
        <div class="stat-icon">入</div>
        <div class="stat-label">输入 Token</div>
        <div class="stat-value">{{ formatTokens(stats.tokens.promptTokens) }}</div>
        <div class="stat-sub">对话和本次评审的向量检索</div>
      </div>
      <div class="card stat-card amber">
        <div class="stat-icon">出</div>
        <div class="stat-label">输出 Token</div>
        <div class="stat-value">{{ formatTokens(stats.tokens.completionTokens) }}</div>
        <div class="stat-sub">模型生成的内容</div>
      </div>
      <div class="card stat-card blue">
        <div class="stat-icon">均</div>
        <div class="stat-label">平均每次</div>
        <div class="stat-value">{{ averageTokens }}</div>
        <div class="stat-sub">累计 Token / 已统计次数</div>
      </div>
    </div>

    <div class="grid grid-2">
      <div class="card">
        <div class="card-title">风险等级分布 <span style="font-weight: 400; font-size: 12px">已完成报告</span></div>
        <div class="risk-bars">
          <div v-for="item in severityRows" :key="item.name" class="risk-row">
            <span class="name" :style="{ color: item.color }">{{ item.label }}</span>
            <div class="bar-track"><div class="bar-fill" :style="{ width: item.width, background: item.color }" /></div>
            <span class="cnt">{{ item.count }}</span>
          </div>
        </div>
        <div class="legend">
          <span class="legend-item"><span class="dot" style="background: #ef4444" /> 高危</span>
          <span class="legend-item"><span class="dot" style="background: #f59e0b" /> 中</span>
          <span class="legend-item"><span class="dot" style="background: #3b82f6" /> 低</span>
          <span class="legend-item"><span class="dot" style="background: #9ca3af" /> 提示</span>
        </div>
      </div>
      <div class="card">
        <div class="card-title">
          最近评审
          <button class="btn btn-ghost btn-sm" @click="router.push('/reviews')">查看全部 →</button>
        </div>
        <div v-if="recent.length === 0" class="empty-state">还没有评审任务</div>
        <div v-else class="recent-list">
          <button v-for="item in recent" :key="item.id" class="recent-item" @click="router.push('/reviews/' + item.id)">
            <span class="rid">#{{ item.id }}</span>
            <div class="info">
              <div class="t1">{{ item.projectPath }} · {{ item.refBranch }}</div>
              <div class="t2">{{ item.pusherName || '未知提交人' }} · {{ formatTime(item.createdAt) }}</div>
            </div>
            <span :class="'badge ' + severityClass(item.overallSeverity)">{{ severityText(item.overallSeverity) }}</span>
            <span :class="'badge ' + statusClass(item.status)">{{ statusText(item.status) }}</span>
          </button>
        </div>
      </div>
    </div>

    <div class="grid grid-2" style="margin-top: 16px">
    <div class="card">
      <div class="card-title">近 14 天任务</div>
      <div v-if="stats.trend.length === 0" class="empty-state">还没有评审任务</div>
      <div v-else class="risk-bars">
        <div v-for="point in stats.trend" :key="point.date" class="risk-row">
          <span class="name" style="width: 88px">{{ point.date.slice(5) }}</span>
          <div class="bar-track"><div class="bar-fill" :style="{ width: trendWidth(point.count), background: '#2563eb' }" /></div>
          <span class="cnt">{{ point.count }}</span>
        </div>
      </div>
    </div>
    <div class="card">
      <div class="card-title">近 14 天 Token</div>
      <div v-if="tokenTrendMax === 0" class="empty-state">还没有记到 Token</div>
      <div v-else class="risk-bars">
        <div v-for="point in stats.tokenTrend" :key="point.date" class="risk-row">
          <span class="name" style="width: 88px">{{ point.date.slice(5) }}</span>
          <div class="bar-track"><div class="bar-fill" :style="{ width: tokenWidth(point.totalTokens), background: '#0f766e' }" /></div>
          <span class="cnt">{{ formatTokens(point.totalTokens) }}</span>
        </div>
      </div>
    </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import http from '../api/http'
import { formatTime, formatTokens, SEVERITY_COLOR, SEVERITY_LABEL, severityClass, severityText, statusClass, statusText } from '../labels'

const router = useRouter()
const recent = ref<any[]>([])
const stats = reactive({
  total: 0,
  failed: 0,
  failureRate: 0,
  byStatus: {} as Record<string, number>,
  bySeverity: {} as Record<string, number>,
  trend: [] as { date: string; count: number }[],
  tokens: { promptTokens: 0, completionTokens: 0, totalTokens: 0, countedReviews: 0 },
  tokenTrend: [] as { date: string; totalTokens: number }[]
})

const averageTokens = computed(() => {
  if (!stats.tokens.countedReviews) return '-'
  return formatTokens(Math.round(stats.tokens.totalTokens / stats.tokens.countedReviews))
})

const tokenTrendMax = computed(() => Math.max(0, ...stats.tokenTrend.map((point) => point.totalTokens || 0)))

function tokenWidth(value: number) {
  if (!tokenTrendMax.value) return '0%'
  return `${(value / tokenTrendMax.value) * 100}%`
}

const severityRows = computed(() => {
  const names = ['high', 'medium', 'low', 'info']
  const max = Math.max(1, ...names.map((name) => stats.bySeverity[name] || 0))
  return names.map((name) => ({
    name,
    label: SEVERITY_LABEL[name],
    color: SEVERITY_COLOR[name],
    count: stats.bySeverity[name] || 0,
    width: `${((stats.bySeverity[name] || 0) / max) * 100}%`
  }))
})

function trendWidth(count: number) {
  const max = Math.max(1, ...stats.trend.map((point) => point.count))
  return `${(count / max) * 100}%`
}

onMounted(async () => {
  const [statsRes, listRes] = await Promise.all([
    http.get('/reviews/stats'),
    http.get('/reviews', { params: { page: 1, size: 5 } })
  ])
  Object.assign(stats, statsRes.data)
  recent.value = listRes.data.records || []
})
</script>
