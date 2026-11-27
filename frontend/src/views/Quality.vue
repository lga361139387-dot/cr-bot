<template>
  <div class="page ep-page">
    <div class="section-head">
      <div>
        <h2>代码质量看板</h2>
        <p>规划项。按提交人筛选缺陷、统计个人和团队高频问题，本次只预留入口和角色字段。</p>
      </div>
    </div>
    <div class="card" style="margin-bottom: 16px">
      <div class="toolbar">
        <el-select disabled placeholder="提交人" style="width: 180px" />
        <el-select disabled placeholder="迭代" style="width: 180px" />
        <el-select disabled placeholder="角色：开发 / 负责人 / DBA / 产品" style="width: 280px" />
      </div>
      <p class="hint">{{ boardMessage }}</p>
    </div>
    <div class="card">
      <div class="card-title">迭代变更汇总</div>
      <p class="hint">{{ deliveryMessage }}</p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import http from '../api/http'

const boardMessage = ref('正在读取预留接口')
const deliveryMessage = ref('')

onMounted(async () => {
  const [board, delivery, gate] = await Promise.all([
    http.get('/reserved/quality-board'),
    http.get('/reserved/iterations/0/delivery'),
    http.post('/reserved/gates/merge')
  ])
  boardMessage.value = board.data.message
  deliveryMessage.value = delivery.data.message
  if (gate.data.blocked) {
    boardMessage.value = gate.data.message
  }
})
</script>
