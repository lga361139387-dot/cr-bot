<template>
  <div class="anno" @click.stop>
    <div class="rates">
      <button v-for="item in rates" :key="item.value" type="button" class="rate" :class="[item.tone, { on: label === item.value }]" @click="label = item.value">
        {{ item.label }}
      </button>
    </div>
    <el-input v-model="remark" type="textarea" :rows="4" placeholder="写下这次标注的说明" />
    <div class="anno-actions">
      <el-button size="small" type="primary" :loading="saving" @click.stop="save">提交</el-button>
      <span class="hint">只记录标注，不会重新跑评审。</span>
    </div>
    <div v-if="history.length" class="notes">
      <article v-for="(item, index) in history" :key="item.id" class="note" :class="'tone-' + (index % 3)">
        <div class="note-head">{{ item.operator || '未知' }} · {{ labelText(item.label) }}</div>
        <p>{{ item.remark || '没有备注' }}</p>
        <div class="note-time">{{ formatTime(item.createdAt) }}</div>
      </article>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import http from '../api/http'
import { formatTime } from '../labels'

const props = defineProps<{ jobId: number }>()
const emit = defineEmits<{ saved: [] }>()
const label = ref('')
const remark = ref('')
const saving = ref(false)
const history = ref<any[]>([])
const rates = [
  { value: 'ACCURATE', label: '准确', tone: 'cool' },
  { value: 'MEDIUM', label: '适中', tone: 'mild' },
  { value: 'LOW', label: '偏低', tone: 'warm' },
  { value: 'WRONG', label: '错误', tone: 'hot' }
]

watch(() => props.jobId, loadHistory, { immediate: true })

async function loadHistory() {
  if (!props.jobId) {
    history.value = []
    return
  }
  const { data } = await http.get('/reviews/' + props.jobId + '/annotations')
  history.value = data
}

async function save() {
  if (!label.value) {
    ElMessage.warning('请选择准确率')
    return
  }
  saving.value = true
  try {
    await http.post('/reviews/' + props.jobId + '/annotations', {
      label: label.value,
      remark: remark.value
    })
    remark.value = ''
    ElMessage.success('已保存标注')
    await loadHistory()
    emit('saved')
  } catch {
    // 拦截器已经提示
  } finally {
    saving.value = false
  }
}

function labelText(value: string) {
  if (value === 'ACCURATE' || value === 'CORRECT') return '准确'
  if (value === 'MEDIUM' || value === 'PARTIAL') return '适中'
  if (value === 'LOW' || value === 'NEED_CONTEXT') return '偏低'
  if (value === 'WRONG' || value === 'FALSE_POSITIVE') return '错误'
  return value
}
</script>

<style scoped>
.anno { display: flex; flex-direction: column; gap: 8px; margin-top: 8px; }
.rates { display: flex; gap: 8px; }
.rate {
  border: 0;
  border-radius: 8px;
  padding: 6px 14px;
  cursor: pointer;
  font-weight: 600;
}
.rate.cool { background: #dbeafe; color: #1d4ed8; }
.rate.mild { background: #fef3c7; color: #92400e; }
.rate.warm { background: #ffedd5; color: #c2410c; }
.rate.hot { background: #fee2e2; color: #b91c1c; }
.rate.on { outline: 2px solid #111827; }
.notes { display: flex; flex-direction: column; gap: 8px; }
.note {
  padding: 10px 12px;
  border-radius: 2px 10px 10px 10px;
  box-shadow: 0 1px 0 rgba(16, 24, 40, 0.06);
}
.tone-0 { background: #fff6cc; }
.tone-1 { background: #e7f8e7; }
.tone-2 { background: #ffe8d6; }
.note-head { font-weight: 600; margin-bottom: 4px; }
.note p { margin: 0; }
.note-time { margin-top: 6px; font-size: 12px; color: #667085; }
.anno-actions { display: flex; align-items: center; gap: 8px; }
</style>
