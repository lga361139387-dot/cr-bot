<template>
  <div class="chat">
    <h3>标注对话</h3>
    <p class="hint">把要标注或修正的内容发到这个群聊。CRBOT 认为需要改时，才会出现确认按钮。每个人的发言都会留下来。</p>
    <div v-if="job.voided === 1" class="alert alert-info">这次评审已作废。</div>
    <div class="thread">
      <div v-for="item in messages" :key="item.id" class="bubble" :class="item.role === 2 ? 'ai' : 'person'">
        <div class="who">{{ item.role === 2 ? 'CRBOT' : (item.speaker || '有人') }}</div>
        <div>{{ displayText(item) }}<span v-if="isTyping(item)" class="caret"></span></div>
      </div>
      <div v-if="waiting" class="bubble ai">
        <div class="who">CRBOT</div>
        <div class="dots"><i></i><i></i><i></i></div>
      </div>
      <div v-if="messages.length === 0 && !waiting" class="hint">还没有对话</div>
    </div>
    <div v-if="offer" class="offer">
      <span>{{ offer.mode === 2 ? 'CRBOT 建议只调整综述和备注' : 'CRBOT 建议完整重跑评审' }}</span>
      <button class="btn btn-primary btn-sm" type="button" :disabled="busy" @click="confirm">确认</button>
    </div>
    <div v-if="canTalk" class="composer">
      <p v-if="me" class="hint">当前以 {{ me }} 发言</p>
      <el-input v-model="text" type="textarea" :rows="4" :disabled="waiting" placeholder="例如：这条缺少注释是误报，项目约定生成代码不用行注释" />
      <button class="btn btn-primary" type="button" :disabled="waiting" @click="send">发送</button>
    </div>
    <p class="hint">已重跑 {{ job.rereviewRound || 0 }} / 3 次。Token 按次累加。</p>
    <button v-if="(job.rereviewRound || 0) >= 3 && job.voided !== 1" class="btn btn-ghost" type="button" @click="voidReview">作废</button>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api/http'

const props = defineProps<{ job: any }>()
const emit = defineEmits<{ changed: [] }>()
const messages = ref<any[]>([])
const text = ref('')
const busy = ref(false)
const waiting = ref(false)
const me = ref('')
const typed = ref('')
const typingId = ref<number | null>(null)
let timer = 0

const offer = computed(() => {
  const last = [...messages.value].reverse().find(item => item.role === 2 && item.needChange === 1 && item.confirmed === 0)
  return last || null
})
const canTalk = computed(() => props.job?.status === 'SUCCESS' && props.job?.voided !== 1 && (props.job?.rereviewRound || 0) < 3)

watch(() => props.job?.id, async (id) => {
  await loadMe()
  await load(id)
}, { immediate: true })

async function loadMe() {
  const { data } = await http.get('/auth/me')
  me.value = data?.username || ''
}

async function load(id = props.job?.id) {
  if (!id) {
    messages.value = []
    return
  }
  const { data } = await http.get('/reviews/' + id + '/feedback-chat')
  messages.value = data
}

function displayText(item: any) {
  return typingId.value === item.id ? typed.value : item.content
}

function isTyping(item: any) {
  return typingId.value === item.id
}

function play(item: any) {
  window.clearInterval(timer)
  typed.value = ''
  typingId.value = item.id
  let index = 0
  timer = window.setInterval(() => {
    index += 1
    typed.value = item.content.slice(0, index)
    if (index >= item.content.length) {
      window.clearInterval(timer)
      typingId.value = null
    }
  }, 16)
}

async function send() {
  if (!text.value.trim() || waiting.value) return
  waiting.value = true
  busy.value = true
  const draft = text.value.trim()
  text.value = ''
  try {
    const { data } = await http.post('/reviews/' + props.job.id + '/feedback-chat', { content: draft })
    messages.value = data
    const last = [...data].reverse().find((item: any) => item.role === 2)
    if (last) play(last)
  } finally {
    waiting.value = false
    busy.value = false
  }
}

async function confirm() {
  try {
    await ElMessageBox.confirm(offer.value?.mode === 2 ? '只改这次报告的综述，不重跑模型评审流水线。' : '按对话内容完整重跑这次评审。', '确认变更', { type: 'warning' })
  } catch {
    return
  }
  busy.value = true
  try {
    const { data } = await http.post('/reviews/' + props.job.id + '/feedback-chat/confirm')
    messages.value = data
    ElMessage.success(offer.value ? '已确认' : '已提交')
    emit('changed')
  } finally {
    busy.value = false
  }
}

async function voidReview() {
  try {
    await ElMessageBox.confirm('只把这次评审标记为作废，不删除报告和对话。', '作废', { type: 'warning' })
  } catch {
    return
  }
  await http.post('/reviews/' + props.job.id + '/void')
  ElMessage.success('已作废')
  emit('changed')
}
</script>

<style scoped>
.chat { margin-top: 16px; }
.thread { display: flex; flex-direction: column; gap: 8px; margin: 12px 0; max-height: 360px; overflow: auto; }
.bubble { padding: 8px 12px; border-radius: 10px; max-width: 80%; }
.person { align-self: flex-end; background: #e8f1ff; }
.ai { align-self: flex-start; background: #f4f5f7; }
.dots { display: flex; gap: 4px; height: 16px; align-items: center; }
.dots i { width: 6px; height: 6px; border-radius: 50%; background: #98a2b3; animation: blink 1s infinite; }
.dots i:nth-child(2) { animation-delay: .2s; }
.dots i:nth-child(3) { animation-delay: .4s; }
.caret { display: inline-block; width: 1px; height: 1em; background: #344054; margin-left: 2px; vertical-align: text-bottom; }
@keyframes blink { 50% { opacity: .2; } }
.who { font-size: 12px; color: #667085; margin-bottom: 4px; }
.composer { display: flex; flex-direction: column; gap: 8px; align-items: flex-start; }
.offer { display: flex; gap: 12px; align-items: center; margin-bottom: 8px; }
</style>
