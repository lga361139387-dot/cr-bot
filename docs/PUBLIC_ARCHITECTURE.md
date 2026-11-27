# CRbot 架构

GitLab Webhook 验证密钥并匹配分支订阅，事件通过唯一约束去重后进入 PostgreSQL 队列。Worker 领取任务，Agent 拉取 diff，过滤无关文件并分组评审。

Agent 聚合启用的规范、业务知识检索结果和数据库上下文，通过兼容 Chat Completions 的接口调用模型。工具支持读取源码、检索文档和只读数据库查询。结论通过 JSON 解析、证据定位与可选事实过滤生成报告。

报告、问题和反馈持久化，Vue 看板负责呈现；可选回写 MR 评论。TokenLedger 记录模型用量。IDE MCP 通过认证后的只读入口访问报告和知识库。

## 模型协议

- 对话：`POST {LLM_BASE_URL}/chat/completions`，非流式 messages/tools。
- 向量：`POST {EMBEDDING_BASE_URL}/embeddings`，input 字符串数组，返回 1024 维向量。
- 向量地址未配置时复用对话地址；仅在地址完全相同时自动复用对话密钥。
- 对话可关闭 tools，可省略 temperature；无密钥时不发送 Authorization。
- 管理员连接测试：`POST /api/v1/settings/models/test/chat` 或 `/embedding`。测试使用已保存的配置。

## 边界

面向单团队单实例。重启会恢复 RUNNING 任务；当前不实现分布式租约。人工反馈用于评审上下文与标注，不执行模型训练。知识库更换模型后必须重建索引，不能混用不同模型的向量。
