import { createRouter, createWebHistory } from 'vue-router'
import Login from '../views/Login.vue'
import Layout from '../views/Layout.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: Login },
    {
      path: '/',
      component: Layout,
      redirect: '/dashboard',
      children: [
        { path: 'dashboard', meta: { title: '仪表盘' }, component: () => import('../views/Dashboard.vue') },
        { path: 'token-usage', meta: { title: 'Token 用量' }, component: () => import('../views/TokenUsage.vue') },
        { path: 'reviews', meta: { title: '评审记录' }, component: () => import('../views/Reviews.vue') },
        { path: 'reviews/:id', meta: { title: '评审详情' }, component: () => import('../views/ReviewDetail.vue') },
        { path: 'quality', meta: { title: '质量看板' }, component: () => import('../views/Quality.vue') },
        { path: 'standards', meta: { title: '编码规范' }, component: () => import('../views/Standards.vue') },
        { path: 'knowledge', meta: { title: '知识库' }, component: () => import('../views/Knowledge.vue') },
        { path: 'catalog', meta: { title: '基础资料' }, component: () => import('../views/Catalog.vue') },
        { path: 'datasources', meta: { title: '数据源' }, component: () => import('../views/Datasources.vue') },
        { path: 'subscriptions', meta: { title: '分支订阅' }, component: () => import('../views/Subscriptions.vue') },
        { path: 'policy', meta: { title: '策略说明' }, component: () => import('../views/Policy.vue') },
        { path: 'settings', meta: { title: '系统设置' }, component: () => import('../views/Settings.vue') }
      ]
    }
  ]
})

router.beforeEach((to) => {
  const token = localStorage.getItem('crbot_token')
  if (to.path !== '/login' && !token) {
    return '/login'
  }
  if (to.path === '/login' && token) {
    return '/dashboard'
  }
  return true
})

export default router
