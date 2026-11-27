<template>
  <div>
    <div class="section-head">
      <div>
        <h2>基础资料</h2>
        <p>按页签维护。知识库上传时从这里选择迭代版本和产品负责人。</p>
      </div>
    </div>
    <div class="project-tabs">
      <button v-for="tab in tabs" :key="tab.id" class="project-tab" :class="{ active: tab.id === active }" type="button" @click="active = tab.id">
        {{ tab.label }}
      </button>
    </div>
    <div class="card">
      <div class="card-title">创建{{ current.label }}</div>
      <div class="toolbar">
        <el-input v-model="name" :placeholder="current.placeholder" style="width: 220px" @keyup.enter="add" />
        <el-select v-if="active === 'developer'" v-model="gitlabUserId" filterable placeholder="Git 账号" style="width: 260px">
          <el-option v-for="user in gitlabUsers" :key="user.id" :label="user.username + '（' + user.name + '）'" :value="user.id" />
        </el-select>
        <el-button type="primary" @click="add">创建</el-button>
      </div>
      <div class="settings-list">
        <div v-for="item in items" :key="item.id" class="branch-row">
          <div class="left">
            <span class="pattern">{{ item.name }}</span>
            <span v-if="item.gitlabUsername" class="desc">Git：{{ item.gitlabUsername }}</span>
          </div>
          <button class="btn btn-danger btn-sm" type="button" @click="remove(item.id)">删除</button>
        </div>
        <div v-if="items.length === 0" class="hint">还没有{{ current.label }}</div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import http from '../api/http'

const tabs = [
  { id: 'iteration', label: '迭代版本', placeholder: '例如 4.3.0' },
  { id: 'owner', label: '产品负责人', placeholder: '姓名', role: 1 },
  { id: 'developer', label: '开发人员', placeholder: '姓名', role: 2 },
  { id: 'tester', label: '测试人员', placeholder: '姓名', role: 3 }
]
const active = ref('iteration')
const name = ref('')
const gitlabUserId = ref<number | undefined>()
const gitlabUsers = ref<any[]>([])
const items = ref<any[]>([])
const current = computed(() => tabs.find((tab) => tab.id === active.value) || tabs[0])

async function load() {
  if (active.value === 'iteration') {
    const { data } = await http.get('/knowledge/iterations')
    items.value = data
    return
  }
  const { data } = await http.get('/knowledge/owners', { params: { role: current.value.role } })
  items.value = data
}

async function add() {
  if (!name.value.trim()) {
    ElMessage.warning('请填写' + current.value.label)
    return
  }
  if (active.value === 'iteration') {
    await http.post('/knowledge/iterations', { name: name.value.trim() })
  } else {
    if (active.value === 'developer' && !gitlabUserId.value) {
      ElMessage.warning('请选择 Git 账号')
      return
    }
    await http.post('/knowledge/owners', { name: name.value.trim(), role: current.value.role, gitlabUserId: gitlabUserId.value || null })
  }
  name.value = ''
  gitlabUserId.value = undefined
  ElMessage.success('已创建')
  await load()
}

async function remove(id: number) {
  if (active.value === 'iteration') {
    await http.delete('/knowledge/iterations/' + id)
  } else {
    await http.delete('/knowledge/owners/' + id)
  }
  await load()
}

watch(active, async () => {
  name.value = ''
  gitlabUserId.value = undefined
  if (active.value === 'developer' && gitlabUsers.value.length === 0) {
    const { data } = await http.get('/knowledge/gitlab-users')
    gitlabUsers.value = data
  }
  await load()
})

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
