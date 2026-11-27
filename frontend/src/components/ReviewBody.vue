<template>
  <div v-if="detail">
    <div class="drawer-section">
      <h3>基本信息</h3>
      <div class="kv-grid">
        <div class="k">任务 ID</div><div class="v mono">{{ detail.job.id }}</div>
        <div class="k">仓库</div><div class="v">{{ detail.job.projectPath }}</div>
        <div class="k">分支</div><div class="v">{{ detail.job.refBranch }}</div>
        <div class="k">事件</div><div class="v">{{ eventText(detail.job.eventType) }}<span v-if="detail.job.mrIid"> #{{ detail.job.mrIid }}</span></div>
        <div class="k">提交人</div><div class="v">{{ detail.job.pusherName || '-' }}</div>
        <div class="k">时间</div><div class="v">{{ formatTime(detail.job.createdAt) }}</div>
        <div class="k">Commit</div><div class="v mono">{{ detail.job.commitSha || '-' }}<span v-if="detail.job.title"> — {{ detail.job.title }}</span></div>
        <div class="k">Token</div>
        <div class="v">{{ tokenText }}</div>
        <div class="k">状态 / 级别</div>
        <div class="v">
          <span :class="'badge ' + statusClass(detail.job.status)">{{ statusText(detail.job.status) }}</span>
          <span v-if="detail.report" :class="'badge ' + severityClass(detail.report.overallSeverity)" style="margin-left: 6px">{{ severityText(detail.report.overallSeverity) }}</span>
        </div>
      </div>
    </div>

    <div v-if="pipeline" class="drawer-section">
      <h3>评审过程</h3>
      <div class="biz-text">计划 {{ pipeline.plan }}，核查 {{ pipeline.filter }}，去掉 {{ pipeline.filterRemoved || 0 }} 条。</div>
      <div v-for="phase in pipeline.phases || []" :key="phase.name" class="hint">{{ phase.name }}：{{ phase.elapsedMs }} ms，{{ phase.totalTokens }} token</div>
      <div v-if="pipeline.skippedFiles?.length" class="hint">跳过：{{ pipeline.skippedFiles.map((item) => item.path + '（' + item.reason + '）').join('、') }}</div>
      <div v-if="pipeline.coverageGaps?.length" class="hint">未覆盖：{{ pipeline.coverageGaps.join('、') }}</div>
    </div>

    <div v-if="detail.job.errorMessage" class="drawer-section">
      <h3>任务失败</h3>
      <div class="alert alert-danger">{{ detail.job.errorMessage }}。提交已经在 GitLab 上，这里只是评审任务没有跑完。</div>
      <button class="btn btn-primary" style="margin-top: 10px" @click="$emit('retry')">重新排队</button>
    </div>

        <template v-if="detail.report">
      <div class="drawer-section">
        <h3>综合评审</h3>
        <div class="biz-text">
          <span :class="'badge ' + severityClass(detail.report.overallSeverity)">{{ severityText(detail.report.overallSeverity) }}</span>
          {{ detail.report.summary || '无摘要' }}
        </div>
      </div>
      <div v-for="dimension in dimensions" :key="dimension.code" class="drawer-section">
        <h3>{{ dimension.name }}</h3>
        <div v-if="dimension.status === 'skipped'" class="alert alert-info">已跳过。{{ dimension.reason }}</div>
        <template v-else>
          <div class="biz-text" style="margin-bottom: 10px">
            <span :class="'badge ' + severityClass(dimension.severity)">{{ severityText(dimension.severity) }}</span>
            {{ dimension.summary || '无摘要' }}
          </div>
          <div v-if="!dimension.findings?.length" class="hint">这个维度没有发现问题</div>
          <div v-for="(item, index) in dimension.findings" :key="dimension.code + index" class="finding">
            <div class="finding-head">
              <span :class="'badge ' + severityClass(item.severity)">{{ severityText(item.severity) }}</span>
              <span class="finding-title">{{ item.title }}</span>
              <span v-if="item.filePath" class="finding-loc">{{ item.filePath }}<span v-if="item.startLine && item.locationStatus !== 2 && item.locationStatus !== 3">:{{ item.startLine }}</span></span>
              <span v-if="item.locationStatus" class="badge badge-none">{{ locationText(item.locationStatus) }}</span>
            </div>
            <div class="finding-body">{{ item.detail }}</div>
            <pre v-if="item.existingCode" class="fix-box">{{ item.existingCode }}</pre>
            <div v-if="item.suggestion" class="finding-body">建议：{{ item.suggestion }}</div>
          </div>
        </template>
      </div>
      <template v-if="dimensions.length === 0">
      <div class="drawer-section">
        <h3>代码审查报告</h3>
        <div class="biz-text" style="margin-bottom: 10px">{{ detail.report.summary || '无摘要' }}</div>
        <div v-if="!detail.findings?.length" class="hint">无结构化发现</div>
        <div v-for="item in detail.findings" :key="item.id" class="finding">
          <div class="finding-head">
            <span :class="'badge ' + severityClass(item.severity)">{{ severityText(item.severity) }}</span>
            <span class="finding-title">{{ item.title }}</span>
            <span v-if="item.filePath" class="finding-loc">{{ item.filePath }}<span v-if="item.startLine && item.locationStatus !== 2 && item.locationStatus !== 3">:{{ item.startLine }}</span></span>
            <span v-if="item.locationStatus" class="badge badge-none">{{ locationText(item.locationStatus) }}</span>
          </div>
          <div class="finding-body">{{ item.detail }}</div>
        </div>
      </div>

      <div class="drawer-section">
        <h3>修复建议</h3>
        <div v-if="suggestions.length === 0" class="hint">暂无修复建议</div>
        <div v-for="item in suggestions" :key="item.id" class="finding">
          <div class="finding-head">
            <span class="badge badge-low">建议</span>
            <span class="finding-title">{{ item.title }}</span>
          </div>
          <div class="finding-body">{{ item.suggestion }}</div>
        </div>
      </div>

      <div class="drawer-section">
        <h3>业务相符性</h3>
        <div class="biz-text">
          <p><span :class="'badge ' + severityClass(detail.report.businessStatus === 'unmatched' ? 'medium' : 'info')">{{ businessText(detail.report.businessStatus) }}</span> {{ detail.report.businessReason }}</p>
          <p v-for="(ref, index) in kbRefs" :key="index">{{ ref.title }}：{{ ref.snippet }}</p>
        </div>
      </div>

      <div v-if="detail.report.markdown" class="drawer-section">
        <h3>原始报告</h3>
        <pre class="fix-box">{{ detail.report.markdown }}</pre>
      </div>
      </template>
    </template>
    <div v-else-if="!detail.job.errorMessage" class="hint">报告还没生成</div>
    <div v-if="detail.report" class="drawer-section">
      <h3>人工标注</h3>
      <FindingAnnotation :job-id="detail.job.id" @saved="$emit('refresh')" />
    </div>
    <FeedbackChat v-if="detail.job" :job="detail.job" @changed="$emit('refresh')" />
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { businessText, eventText, formatTime, formatTokens, severityClass, severityText, statusClass, statusText } from '../labels'
import FindingAnnotation from './FindingAnnotation.vue'
import FeedbackChat from './FeedbackChat.vue'

