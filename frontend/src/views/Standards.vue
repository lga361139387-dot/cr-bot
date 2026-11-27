<template>
  <div class="page ep-page">
    <div class="section-head">
      <div>
        <h2>编码规范</h2>
        <p>上传 Markdown 或文本。多份规范可以同时启用。文件级禁用只关掉这一份的全部规则；点开后可以单独开关 R1、R2。</p>
      </div>
    </div>
    <div class="toolbar">
      <el-input v-model="title" placeholder="标题" style="width: 180px" />
      <el-input v-model="version" placeholder="版本，例如 1.0" style="width: 140px" />
      <label class="btn btn-ghost file-pick">
        <span>{{ file ? file.name : '选择文件' }}</span>
        <input ref="fileInput" type="file" accept=".md,.txt,.markdown,text/markdown,text/plain" @change="onFile" />
      </label>
      <el-button type="primary" :loading="uploading" @click="upload">上传并索引</el-button>
    </div>
    <p class="hint">支持 md、txt。标题、版本、文件都要有，缺一项会在这里提示，不会再无声返回。</p>
    <p class="hint">点一行查看规则。评审会引用所有已启用规范里仍开启的规则。文件上的「禁用」只关掉这一份。</p>
    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>标题</th>
            <th>版本</th>
            <th>索引</th>
            <th>启用</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in rows" :key="row.id" @click="openRules(row.id)">
            <td>{{ row.title }}</td>
            <td>{{ row.version }}</td>
            <td>{{ statusText(row.status) }}</td>
            <td><span :class="row.active ? 'badge badge-done' : 'badge badge-none'">{{ row.active ? '已启用' : '未启用' }}</span></td>
            <td @click.stop>
              <button v-if="!row.active" class="btn btn-ghost btn-sm" type="button" @click="activate(row)">启用</button>
              <button v-else class="btn btn-danger btn-sm" type="button" @click="deactivate(row)">禁用</button>
              <button class="btn btn-ghost btn-sm" type="button" @click="reindex(row.id)">重建索引</button>
              <button class="btn btn-danger btn-sm" type="button" @click="remove(row.id)">删除</button>
            </td>
          </tr>
        </tbody>
      </table>
      <div v-if="rows.length === 0" class="empty-state">还没有编码规范</div>
    </div>
    <p v-if="rows.some((row) => row.errorMessage)" class="hint">{{ rows.filter((row) => row.errorMessage).map((row) => row.title + '：' + row.errorMessage).join('；') }}</p>

    <div v-if="opened" class="drawer-overlay" @click="closeRules" />
    <aside v-if="opened" class="drawer">
      <div class="drawer-header">
        <div>
          <h2>{{ detail?.title || '规则' }}</h2>
          <div class="meta">
            <span>{{ detail?.version }}</span>
            <span :class="detail?.active ? 'badge badge-done' : 'badge badge-none'">{{ detail?.active ? '已启用' : '未启用' }}</span>
          </div>
        </div>
        <button class="drawer-close" type="button" @click="closeRules">×</button>
      </div>
      <div class="drawer-body">
        <p class="hint">{{ detail?.summary }}</p>
        <div v-for="rule in detail?.rules || []" :key="rule.code" class="finding">
          <div class="finding-head">
            <span class="badge badge-low">{{ rule.code }}</span>
            <span class="finding-title">{{ rule.title }}</span>
            <span :class="rule.enabled ? 'badge badge-done' : 'badge badge-none'">{{ rule.enabled ? '已启用' : '已禁用' }}</span>
            <button v-if="!rule.enabled" class="btn btn-ghost btn-sm" type="button" @click="setRule(rule, true)">启用</button>
            <button v-else class="btn btn-danger btn-sm" type="button" @click="setRule(rule, false)">禁用</button>
          </div>
          <div v-if="rule.summary" class="finding-body">{{ rule.summary }}</div>
          <div v-for="(item, index) in rule.requirements" :key="'r' + index" class="finding-body">必须：{{ item }}</div>
          <div v-for="(item, index) in rule.counterexamples" :key="'c' + index" class="finding-body">反例：{{ item }}</div>
        </div>
        <div v-if="detail && detail.rules.length === 0" class="empty-state">这份文档没有提炼出规则。请用二级标题写出每条规则。</div>
      </div>
    </aside>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import http from '../api/http'

const rows = ref<any[]>([])
const title = ref('')
const version = ref('')
const file = ref<File | null>(null)
const fileInput = ref<HTMLInputElement | null>(null)
const uploading = ref(false)
const opened = ref<number | null>(null)
const detail = ref<any>(null)

function statusText(status: string) {
  return ({ UPLOADED: '已上传', INDEXED: '已索引', FAILED: '索引失败' } as Record<string, string>)[status] || status
}

function onFile(event: Event) {
  const picked = (event.target as HTMLInputElement).files?.[0] || null
  file.value = picked
  if (picked) {
    ElMessage.success('已选择 ' + picked.name)
  }
}

async function load() {
  const { data } = await http.get('/standards')
  rows.value = data
}

async function upload() {
  if (!title.value.trim() || !version.value.trim()) {
    ElMessage.warning('请先填写标题和版本')
    return
  }
  if (!file.value) {
    ElMessage.warning('请先选择 md 或 txt 文件')
    return
  }
  uploading.value = true
  try {
    const form = new FormData()
    form.append('title', title.value.trim())
    form.append('version', version.value.trim())
    form.append('file', file.value)
    await http.post('/standards', form)
    ElMessage.success('已上传')
    file.value = null
    if (fileInput.value) {
      fileInput.value.value = ''
    }
    await load()
  } finally {
    uploading.value = false
  }
}

async function activate(row: { id: number; active: boolean; status: string }) {
  if (row.status !== 'INDEXED') {
    ElMessage.warning('索引成功后才能启用')
    return
  }
  await http.post('/standards/' + row.id + '/activate')
  ElMessage.success('已启用。其他已启用的规范会一起参与评审')
  await load()
  if (opened.value === row.id) {
    await openRules(row.id)
  }
}

async function deactivate(row: { id: number }) {
  await http.post('/standards/' + row.id + '/deactivate')
  ElMessage.success('已禁用该文件的全部规则')
  await load()
  if (opened.value === row.id) {
    await openRules(row.id)
  }
}

async function setRule(rule: { code: string; enabled: boolean }, enabled: boolean) {
  if (!opened.value) return
  const action = enabled ? 'activate' : 'deactivate'
  const { data } = await http.post('/standards/' + opened.value + '/rules/' + rule.code + '/' + action)
  detail.value = data
  ElMessage.success(enabled ? rule.code + ' 已启用' : rule.code + ' 已禁用')
  await load()
}

async function openRules(id: number) {
  opened.value = id
  const { data } = await http.get('/standards/' + id + '/rules')
  detail.value = data
}

function closeRules() {
  opened.value = null
  detail.value = null
}

async function reindex(id: number) {
  await http.post('/standards/' + id + '/reindex')
  await load()
}

async function remove(id: number) {
  await http.delete('/standards/' + id)
  await load()
}

onMounted(load)
</script>

<style scoped>
.file-pick {
  position: relative;
  overflow: hidden;
  cursor: pointer;
}
.file-pick input {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  opacity: 0;
  cursor: pointer;
}
</style>
