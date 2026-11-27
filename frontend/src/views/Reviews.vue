<template>
  <div>
    <div class="section-head">
      <div>
        <h2>评审记录</h2>
      </div>
    </div>

    <div class="project-tabs">
      <button class="project-tab" :class="{ active: query.projectId == null }" type="button" @click="selectProject(null)">
        全部
        <span>{{ allCount }}</span>
      </button>
      <button
        v-for="item in projects"
        :key="item.gitlabProjectId"
        class="project-tab"
        :class="{ active: query.projectId === item.gitlabProjectId }"
        type="button"
        @click="selectProject(item.gitlabProjectId)"
      >
        {{ item.projectPath }}
        <span>{{ item.count }}</span>
      </button>
    </div>

    <div class="toolbar">
      <select v-if="query.projectId != null" v-model="query.branch" class="filter-select" @change="onBranch">
        <option value="">全部分支</option>
        <option v-for="name in branches" :key="name" :value="name">{{ name }}</option>
      </select>
      <select v-model="query.pusher" class="filter-select" @change="search">
        <option value="">全部提交人</option>
        <option v-for="name in pushers" :key="name" :value="name">{{ name }}</option>
      </select>
      <select v-model="query.status" class="filter-select" @change="search">
        <option value="">全部状态</option>
        <option value="PENDING">待评审</option>
        <option value="RUNNING">评审中</option>
        <option value="SUCCESS">已完成</option>
        <option value="FAILED">失败</option>
      </select>
      <select v-model="query.severity" class="filter-select" @change="search">
        <option value="">全部级别</option>
        <option value="info">提示</option>
        <option value="low">低</option>
        <option value="medium">中</option>
        <option value="high">高</option>
      </select>
      <button class="btn btn-ghost" @click="reset">重置</button>
      <span class="hint" style="margin: 0">共 {{ total }} 条</span>
    </div>

    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>任务</th>
            <th>时间</th>
            <th>仓库 / 分支</th>
            <th>提交人</th>
            <th>标题</th>
            <th>级别</th>
            <th>Token</th>
            <th>状态</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in records" :key="row.id" @click="open(row.id)">
            <td class="mono">#{{ row.id }}</td>
            <td>{{ formatTime(row.createdAt) }}</td>
            <td>
              <div class="repo-cell">
                <span class="repo-name">{{ row.projectPath }}</span>
                <span class="branch-line"><span>{{ row.refBranch }}</span><span>{{ eventText(row.eventType) }}</span></span>
              </div>
            </td>
            <td>{{ row.pusherName || '-' }}</td>
            <td>{{ row.title || '-' }}</td>
            <td><span :class="'badge ' + severityClass(row.overallSeverity)">{{ severityText(row.overallSeverity) }}</span></td>
            <td class="mono">{{ formatTokens(row.totalTokens) }}</td>
            <td><span :class="'badge ' + statusClass(row.status)">{{ statusText(row.status) }}</span></td>
            <td><button class="btn btn-ghost btn-sm" type="button" @click.stop="logId = row.id">日志</button></td>
          </tr>
        </tbody>
      </table>
      <div v-if="records.length === 0" class="empty-state">没有匹配的记录</div>
    </div>
    <div class="pager">
      <button class="btn btn-ghost btn-sm" :disabled="query.page <= 1" @click="changePage(query.page - 1)">上一页</button>
      <span>第 {{ query.page }} 页</span>
      <button class="btn btn-ghost btn-sm" :disabled="query.page * query.size >= total" @click="changePage(query.page + 1)">下一页</button>
    </div>

    <ReviewLogDialog :job-id="logId" @close="logId = null" />

    <div v-if="opened" class="drawer-overlay" @click="close" />
    <aside v-if="opened" class="drawer">
      <div class="drawer-header">
        <div>
          <h2>评审 #{{ opened }}</h2>
          <button class="btn btn-ghost btn-sm" type="button" @click="logId = opened">日志</button>
          <div v-if="detail" class="meta">
            <span>{{ detail.job.projectPath }} / {{ detail.job.refBranch }}</span>
            <span :class="'badge ' + statusClass(detail.job.status)">{{ statusText(detail.job.status) }}</span>
          </div>
        </div>
        <button class="drawer-close" @click="close">×</button>
      </div>
      <div class="drawer-body">
        <ReviewBody v-if="detail" :detail="detail" @retry="retry" @refresh="reloadDetail" />
      </div>
    </aside>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import http from '../api/http'
