<template>
  <div class="app">
    <aside class="sidebar">
      <div class="brand">
        <div class="brand-logo">CR</div>
        <div class="brand-text">
          <h1>CRBOT</h1>
          <p>代码评审控制台</p>
        </div>
      </div>
      <nav class="nav">
        <div class="nav-label">概览</div>
        <router-link class="nav-item" to="/dashboard" active-class="active"><span class="icon">◈</span> 仪表盘</router-link>
        <router-link class="nav-item" to="/token-usage" active-class="active"><span class="icon">T</span> Token 用量</router-link>
        <div class="nav-label">审查</div>
        <router-link class="nav-item" to="/reviews" :class="{ active: reviewsActive }">
          <span class="icon">☰</span> 评审记录
          <span v-if="failedCount > 0" class="badge" title="失败任务">{{ failedCount }}</span>
        </router-link>
        <router-link class="nav-item" to="/quality" active-class="active"><span class="icon">▦</span> 质量看板</router-link>
        <div class="nav-label">配置</div>
        <router-link class="nav-item" to="/subscriptions" active-class="active"><span class="icon">◎</span> 分支订阅</router-link>
        <router-link class="nav-item" to="/standards" active-class="active"><span class="icon">§</span> 编码规范</router-link>
        <router-link class="nav-item" to="/knowledge" active-class="active"><span class="icon">▤</span> 知识库</router-link>
        <router-link class="nav-item" to="/catalog" active-class="active"><span class="icon">＋</span> 基础资料</router-link>
        <router-link class="nav-item" to="/datasources" active-class="active"><span class="icon">▣</span> 数据源</router-link>
        <router-link class="nav-item" to="/policy" active-class="active"><span class="icon">ⓘ</span> 策略说明</router-link>
        <router-link class="nav-item" to="/settings" active-class="active"><span class="icon">⚙</span> 系统设置</router-link>
      </nav>
    </aside>
    <div class="main">
      <header class="topbar">
        <div class="topbar-left">
          <div class="page-title">{{ title }}</div>
        </div>
        <div class="topbar-right">
          <span>{{ clock }}</span>
          <div class="user-chip">
            <div class="avatar">{{ avatar }}</div>
            <span>{{ auth.username || '管理员' }}</span>
          </div>
          <button class="link-btn" @click="exit">退出</button>
        </div>
      </header>
      <div class="content">
        <router-view />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import http from '../api/http'
import { useAuthStore } from '../stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const failedCount = ref(0)
const clock = ref('')
let timer = 0

const reviewsActive = computed(() => route.path.startsWith('/reviews'))
const title = computed(() => (route.meta.title as string) || 'CRBOT')
const avatar = computed(() => (auth.username || '管').slice(0, 1))

function tick() {
  const now = new Date()
  const pad = (n: number) => String(n).padStart(2, '0')
  clock.value = `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())} ${pad(now.getHours())}:${pad(now.getMinutes())}:${pad(now.getSeconds())}`
}

onMounted(() => {
  auth.me().catch(() => undefined)
  http.get('/reviews/stats').then(({ data }) => { failedCount.value = data.failed || 0 }).catch(() => undefined)
  tick()
  timer = window.setInterval(tick, 1000)
})

onUnmounted(() => window.clearInterval(timer))

async function exit() {
  await auth.logout()
  await router.push('/login')
}
</script>
