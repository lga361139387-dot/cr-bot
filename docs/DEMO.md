# 演示指南

使用 `docker compose -f docker-compose.yml -f docker-compose.demo.yml up --build -d` 启动。

1. 登录后查看仪表盘：3 条已完成的示例评审。
2. 打开 `[DEMO] 订单取消与退款`：查看高危业务发现、示例源码片段和修复建议。
3. 对报告添加人工标注，体验反馈流程。
4. 打开系统设置，为自己的 OpenAI 兼容服务填写地址、模型和密钥，保存后测试连接。
5. 正式对接 GitLab 时添加自己的订阅。演示订阅默认关闭。

示例均为虚构数据，模型名固定 `demo-fixture`，不是实际 LLM 输出。演示数据仅在显式开启 `CRBOT_DEMO_ENABLED=true` 时导入，按 `demo/order-service` 标记幂等，不修改已有业务报告。请勿给真实项目使用该演示路径。

预置报告可离线浏览；知识库索引、反馈对话和重新评审需要真实外部服务。此演示不冒充完整 GitLab/模型端到端测试。
