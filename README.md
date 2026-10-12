# 书间 · 图书借阅系统

一个面向小型图书室的前后端分离项目：React 19 + Ant Design 6，Java 21 + Spring Boot 4.1.1，PostgreSQL 16。

已实现管理员与读者登录、首次改密、图书与分类管理、馆藏副本、读者账号、借出、归还、续借、逾期筛选和统计。业务规则为每人最多 5 册、借期 30 天、最多续借一次。书目和实体副本分开存储，事务、行锁与数据库约束共同保护借还一致性。

## 本地启动

需要 Node.js 24、Java 21 和 PostgreSQL 16 的命令行工具。项目自带 Maven Wrapper，无需使用特定全局 Maven 版本。

```bash
cd /Users/yj/Desktop/project/library-system
npm ci
npm run db:start
npm run build
npm run db:seed # 可选；已整理的数据库跳过演示书目初始化
npm run admin:create
npm run dev
```

- `db:start` 创建并启动本项目独立的本机 PostgreSQL，端口 55432，仅监听 127.0.0.1。不会连接阿里云或修改现有数据库。
- 本地实例使用无密码 trust 认证，应用账号 library_app 不是超级用户；此实例仅用于本机学习，不得改为公网监听。
- PostgreSQL 数据在 `~/Library/Application Support/library-system/postgres`，不放在源码目录；`db:stop` 停止该实例且保留数据。
- 开发环境首次启动、初始化或 seed 时，由 Flyway 执行未应用迁移，重复启动不重复建表。
- `db:seed` 只添加 4 种虚构书目和各 3 册副本，不创建默认账号，不清空数据。
- `admin:create` 在终端询问用户名、密码和密码确认。密码为 5～128 个字符，格式或长度不合要求会立即提示并重新询问，不启动应用；密码不回显、不写文件、不进入命令参数。初始化已有管理员时拒绝覆盖。源码变更后会先更新工具构建。
- `dev` 同时启动后端和前端，按 Ctrl+C 停止它们；数据库独立运行，可通过 `npm run db:stop` 停止。

