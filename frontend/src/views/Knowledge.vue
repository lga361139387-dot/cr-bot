<template>
  <div class="page ep-page">
    <div class="section-head">
      <div>
        <h2>知识库</h2>
        <p>点一行查看梳理后的正文。上传前先在基础资料里建好迭代和产品负责人。</p>
      </div>
    </div>
    <div class="toolbar">
      <el-select v-model="form.iterationId" placeholder="迭代版本" style="width: 160px">
        <el-option v-for="item in iterations" :key="item.id" :label="item.name" :value="item.id" />
      </el-select>
      <el-input v-model="form.title" placeholder="标题" style="width: 160px" />
      <el-select v-model="form.docType" placeholder="分类" style="width: 120px">
        <el-option label="原型" value="prototype" />
        <el-option label="概要设计" value="outline" />
        <el-option label="技术方案" value="solution" />
        <el-option label="测试用例" value="testcase" />
        <el-option label="业务逻辑" value="logic" />
        <el-option label="会议纪要" value="meeting" />
        <el-option label="其他" value="other" />
      </el-select>
      <el-select v-model="form.ownerIds" multiple collapse-tags placeholder="产品负责人" style="width: 160px">
        <el-option v-for="item in owners" :key="item.id" :label="item.name" :value="item.id" />
      </el-select>
      <el-select v-model="form.developerIds" multiple collapse-tags placeholder="开发人员" style="width: 160px">
        <el-option v-for="item in developers" :key="item.id" :label="item.name" :value="item.id" />
      </el-select>
      <el-select v-model="form.testerIds" multiple collapse-tags placeholder="测试人员" style="width: 160px">
        <el-option v-for="item in testers" :key="item.id" :label="item.name" :value="item.id" />
      </el-select>
      <label class="btn btn-ghost file-pick">
        <span>{{ file ? file.name : '选择文件' }}</span>
        <input ref="fileInput" type="file" accept=".md,.txt,.markdown,.html,.htm,.xls,.xlsx,.xmind" @change="onFile" />
      </label>
      <el-button type="primary" :loading="uploading" @click="create">上传并索引</el-button>
    </div>

    <div class="project-tabs">
      <button class="project-tab" :class="{ active: iterationId == null }" type="button" @click="selectIteration(null)">
        全部
        <span>{{ allCount }}</span>
      </button>
      <button
        v-for="item in facets"
        :key="item.id"
        class="project-tab"
        :class="{ active: iterationId === item.id }"
        type="button"
        @click="selectIteration(item.id)"
      >
        {{ item.name }}
        <span>{{ item.count }}</span>
      </button>
    </div>
    <div class="toolbar">
      <el-input v-model="keyword" placeholder="标题、负责人、标签、正文" style="width: 320px" @keyup.enter="load" />
      <el-button @click="load">筛选</el-button>
    </div>

    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>迭代</th>
            <th>标题</th>
            <th>分类</th>
            <th>产品</th>
            <th>开发</th>
            <th>测试</th>
            <th>索引</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in rows" :key="row.id" @click="openDetail(row)">
            <td>{{ row.iterationName || '-' }}</td>
            <td>{{ row.title }}</td>
            <td>{{ typeText(row.docType) }}</td>
            <td>{{ names(row.owners) || '-' }}</td>
            <td>{{ names(row.developers) || '-' }}</td>
            <td>{{ names(row.testers) || '-' }}</td>
            <td><span :class="statusClass(row.status)">{{ statusText(row.status) }}</span></td>
            <td @click.stop>
              <button v-if="row.status === 'PRECHECK' || row.status === 'PRECHECK_FAILED'" class="btn btn-ghost btn-sm" type="button" @click="openPrecheck(row.id)">预审</button>
              <button class="btn btn-ghost btn-sm" type="button" @click="showChunks(row.id)">切片</button>
              <button class="btn btn-ghost btn-sm" type="button" @click="reindex(row.id)">重建</button>
              <button class="btn btn-danger btn-sm" type="button" @click="remove(row.id)">删除</button>
            </td>
          </tr>
        </tbody>
      </table>
      <div v-if="rows.length === 0" class="empty-state">还没有知识库文档</div>
    </div>

    <div v-if="opened" class="drawer-overlay" @click="closeDetail" />
    <aside v-if="opened" class="drawer">
      <div class="drawer-header">
        <div>
          <h2>{{ detailTitle || '知识详情' }}</h2>
          <div class="meta">
            <span>{{ typeText(detailType) }}</span>
            <span :class="statusClass(detailStatus)">{{ statusText(detailStatus) }}</span>
          </div>
        </div>
        <button class="drawer-close" type="button" @click="closeDetail">×</button>
      </div>
      <div class="drawer-body">
        <pre class="detail-text">{{ detailText || '还没有正文' }}</pre>
      </div>
    </aside>

    <el-dialog v-model="precheckVisible" title="入库预审" width="760px" :close-on-click-modal="false">
      <div v-if="precheckDocStatus === 'PRECHECK_FAILED'" class="alert alert-danger" style="margin: 12px 0">
        {{ precheckError || '预审没有完成' }}
        <div style="margin-top: 8px"><el-button type="primary" :loading="precheckLoading" @click="retryPrecheck">重新预审</el-button></div>
      </div>
      <div v-else-if="doubts.length === 0" class="empty-state">没有待确认的疑点</div>
      <div v-for="item in doubts" :key="item.id" class="finding">
        <div class="finding-body">{{ item.excerpt }}</div>
        <div class="finding-body">{{ item.question }}</div>
        <el-radio-group v-model="decision[item.id].action">
          <el-radio value="CONFIRMED">确认无问题</el-radio>
          <el-radio value="SUPPLEMENT">修改补充说明</el-radio>
          <el-radio value="DISCARD">标记需要删除该疑点</el-radio>
        </el-radio-group>
        <el-input v-model="decision[item.id].note" type="textarea" :rows="2" placeholder="补充说明" />
      </div>
      <template #footer>
        <el-button @click="precheckVisible = false">关闭</el-button>
        <el-button v-if="doubts.length" type="primary" :loading="precheckLoading" @click="confirmPrecheck">确认并入库</el-button>
      </template>
    </el-dialog>
    <el-dialog v-model="chunkVisible" title="切片" width="720px">
      <div v-for="chunk in chunks" :key="chunk.chunkIndex" class="finding">
        <div class="finding-head"><span class="badge badge-low">#{{ chunk.chunkIndex }}</span></div>
        <pre class="detail-text">{{ chunk.content }}</pre>
      </div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import http from '../api/http'

