<template>
  <div>
    <div class="section-head">
      <div>
        <h2>分支订阅</h2>
        <p>匹配到的分支会在 Push 或 Merge Request 之后进入评审队列。关掉只是不再建任务，Git 提交照常成功。</p>
      </div>
    </div>

    <div class="policy-box" style="margin-bottom: 16px">
      <h3>当前范围</h3>
      <ul>
        <li>项目和分支都从 GitLab 仓库拉取。同一项目的同一分支只能订阅一次。</li>
        <li>事件默认同时接收 push 和 merge request。MR 只处理 open、update、reopen。</li>
      </ul>
    </div>

    <div class="card" style="margin-bottom: 16px">
      <div class="card-title">添加订阅</div>
      <div class="form-grid">
        <div class="field">
          <label>项目</label>
          <select v-model="form.projectKey" @change="onProject">
            <option value="">请选择仓库</option>
            <option v-for="item in projects" :key="item.id" :value="String(item.id)">{{ item.path }}</option>
          </select>
        </div>
        <div class="field">
          <label>分支</label>
          <select v-model="form.branchPattern">
            <option value="">请选择分支</option>
            <option v-for="name in branches" :key="name" :value="name">{{ name }}</option>
          </select>
        </div>
      </div>
      <button class="btn btn-primary" type="button" @click="create">添加</button>
    </div>

    <div v-if="groups.length" class="project-tabs">
      <button
        v-for="group in groups"
        :key="group.id"
        class="project-tab"
        :class="{ active: group.id === activeProjectId }"
        type="button"
        @click="activeProjectId = group.id"
      >
        {{ group.path }}
        <span>{{ group.rows.length }}</span>
      </button>
    </div>
    <div class="settings-list">
      <div v-for="row in currentRows" :key="row.id" class="branch-row">
        <div class="left">
          <span class="pattern">{{ row.branchPattern }}</span>
          <span class="desc">{{ row.eventTypes }}</span>
        </div>
        <div style="display: flex; align-items: center; gap: 10px">
          <span class="desc">{{ row.enabled ? '订阅中' : '已关闭' }}</span>
          <button class="toggle" :class="{ on: row.enabled }" @click="toggle(row)" />
          <button class="btn btn-danger btn-sm" @click="remove(row.id)">删除</button>
        </div>
      </div>
      <div v-if="groups.length === 0" class="empty-state">还没有订阅</div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import http from '../api/http'

const rows = ref<any[]>([])
const projects = ref<any[]>([])
const branches = ref<string[]>([])
const activeProjectId = ref<number | null>(null)
const form = reactive({ projectKey: '', gitlabProjectId: 0, projectPath: '', branchPattern: '', eventTypes: 'push,merge_request', enabled: true })

const groups = computed(() => {
  const map = new Map<number, { id: number; path: string; rows: any[] }>()
  for (const row of rows.value) {
    const id = Number(row.gitlabProjectId)
    const current = map.get(id)
    if (!current) {
      map.set(id, { id, path: row.projectPath, rows: [row] })
    } else {
      current.rows.push(row)
    }
  }
  return [...map.values()]
})

const currentRows = computed(() => groups.value.find((group) => group.id === activeProjectId.value)?.rows || [])

function keepTab() {
  if (!groups.value.length) {
    activeProjectId.value = null
    return
  }
  if (!groups.value.some((group) => group.id === activeProjectId.value)) {
    activeProjectId.value = groups.value[0].id
  }
}

async function load() {
  const { data } = await http.get('/subscriptions')
  rows.value = data
  keepTab()
}

async function loadProjects() {
  const { data } = await http.get('/subscriptions/projects')
  projects.value = data
}

async function onProject() {
  form.branchPattern = ''
  branches.value = []
  const project = projects.value.find((item) => String(item.id) === form.projectKey)
  if (!project) {
    form.gitlabProjectId = 0
    form.projectPath = ''
    return
  }
  form.gitlabProjectId = project.id
  form.projectPath = project.path
  const { data } = await http.get('/subscriptions/projects/' + project.id + '/branches')
  branches.value = data
}

async function create() {
  if (!form.gitlabProjectId || !form.projectPath) {
    ElMessage.warning('请选择项目')
    return
  }
  if (!form.branchPattern) {
    ElMessage.warning('请选择分支')
    return
  }
  await http.post('/subscriptions', {
    gitlabProjectId: form.gitlabProjectId,
    projectPath: form.projectPath,
    branchPattern: form.branchPattern,
    eventTypes: form.eventTypes,
    enabled: true
  })
  ElMessage.success('已订阅')
  activeProjectId.value = form.gitlabProjectId
  form.branchPattern = ''
  await load()
}

async function toggle(row: any) {
  await http.put('/subscriptions/' + row.id, { ...row, enabled: !row.enabled })
  await load()
}

async function remove(id: number) {
  await http.delete('/subscriptions/' + id)
  await load()
}

onMounted(async () => {
  await loadProjects()
  await load()
})
</script>

<style scoped>
.project-tabs {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
}
.project-tab {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  border-radius: 8px;
  border: 1px solid var(--border-strong);
  background: #fff;
  color: var(--text);
  cursor: pointer;
}
.project-tab span {
  min-width: 18px;
  padding: 0 6px;
  border-radius: 999px;
  background: #f3f4f6;
  color: var(--text-secondary);
  font-size: 12px;
  text-align: center;
}
.project-tab.active {
  background: var(--primary);
  border-color: var(--primary);
  color: #fff;
}
.project-tab.active span {
  background: rgba(255, 255, 255, .2);
  color: #fff;
}
</style>
