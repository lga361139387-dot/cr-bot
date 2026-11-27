<template>
  <div class="page ep-page">
    <div class="section-head">
      <div>
        <h2>数据源</h2>
        <p>评审涉及表结构时使用的只读库。新增和修改都必须先测通连接，通过后才能保存。</p>
      </div>
      <button class="btn btn-primary" type="button" @click="openCreate">新增数据库</button>
    </div>
    <el-table :data="rows">
      <el-table-column prop="name" label="名称" />
      <el-table-column label="类型" width="140">
        <template #default="{ row }">{{ typeText(row.dbType) }}</template>
      </el-table-column>
      <el-table-column prop="host" label="主机" min-width="140" />
      <el-table-column prop="port" label="端口" width="90" />
      <el-table-column prop="databaseName" label="库名" min-width="140" />
      <el-table-column prop="username" label="用户" width="120" />
      <el-table-column label="连接" width="110">
        <template #default="{ row }">
          <span :class="row.readonlyChecked ? 'badge badge-done' : 'badge badge-failed'">{{ row.readonlyChecked ? '已测通' : '未测通' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="启用" width="80">
        <template #default="{ row }">{{ row.enabled ? '是' : '否' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="160">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link type="danger" @click="remove(row.id)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="visible" :title="form.id ? '编辑数据库' : '新增数据库'" width="560px" @closed="reset">
      <el-form label-width="96px">
        <el-form-item label="名称">
          <el-input v-model="form.name" @input="markDirty" />
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="form.dbType" style="width: 100%" @change="onType">
            <el-option label="PostgreSQL" value="postgres" />
            <el-option label="MySQL" value="mysql" />
            <el-option label="达梦" value="dm" />
          </el-select>
        </el-form-item>
        <el-form-item label="主机">
          <el-input v-model="form.host" placeholder="127.0.0.1" @input="markDirty" />
        </el-form-item>
        <el-form-item label="端口">
          <el-input-number v-model="form.port" :min="1" :max="65535" :step="1" controls-position="right" style="width: 100%" @change="markDirty" />
        </el-form-item>
        <el-form-item label="库名">
          <el-input v-model="form.databaseName" placeholder="数据库名" @input="markDirty" />
        </el-form-item>
        <el-form-item label="用户名">
          <el-input v-model="form.username" @input="markDirty" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="form.password" type="password" show-password :placeholder="form.id ? '留空则沿用原密码' : ''" @input="markDirty" />
        </el-form-item>
      </el-form>
      <p class="hint">{{ tested ? '连接测试已通过，可以保存。' : '请先测试连接。改过主机、端口、库名、账号或密码后需要重新测试。' }}</p>
      <template #footer>
        <el-button @click="probe" :loading="probing">测试连接</el-button>
        <el-button type="primary" :disabled="!tested" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import http from '../api/http'

const rows = ref<any[]>([])
const visible = ref(false)
const tested = ref(false)
const probing = ref(false)
const saving = ref(false)
const form = reactive({ id: 0, name: '', dbType: 'postgres', host: '', port: 5432 as number | null, databaseName: '', username: '', password: '', enabled: true })

async function load() {
  const { data } = await http.get('/datasources')
  rows.value = data
}

function openCreate() {
  reset()
  visible.value = true
}

function openEdit(row: any) {
  form.id = row.id
  form.name = row.name
  form.dbType = row.dbType
  form.host = row.host
  form.port = row.port
  form.databaseName = row.databaseName
  form.username = row.username
  form.password = ''
  form.enabled = row.enabled
  tested.value = false
  visible.value = true
}

function typeText(dbType: string) {
  if (dbType === 'mysql') return 'MySQL'
  if (dbType === 'dm') return '达梦'
  return 'PostgreSQL'
}

function onType() {
  const defaults: Record<string, number> = { postgres: 5432, mysql: 3306, dm: 5236 }
  // 还停在某一类的默认端口时，跟着类型换成 5432、3306 或 5236
  if (form.port == null || form.port === 5432 || form.port === 3306 || form.port === 5236) {
    form.port = defaults[form.dbType] || 5432
  }
  markDirty()
}

function markDirty() {
  tested.value = false
}

function reset() {
  form.id = 0
  form.name = ''
  form.dbType = 'postgres'
  form.host = ''
  form.port = 5432
  form.databaseName = ''
  form.username = ''
  form.password = ''
  form.enabled = true
  tested.value = false
}

async function probe() {
  probing.value = true
  try {
    await http.post('/datasources/probe', {
      id: form.id || null,
      dbType: form.dbType,
      host: form.host,
      port: form.port,
      databaseName: form.databaseName,
      username: form.username,
      password: form.password
    })
    tested.value = true
    ElMessage.success('连接测试通过')
  } finally {
    probing.value = false
  }
}

async function save() {
  if (!tested.value) {
    ElMessage.warning('请先测试连接并通过')
    return
  }
  saving.value = true
  try {
    const body = { name: form.name, dbType: form.dbType, host: form.host, port: form.port, databaseName: form.databaseName, username: form.username, password: form.password, enabled: form.enabled }
    if (form.id) {
      await http.put('/datasources/' + form.id, body)
      ElMessage.success('已修改')
    } else {
      await http.post('/datasources', body)
      ElMessage.success('已新增')
    }
    visible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function remove(id: number) {
  await http.delete('/datasources/' + id)
  await load()
}

onMounted(load)
</script>