const rows = ref<any[]>([])
const owners = ref<any[]>([])
const developers = ref<any[]>([])
const testers = ref<any[]>([])
const iterations = ref<any[]>([])
const facets = ref<any[]>([])
const iterationId = ref<number | null>(null)
const keyword = ref('')
const allCount = computed(() => facets.value.reduce((sum, item) => sum + Number(item.count || 0), 0))
const file = ref<File | null>(null)
const fileInput = ref<HTMLInputElement | null>(null)
const chunks = ref<any[]>([])
const chunkVisible = ref(false)
const detailTitle = ref('')
const detailText = ref('')
const uploading = ref(false)
const opened = ref<number | null>(null)
const detailType = ref('')
const detailStatus = ref('')
const precheckVisible = ref(false)
const precheckLoading = ref(false)
const precheckDocId = ref<number | null>(null)
const precheckDocStatus = ref('')
const precheckError = ref('')
const doubts = ref<any[]>([])
const decision = reactive<Record<number, { action: string, note: string }>>({})
const form = reactive({ title: '', docType: 'logic', iterationId: undefined as number | undefined, ownerIds: [] as number[], developerIds: [] as number[], testerIds: [] as number[] })

function names(people?: { name: string }[]) {
  return (people || []).map((item) => item.name).join('、')
}

function typeText(docType: string) {
  return ({ prototype: '原型', outline: '概要设计', solution: '技术方案', testcase: '测试用例', logic: '业务逻辑', meeting: '会议纪要', other: '其他' } as Record<string, string>)[docType] || docType
}

function statusText(status: string) {
  return ({ PRECHECK: '待确认', PRECHECK_FAILED: '预审失败', INDEXED: '已入库', UPLOADED: '待索引', FAILED: '索引失败' } as Record<string, string>)[status] || status
}

function statusClass(status: string) {
  if (status === 'INDEXED') return 'badge badge-done'
  if (status === 'FAILED' || status === 'PRECHECK_FAILED') return 'badge badge-high'
  if (status === 'PRECHECK') return 'badge badge-medium'
  return 'badge badge-none'
}

function onFile(event: Event) {
  const picked = (event.target as HTMLInputElement).files?.[0] || null
  file.value = picked
  if (picked) {
    ElMessage.success('已选择 ' + picked.name)
  }
}

async function openDetail(row: { id: number, title?: string, docType?: string, status?: string }) {
  opened.value = row.id
  detailTitle.value = row.title || ''
  detailType.value = row.docType || ''
  detailStatus.value = row.status || ''
  detailText.value = ''
  const { data } = await http.get('/knowledge/docs/' + row.id)
  detailTitle.value = data.title
  detailType.value = data.docType
  detailStatus.value = data.status
  detailText.value = data.contentText || ''
}

function closeDetail() {
  opened.value = null
}

async function loadCatalog() {
  const [ownerRes, developerRes, testerRes, iterationRes] = await Promise.all([
    http.get('/knowledge/owners', { params: { role: 1 } }),
    http.get('/knowledge/owners', { params: { role: 2 } }),
    http.get('/knowledge/owners', { params: { role: 3 } }),
    http.get('/knowledge/iterations')
  ])
  owners.value = ownerRes.data
  developers.value = developerRes.data
  testers.value = testerRes.data
  iterations.value = iterationRes.data
}

