<template>
  <div class="page ep-page" v-if="form">
    <div class="section-head">
      <div>
        <h2>系统设置</h2>
      </div>
    </div>

    <div class="card" style="margin-bottom: 16px">
      <div class="card-title">GitLab</div>
      <el-form label-width="140px" style="max-width: 860px">
        <el-form-item>
          <template #label><el-tooltip content="拉取项目、分支和代码差异的地址" placement="top"><span>地址</span></el-tooltip></template>
          <el-input v-model="form.gitlabBaseUrl" placeholder="https://gitlab.company.com" />
        </el-form-item>
        <el-form-item>
          <template #label><el-tooltip :content="form.gitlabTokenConfigured ? '已配置，留空不修改' : '需要能读取仓库的令牌'" placement="top"><span>访问令牌</span></el-tooltip></template>
          <el-input v-model="secrets.gitlabToken" type="password" show-password placeholder="留空不修改" />
        </el-form-item>
        <el-form-item>
          <template #label><el-tooltip :content="form.webhookSecretConfigured ? '已配置，需与 GitLab Webhook 密钥一致' : '与 GitLab Webhook 里填写的密钥一致'" placement="top"><span>Webhook 密钥</span></el-tooltip></template>
          <el-input v-model="secrets.webhookSecret" type="password" show-password placeholder="留空不修改" />
        </el-form-item>
      </el-form>
    </div>

    <div class="card" style="margin-bottom: 16px">
      <div class="card-title">模型接入 · OpenAI 兼容协议</div>
      <el-alert title="对话和向量服务可以来自不同供应商。先保存配置，再测试连接（会产生少量调用费用）。" type="info" :closable="false" style="margin-bottom: 20px" />
      <el-form label-width="140px" style="max-width: 860px">
        <el-form-item>
          <template #label><el-tooltip content="对话接口地址" placement="top"><span>接口地址</span></el-tooltip></template>
          <el-input v-model="form.llmBaseUrl" placeholder="例如 https://your-gateway.example/v1 或 http://ollama:11434/v1" />
        </el-form-item>
        <el-form-item>
          <template #label><el-tooltip content="写评审结论的对话模型" placement="top"><span>对话模型</span></el-tooltip></template>
          <el-input v-model="form.llmModel" placeholder="填写服务端提供的模型 ID，支持自定义模型" />
        </el-form-item>
        <el-form-item>
          <template #label><el-tooltip :content="form.llmApiKeyConfigured ? '已配置，留空不修改' : '云服务需要密钥；本地无鉴权服务可留空'" placement="top"><span>对话密钥</span></el-tooltip></template>
          <el-input v-model="secrets.llmApiKey" type="password" show-password placeholder="留空不修改" />
        </el-form-item>
        <el-form-item label="工具调用"><el-switch v-model="form.llmToolsEnabled" /><span style="margin-left: 12px">模型支持 tools 时启用</span></el-form-item>
        <el-form-item label="温度"><el-input v-model="form.llmTemperature" placeholder="留空使用模型默认值；可填写 0 到 2" /></el-form-item>
        <el-form-item label="向量接口地址"><el-input v-model="form.embeddingBaseUrl" placeholder="留空复用对话地址；可单独接入向量服务" /></el-form-item>
        <el-form-item>
          <template #label><el-tooltip content="编码规范和知识库的向量模型，需输出 1024 维" placement="top"><span>向量模型</span></el-tooltip></template>
          <el-input v-model="form.embeddingModel" />
        </el-form-item>
        <el-form-item>
          <template #label><el-tooltip :content="form.embeddingApiKeyConfigured ? '已单独配置，留空不修改' : '同一接口可复用对话密钥；不同接口请单独填写'" placement="top"><span>向量密钥</span></el-tooltip></template>
          <el-input v-model="secrets.embeddingApiKey" type="password" show-password placeholder="留空不修改" />
        </el-form-item>
        <el-form-item label="连接检查">
          <el-button :loading="testing === 'chat'" :disabled="!!testing" @click="testModel('chat')">测试已保存的对话配置</el-button>
          <el-button :loading="testing === 'embedding'" :disabled="!!testing" @click="testModel('embedding')">测试已保存的向量配置</el-button>
        </el-form-item>
        <el-form-item><span>向量维度固定为 1024；更换向量模型后需重新索引知识库和规范。Docker 内本机服务可用 host.docker.internal。</span></el-form-item>
      </el-form>
    </div>

    <div class="card">
      <div class="card-title">评审</div>
      <el-form label-width="140px" style="max-width: 860px">
        <el-form-item>
          <template #label><el-tooltip content="评审完成后把摘要写到合并请求评论" placement="top"><span>回写评论</span></el-tooltip></template>
          <el-switch v-model="form.postMrNote" />
        </el-form-item>
        <el-form-item>
          <template #label><el-tooltip content="上传文档先识别疑点，确认后才分块入库" placement="top"><span>知识库预审</span></el-tooltip></template>
          <el-switch v-model="form.kbPrecheckEnabled" />
        </el-form-item>
        <el-form-item label="二轮复核"><el-switch v-model="form.secondRoundEnabled" /></el-form-item>
        <el-form-item label="事实过滤"><el-switch v-model="form.postFilterEnabled" /></el-form-item>
        <el-form-item label="辅助定位"><el-switch v-model="form.relocateEnabled" /></el-form-item>
        <el-form-item label="按路径应用规则"><el-switch v-model="form.pathRulesEnabled" /></el-form-item>
        <el-form-item>
          <el-button type="primary" @click="save">保存</el-button>
        </el-form-item>
      </el-form>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import http from '../api/http'

const form = ref<any>(null)
const testing = ref('')
async function testModel(kind: string) {
  testing.value = kind
  try {
    const { data } = await http.post(`/settings/models/test/${kind}`)
    if (data.success) ElMessage.success(`${data.message} · ${data.latencyMs}ms`)
    else ElMessage.error(data.message)
  } finally { testing.value = '' }
}
const secrets = reactive({ gitlabToken: '', webhookSecret: '', llmApiKey: '', embeddingApiKey: '' })

async function load() {
  const { data } = await http.get('/settings')
  form.value = data
  secrets.gitlabToken = ''
  secrets.webhookSecret = ''
  secrets.llmApiKey = ''
  secrets.embeddingApiKey = ''
}

async function save() {
  const { data } = await http.put('/settings', { ...form.value, ...secrets })
  form.value = data
  secrets.gitlabToken = ''
  secrets.webhookSecret = ''
  secrets.llmApiKey = ''
  secrets.embeddingApiKey = ''
  ElMessage.success('已保存')
}

onMounted(load)
</script>

<style scoped>
.level-block { margin: 8px 0 28px; }
.level-title { font-weight: 600; }
</style>
