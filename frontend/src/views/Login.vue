<template>
  <div class="login-wrap">
    <form class="login-card" @submit.prevent="submit">
      <div class="brand-logo">CR</div>
      <h2>CRBOT</h2>
      <div class="field"><label>用户名</label><input v-model="username" autocomplete="username" /></div>
      <div class="field"><label>密码</label><input v-model="password" type="password" autocomplete="current-password" /></div>
      <button class="btn btn-primary" :disabled="loading" type="submit">{{ loading ? '登录中…' : '登录' }}</button>
      <p v-if="error" class="hint" style="color: #b91c1c">{{ error }}</p>
    </form>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const router = useRouter()
const auth = useAuthStore()
const username = ref('admin')
const password = ref('')
const loading = ref(false)
const error = ref('')

async function submit() {
  loading.value = true
  error.value = ''
  try {
    await auth.login(username.value, password.value)
    await router.push('/dashboard')
  } catch (ex: any) {
    error.value = ex?.response?.data?.message || '登录失败'
  } finally {
    loading.value = false
  }
}
</script>