async function loadFacets() {
  const { data } = await http.get('/knowledge/docs/facets')
  facets.value = data || []
  if (iterationId.value != null && !facets.value.some((item) => item.id === iterationId.value)) {
    iterationId.value = null
  }
}

async function selectIteration(id: number | null) {
  iterationId.value = id
  await load()
}

async function load() {
  const { data } = await http.get('/knowledge/docs', {
    params: { q: keyword.value || undefined, iterationId: iterationId.value || undefined }
  })
  rows.value = data
}

async function create() {
  if (!form.iterationId) {
    ElMessage.warning('请选择迭代版本')
    return
  }
  if (!form.title.trim()) {
    ElMessage.warning('请填写标题')
    return
  }
  if (!form.docType) {
    ElMessage.warning('请选择分类')
    return
  }
  if (form.ownerIds.length === 0) {
    ElMessage.warning('请选择产品负责人')
    return
  }
  if (!file.value) {
    ElMessage.warning('请先选择文件')
    return
  }
  uploading.value = true
  try {
    const body = new FormData()
    body.append('title', form.title.trim())
    body.append('docType', form.docType)
    body.append('iterationId', String(form.iterationId))
    form.ownerIds.forEach((id) => body.append('ownerIds', String(id)))
    form.developerIds.forEach((id) => body.append('developerIds', String(id)))
    form.testerIds.forEach((id) => body.append('testerIds', String(id)))
    body.append('file', file.value)
    const { data } = await http.post('/knowledge/docs', body)
    file.value = null
    if (fileInput.value) {
      fileInput.value.value = ''
    }
    await loadFacets()
    await load()
    if (data.status === 'PRECHECK') {
      ElMessage.success('已解析原文，请确认疑点后再入库')
      await openPrecheck(data.id)
    } else if (data.status === 'PRECHECK_FAILED') {
      ElMessage.error(data.errorMessage || '预审失败')
      await openPrecheck(data.id)
    } else {
      ElMessage.success('已上传并入库')
    }
  } finally {
    uploading.value = false
  }
}

async function reindex(id: number) {
  await http.post('/knowledge/docs/' + id + '/reindex')
  await loadFacets()
  await load()
}

async function remove(id: number) {
  await http.delete('/knowledge/docs/' + id)
  await loadFacets()
  await load()
}

async function showChunks(id: number) {
  const { data } = await http.get('/knowledge/docs/' + id + '/chunks')
  chunks.value = data
  chunkVisible.value = true
}

async function openPrecheck(id: number) {
  precheckDocId.value = id
  const row = rows.value.find((item) => item.id === id)
  precheckDocStatus.value = row?.status || ''
  precheckError.value = row?.errorMessage || ''
  const { data } = await http.get('/knowledge/docs/' + id + '/precheck')
  doubts.value = data?.doubts || []
  for (const item of doubts.value) {
    decision[item.id] = { action: item.decision || '', note: item.note || '' }
  }
  precheckVisible.value = true
}

async function retryPrecheck() {
  if (!precheckDocId.value) {
    return
  }
  precheckLoading.value = true
  try {
    const { data } = await http.post('/knowledge/docs/' + precheckDocId.value + '/precheck/retry')
    await loadFacets()
    await load()
    if (data.status === 'PRECHECK') {
      await openPrecheck(data.id)
    } else if (data.status === 'INDEXED') {
      precheckVisible.value = false
      ElMessage.success('没有疑点，已按原文入库')
    } else {
      await openPrecheck(data.id)
    }
  } finally {
    precheckLoading.value = false
  }
}

async function confirmPrecheck() {
  if (!precheckDocId.value) {
    return
  }
  for (const item of doubts.value) {
    const current = decision[item.id]
    if (!current?.action) {
      ElMessage.warning('请确认全部疑点')
      return
    }
    if (current.action === 'SUPPLEMENT' && !current.note.trim()) {
      ElMessage.warning('补充说明不能为空')
      return
    }
  }
  precheckLoading.value = true
  try {
    await http.post('/knowledge/docs/' + precheckDocId.value + '/precheck/confirm', doubts.value.map((item) => ({
      doubtId: item.id,
      action: decision[item.id].action,
      note: decision[item.id].note
    })))
    precheckVisible.value = false
    ElMessage.success('已按原文入库')
    await loadFacets()
    await load()
  } finally {
    precheckLoading.value = false
  }
}

onMounted(async () => {
  await loadCatalog()
  await loadFacets()
  await load()
})
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
.detail-text { white-space: pre-wrap; margin: 0; line-height: 1.7; font-family: inherit; }
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
