# 项目协作

- 本项目为 React / TypeScript + Java 21 / Spring Boot 4.1.1 + PostgreSQL 16。
- 需求与架构在 docs；后端业务采用参数化 SQL 和 Spring JDBC，避免绕过 Service 办理借还。
- 执行测试用 `npm test`，仅使用自动创建的临时 PostgreSQL；禁止将测试配置指向开发库或真实业务库。
- 本机数据库数据在工作区外；源码不保存密码、会话令牌、私钥或备份，初始化在终端安全输入。
- 对需求或表结构变更同步文档，已应用 Flyway 迁移不得修改，新增后续迁移。
- 运行 typecheck、适当业务测试及 build；前端关注中文反馈、键盘访问和窄屏使用。
- 未经用户部署指令，不连接或修改阿里云，部署须独立目录与数据库。