前端：[http://localhost:5173](http://localhost:5173)  
后端就绪检查：[http://127.0.0.1:3001/api/v1/health/ready](http://127.0.0.1:3001/api/v1/health/ready)  
管理员登录后可访问：[http://localhost:3001/swagger-ui/index.html](http://localhost:3001/swagger-ui/index.html)。界面文档仅开放 GET 调试；业务写入需通过应用的 CSRF 和幂等机制。

请用 localhost 打开前端，默认可信 Origin 是 `http://localhost:5173`。如改为其他主机名或端口，同时修改 APP_ORIGIN 和 Vite API 代理。

## 项目目录

```text
apps/web/src/
  app/                  路由、登录上下文、布局
  features/             书目、读者、借还、借阅、统计
  shared/               HTTP 客户端、类型与基础组件
apps/api/src/main/java/com/example/library/
  auth/                 Spring Security、会话、CSRF、密码
  catalog/              图书、分类、馆藏副本
  readers/              读者与账号管理
  circulation/          借还事务、续借、幂等与查询
  statistics/           全馆统计
  common/               SQL Repository、异常、健康检查、初始化
apps/api/src/main/resources/db/migration/
  V1__initial_schema.sql 完整初始表、约束、函数与索引
apps/api/src/test/       真实 PostgreSQL 和 HTTP 集成测试
scripts/                本机数据库、初始化、测试、备份恢复
deploy/                 Docker、Compose、Nginx 示例
docs/                   需求、Java 架构、数据库与验证说明
```

后端采用 Controller → Service → SQL Repository，直接使用参数化 SQL。借、还、续借使用 `@Transactional`；初始化和改密使用 TransactionTemplate。使用 Spring 的 JDBC 事务绑定连接，不在事务中手工另开连接。

公开网络小说样本支持 `npm run catalog:crawl` 与 `npm run catalog:import`。默认采集约 1000 本番茄公开书目，保存来源、去重且不创建实体副本；简介使用题材及状态概览，不下载正文。详细步骤、批次检查及回退见[导入说明](docs/番茄小说书目导入.md)。

按本次学习练习要求，`npm run catalog:prepare` 删除无借阅历史的四本初始虚构演示书及副本，并为每本没有副本的番茄书目添加一册带说明的模拟副本。该命令会删除符合条件的演示数据，运行前先备份；有借阅历史时拒绝删除。`catalog:covers` 下载公开封面，`catalog:covers:import` 校验并关联本地图片。封面默认保存在 `~/Library/Application Support/library-system/covers`，可用 `LIBRARY_COVER_DIR` 修改。已整理的数据库不要再运行 `db:seed`，它会重新生成演示书目。

`npm run catalog:copies:distribute` 把初始一册样本补充为多数 1～3 册、少量 4～6 册的模拟馆藏，并安排分类书架与少量检修样本。它保留借阅历史、手工修改和已注销副本，重复运行不继续增量。操作前先备份，分布规则和回退方式见导入说明。

## 账号与业务流程

1. 初始化管理员并登录。
2. 添加分类、录入图书，在图书目录为书目添加副本；也可以使用虚构演示书目。
3. 创建读者账号，为其设置初始密码。读者首次登录必须修改密码。
4. 在借还工作台选择读者、核验实物副本编号并办理借出。
5. 在借阅记录或工作台搜索副本，办理正常/破损归还；符合规则时可续借。
6. 读者登录后只可浏览正常书目、自己的记录和续借，不能访问管理操作。

账号停用和改密撤销会话；停用读者的既有借阅仍可由管理员归还。已借出副本不能直接改为可借。注销副本不可恢复，归档图书需要没有未归还记录。图书、读者及借阅历史不提供硬删除入口。

初始管理员密码丢失时，在本机运行专用重置脚本：

```bash
bash scripts/reset-admin.sh
```

需要数据库管理权限；终端安全输入新密码，撤销该管理员已有会话。不要把真实密码提交到聊天或配置模板中。

## 验证

```bash
npm run typecheck
npm test
npm run build
npm audit
```

`npm test` 自动创建临时、随机端口的 PostgreSQL 测试实例，运行结束销毁测试数据。不会清空开发库。不要把测试配置指向真实业务库，测试类还会检查数据库名。

测试覆盖同册并发借出、同读者借阅上限、重复请求、并发续借、借出与归还失败回滚、期限边界、分类引用、ISBN 规范化、权限、CSRF、初始改密和会话撤销。浏览器和性能验证记录见 [验证记录](docs/验证记录.md)。

## 环境配置

应用直接读取进程环境变量，`.env.example` 仅是说明，不会自动加载。

| 变量 | 默认值 / 用途 |
| --- | --- |
| DATABASE_URL | jdbc:postgresql://127.0.0.1:55432/library_dev |
| DATABASE_USER | library_app |
| DATABASE_PASSWORD | 本机 trust 模式为空；正式使用通过秘密注入 |
| API_HOST / API_PORT | 127.0.0.1 / 3001 |
| APP_ORIGIN | http://localhost:5173 |
| COOKIE_SECURE | 开发 false；prod 固定 true |
| SPRING_PROFILES_ACTIVE | 部署使用 prod，关闭应用启动时自动迁移 |
| SPRING_FLYWAY_ENABLED | 可由独立迁移进程设为 true |

会话 Cookie 使用 HttpOnly、SameSite=Strict，生产启用 Secure；数据库只保存随机会话令牌摘要。密码使用带随机盐的 PBKDF2 并带算法版本标识。数据库连接池上限 5。登录限速以实例内来源 IP 计数，当前适用于单实例部署。

数据库与密码配置应置于工作区外的受限文件或运行环境。前端只包含公共接口类型，Java DTO 的正式接口结构通过 OpenAPI 对照；当前 TypeScript 接口类型由项目维护。

## 备份与恢复

```bash
bash scripts/backup.sh
bash scripts/restore-test.sh /绝对路径/备份文件.dump
```

备份默认保存到工作区外 `~/Library/Application Support/library-system/backups`，目录 700、文件 600。备份包含个人资料和密码哈希，不提交 Git。恢复脚本只创建以 `library_restore_` 开头的新数据库，不覆盖开发或业务数据库；输出数量及副本一致性核对结果，完成后再按需清理演练库。

## 部署

已有 Docker / Compose 环境可使用 `deploy/compose.yml`；本机此次未安装 Docker，未验证镜像构建或 Compose 实际启动。部署步骤见 [Java 架构设计](docs/图书借阅系统技术选型与架构设计.md)。

本项目此次仅在本机创建和验证，尚未部署到阿里云。部署前检查服务器资源、端口和宝塔配置，使用独立目录、数据库与秘密文件；迁移、初始化管理员和应用发布分别执行。

## 后续功能

批量导入导出、操作更正、可配置借阅政策、封面上传、预约、通知、遗失赔偿和多分馆属于需求中的 P1/P2。当前不提供这些能力。Java 代码格式、前端 lint 自动化可作为后续开发工具补充；现已提供编译、类型检查和业务测试。
