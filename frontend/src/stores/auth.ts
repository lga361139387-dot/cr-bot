import { defineStore } from 'pinia'
import http from '../api/http'

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: localStorage.getItem('crbot_token') || '',
    username: ''
  }),
  actions: {
    async login(username: string, password: string) {
      const { data } = await http.post('/auth/login', { username, password })
      this.token = data.token
      this.username = data.username
      localStorage.setItem('crbot_token', data.token)
    },
    async me() {
      const { data } = await http.get('/auth/me')
      this.username = data.username
    },
    async logout() {
      await http.post('/auth/logout')
      this.token = ''
      this.username = ''
      localStorage.removeItem('crbot_token')
    }
  }
})