import ReviewBody from '../components/ReviewBody.vue'
import ReviewLogDialog from '../components/ReviewLogDialog.vue'
import { eventText, formatTime, formatTokens, severityClass, severityText, statusClass, statusText } from '../labels'

const route = useRoute()
const records = ref<any[]>([])
const projects = ref<any[]>([])
const branches = ref<string[]>([])
const pushers = ref<string[]>([])
const total = ref(0)
const opened = ref<number | null>(null)
const logId = ref<number | null>(null)
const detail = ref<any>(null)
const query = reactive({ projectId: null as number | null, branch: '', status: '', severity: '', pusher: '', page: 1, size: 20 })
const allCount = computed(() => projects.value.reduce((sum, item) => sum + Number(item.count || 0), 0))

function listParams() {
  return {
    projectId: query.projectId || undefined,
    branch: query.branch || undefined,
    status: query.status || undefined,
    severity: query.severity || undefined,
    pusher: query.pusher || undefined,
    page: query.page,
    size: query.size
  }
}

async function loadFacets() {
  const { data } = await http.get('/reviews/facets', {
    params: {
      projectId: query.projectId || undefined,
      branch: query.branch || undefined
    }
  })
  projects.value = data.projects || []
  branches.value = query.projectId == null ? [] : (data.branches || [])
  pushers.value = data.pushers || []
  if (query.projectId != null && !projects.value.some((item) => item.gitlabProjectId === query.projectId)) {
    query.projectId = null
    query.branch = ''
    await loadFacets()
    return
  }
  if (query.pusher && !pushers.value.includes(query.pusher)) {
    query.pusher = ''
  }
}

async function load() {
  const { data } = await http.get('/reviews', { params: listParams() })
  records.value = data.records
  total.value = data.total
}

async function selectProject(projectId: number | null) {
  query.projectId = projectId
  query.branch = ''
  query.page = 1
  await loadFacets()
  await load()
}

async function onBranch() {
  query.page = 1
  await loadFacets()
  await load()
}

function search() {
  query.page = 1
  load()
}

async function reset() {
  query.projectId = null
  query.branch = ''
  query.status = ''
  query.severity = ''
  query.pusher = ''
  query.page = 1
  await loadFacets()
  await load()
}

function changePage(page: number) {
  query.page = page
  load()
}

async function reloadDetail() {
  if (!opened.value) return
  const { data } = await http.get('/reviews/' + opened.value)
  detail.value = data
}

async function open(id: number) {
  opened.value = id
  detail.value = null
  const { data } = await http.get('/reviews/' + id)
  detail.value = data
}

function close() {
  opened.value = null
  detail.value = null
}

async function retry() {
  if (!opened.value) return
  await http.post('/reviews/' + opened.value + '/retry')
  ElMessage.success('已重新排队')
  await open(opened.value)
  await load()
}

function onKey(event: KeyboardEvent) {
  if (event.key === 'Escape') close()
}

onMounted(async () => {
  if (typeof route.query.status === 'string') query.status = route.query.status
  await loadFacets()
  await load()
  window.addEventListener('keydown', onKey)
})

onUnmounted(() => window.removeEventListener('keydown', onKey))
</script>

<style scoped>
.project-tabs { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 12px; }
.project-tab {
  display: inline-flex; align-items: center; gap: 8px;
  padding: 8px 12px; border-radius: 8px; border: 1px solid var(--border-strong);
  background: #fff; color: var(--text); cursor: pointer;
}
.project-tab span {
  min-width: 18px; padding: 0 6px; border-radius: 999px;
  background: #f3f4f6; color: var(--text-secondary); font-size: 12px; text-align: center;
}
.project-tab.active { background: var(--primary); border-color: var(--primary); color: #fff; }
.project-tab.active span { background: rgba(255, 255, 255, .2); color: #fff; }
</style>
