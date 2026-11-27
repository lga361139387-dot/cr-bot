# 参与贡献

欢迎提交 Issue 和 Pull Request。请说明复现步骤、预期行为和实际行为，不要附带令牌、私有代码或真实业务数据。

本地检查：

```sh
mvn -f backend/pom.xml verify
cd frontend
npm ci
npm run build
```

后端使用 Java 17，前端使用 Node.js 22。涉及模型接口的变更请补充模拟响应测试；涉及表结构的变更通过新增 Flyway migration 完成，不修改已发布迁移。提交贡献即表示同意按本项目 MIT 协议发布该贡献。