const props = defineProps<{ detail: any }>()
defineEmits<{ retry: [], refresh: [] }>()

const pipeline = computed(() => {
  if (!props.detail?.report?.rawJson) return null
  try {
    return JSON.parse(props.detail.report.rawJson).pipeline || null
  } catch {
    return null
  }
})

const dimensions = computed(() => {
  if (!props.detail?.report?.rawJson) return []
  try {
    const parsed = JSON.parse(props.detail.report.rawJson).dimensions
    return Array.isArray(parsed) ? parsed : []
  } catch {
    return []
  }
})

const kbRefs = computed(() => {
  if (!props.detail?.report?.rawJson) return []
  try {
    return JSON.parse(props.detail.report.rawJson).businessAlignment?.kbRefs || []
  } catch {
    return []
  }
})

const suggestions = computed(() => (props.detail?.findings || []).filter((item: any) => item.suggestion))

function locationText(status?: number) {
  if (status === 1) return '已定位'
  if (status === 2) return '未定位'
  if (status === 3) return '无证据'
  return ''
}

const tokenText = computed(() => {
  const job = props.detail?.job
  if (!job || job.totalTokens == null) return '未统计'
  return `合计 ${formatTokens(job.totalTokens)}（输入 ${formatTokens(job.promptTokens)} / 输出 ${formatTokens(job.completionTokens)}）`
})
</script>
