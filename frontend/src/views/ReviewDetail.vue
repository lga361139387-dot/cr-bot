<template>
  <div v-if="detail">
    <div class="section-head">
      <div>
        <h2>评审 #{{ detail.job.id }}</h2>
        <p>{{ detail.job.projectPath }} / {{ detail.job.refBranch }}</p>
      </div>
      <div>
        <span v-if="detail.job.voided === 1" class="badge badge-high">已作废</span>
        <button class="btn btn-ghost" type="button" @click="logId = detail.job.id">日志</button>
        <button class="btn btn-ghost" @click="router.push('/reviews')">返回记录</button>
      </div>
    </div>
    <ReviewLogDialog :job-id="logId" @close="logId = null" />
    <div class="card">
      <ReviewBody :detail="detail" @retry="retry" @refresh="load" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import http from '../api/http'
import ReviewBody from '../components/ReviewBody.vue'
import ReviewLogDialog from '../components/ReviewLogDialog.vue'

const route = useRoute()
const router = useRouter()
const detail = ref<any>(null)
const logId = ref<number | null>(null)

async function load() {
  const { data } = await http.get('/reviews/' + route.params.id)
  detail.value = data
}

async function retry() {
  await http.post('/reviews/' + route.params.id + '/retry')
  ElMessage.success('已重新排队')
  await load()
}

onMounted(load)
</script>
