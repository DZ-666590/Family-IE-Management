# 个人 AI 配置与调用边界

## 用户流程

个人中心 → 账号设置 → AI 服务配置。所有家庭角色都可维护本人的配置。API 地址采用 OpenAI 兼容基础地址（例如 `https://api.example.com/v1`），模型名称手工填写；API 密钥是写入字段。

保存不联网。读取只有固定掩码与 `keyConfigured`，不提供任何密钥片段。密钥输入仅存在于当前组件内存和当前请求中，保存请求结束会清除请求对象中的密钥；保存开始即清空输入，失败需重新填写。草稿丢弃、退出/会话失效与卸载会清理，不进入 localStorage、sessionStorage 或 Query mutation cache。

测试按钮仅用于已保存配置，必须逐次勾选费用确认；发送 `GET {baseUrl}/models`，不发送提示词或财务数据、不执行推理。返回目录中是否包含所选模型；目录可访问不证明模型推理可用。部分兼容服务没有目录接口，会明确失败而不会自动改用付费推理测试。费用以服务商规则为准。

## 管理 API

复用 Spring Security 会话、有效家庭成员校验及 CSRF；没有用户 ID 参数。所有写入从登录用户解析 `userId`，家庭 OWNER/ADMIN 也不能读取别人的密钥。默认不开放跨站 CORS。

| 接口 | 行为 |
|---|---|
| `GET /api/me/ai-settings` | 本人配置、固定掩码、storageReady、allowedHosts；不存在返回空值 |
| `PUT /api/me/ai-settings` | 完整保存 baseUrl/model；apiKey 缺失或空串保留原密钥；首次保存或更换基础地址必须重新输入密钥 |
| `DELETE /api/me/ai-settings` | 删除本人的整份配置，可重复调用；存储加密服务未就绪时仍可删除 |
| `POST /api/me/ai-settings/test` | 请求体 `{ "confirmed": true }`；测试已保存模型目录 |

GET/PUT 成功响应使用 `ApiEnvelope.data`，设置字段为 `baseUrl`、`model`、`keyConfigured`、`maskedKey`、`storageReady`、`allowedHosts`、`updatedAt`；响应 `Cache-Control: no-store`。密钥更新对象的字符串形式固定为 REDACTED，apiKey 标记为只写。

失败使用 `ApiEnvelope.error`，关键代码：`AI_HTTPS_REQUIRED` / `AI_KEY_REQUIRED` / `AI_INVALID_SETTINGS` (400)，`AI_CONSENT_REQUIRED` (400)，`AI_REQUEST_TOO_LARGE` (413)，`AI_NOT_CONFIGURED` (409)，`AI_RATE_LIMITED` (429)，`AI_STORAGE_UNAVAILABLE` (503)，`AI_CONNECTION_FAILED` / `AI_PROVIDER_REJECTED` / `AI_INVALID_RESPONSE` / `AI_RESPONSE_TOO_LARGE` (502)。不返回供应商错误正文、密钥、URL 校验原文或底层异常。

请求体上限 16 KiB；基础 URL 最多 500 字符、模型最多 120 字符、密钥最多 2000 个可见 ASCII 字符。PUT/DELETE 先锁用户行，串行化该用户配置更新和删除，避免首次保存竞争与互相覆盖半份配置。

## 加密与服务器配置

- `APP_AI_ENCRYPTION_KEY`：Base64 编码的随机 32 字节 AES 密钥，由运维秘密存储注入。不要使用测试值，不放仓库、数据库、命令行参数或日志。
- `APP_AI_ALLOWED_HOSTS`：经审核的精确域名逗号列表，不接受通配符。默认空列表，禁止所有 AI 地址；不要加入用户可随意控制 DNS 的共享域名或任意转发服务。
- AES-256-GCM；每次写入随机 12 字节 nonce、128 位认证标签，AAD 包含用途/版本/用户 ID。库中格式 `v1.<nonce-base64>.<ciphertext-and-tag-base64>`。替换服务器密钥后旧密文不可解密；本版本不自动轮换。计划轮换时应先停用调用，保留旧密钥及备份以便受控迁移，或让用户重新输入密钥。
- 密钥缺失/无效时登录与财务功能继续工作；`storageReady=false`，AI 写入/解密失败关闭。密文损坏或用户绑定不符同样不返回明文。备份数据库同时必须独立保护服务器密钥；丢失密钥无法恢复原 API 密钥。

目前仓库默认入口为 HTTP。页面禁止输入/保存/测试 AI 密钥，后端 PUT/test 也只接受 `request.isSecure()`。上线前必须先提供 HTTPS，不应为演示关闭此限制。已保存配置可查看/删除，但仍建议所有账号行为通过 HTTPS。

反向代理终止 TLS 时，由运维配置容器只信任受控代理的协议转发信息（例如 Tomcat NATIVE forwarded headers + 严格 internal-proxies）；代理覆盖客户端传入的协议头，后端端口不得被外部直接访问。不要简单开启接受任意客户端头的转发过滤器。本分支不改变现有代理/服务器配置；直连 HTTP 伪造 X-Forwarded-Proto 不会通过本模块检测。参考 [Spring Boot 代理说明](https://docs.spring.io/spring-boot/3.3/how-to/webserver.html)。

禁止 HTTP wire/header/request-body 日志或 APM 请求体采集；默认关闭 Apache 的 wire/header logger。排障使用固定错误代码和现有请求 ID，不输出凭据或提示词。

## SSRF 与传输

`AiEndpointPolicy` 只接受 HTTPS、默认端口/443、精确允许域名和简单路径；拒绝 IP 字面量、用户信息、查询、片段、编码字符与点路径。只支持外部公网兼容服务；不支持 LAN/Ollama 本机端点。

Apache HttpClient 使用专用 DnsResolver；所有 DNS 回答都必须是公网，混合公私地址整体拒绝。特殊地址防护包含已弃用 6to4 及 IPv6 文档网段；依据 [IANA IPv4](https://www.iana.org/assignments/iana-ipv4-special-registry/) / [IPv6](https://www.iana.org/assignments/iana-ipv6-special-registry/) 特殊用途地址登记。校验后的地址直接用于本次建连，避免校验后重新 DNS 查询。保留 TLS 证书与主机名验证，不使用系统代理，不接受重定向，不自动重试；关闭 cookies 和自动压缩。连接/读超时 5/10 秒，20 秒请求取消，响应最多 64 KiB。DNS 查询通过 4 个专用线程、零积压队列隔离；调用者最多等待 3 秒且不超过本次请求截止时间。即使系统 DNS 忽略中断，最多占用 4 个隔离线程，之后查询直接失败，不持续占用 Servlet 线程或积压任务。运维还应设置出口防火墙拒绝内网/元数据网段。

模型目录测试和内部推理共享每用户每 30 秒一次、每进程最多 4 个并发请求的限额。限流为进程内，不是分布式全局限额；重启会重置。当前单实例部署适用。

## 业务模块调用契约

公开 Java 边界：`AiGateway.complete(Authentication, AiGateway.Prompt)`。参数 `Prompt(text, userApprovedExternalProcessing)`；鉴权身份须来自当前服务器 SecurityContext，禁止根据客户端传来的 userId 构造身份。调用前由业务模块明确展示将发送的内容、服务商和费用提示并获得用户操作；布尔值只是服务器模块间的前置契约，不能替代业务 UI 的实际确认。

只发送传入的文本（最多 8000 字符），固定 non-streaming、max_tokens=256；无工具、财务数据抓取、持久化、自动操作或后台任务。本次没有通用推理 HTTP 代理和聊天 UI。后续业务接入应处理 `AiFailure.code()/status()`，按普通文本渲染结果，不信任模型输出为操作指令，不记录提示词/响应。

服务商响应若直接包含密钥会拒绝返回，但不能把不可信服务商视作密钥安全边界：API 密钥本身按协议会发送给配置的服务商。允许域名审核与换地址必须重输密钥共同减少误发。

## 迁移与合入协调

`V26__personal_ai_settings.sql` 是本分支暂定编号（H2 测试/MySQL 各一份），在 `app_users` 之上增加 `user_ai_settings`；主键兼外键为 user_id。无需财务数据回填，也不依赖其他模块迁移。

合入 stage2 前必须统筹编号/顺序，并同步 7 个旧迁移测试文件的最新版本断言。本分支不会假定其他模块已部署；不得修改已部署 Flyway 脚本。新增表的 MySQL 脚本尚未在真实 MySQL 上执行验证，需在独立 MySQL 测试库验收后部署。

复用方案与实现计划见 [个人 AI 设置实施计划](../superpowers/plans/2026-09-08-ai-settings.md)。

## 独立审查

只读安全审查提出 DNS 等待隔离问题；新增 `AiDnsLookup` 的容量/超时/截止时间控制与模拟阻塞测试后，复核通过，无阻塞项。审查另提空响应可能 500；当前 Jackson 3 / Spring Boot 4.1 下新增的 4 个回归用例证明原实现已正确返回安全错误，保留用例而未据此改动业务代码。

## 本分支验证记录（2026-09-08）

- Java 17.0.18：AI 配置/加密/地址/DNS/调用单元与 MockMVC、迁移、登录鉴权、现有行情模拟客户端共 114 项；112 通过，0 失败，2 项因未提供一次性 MySQL 库跳过。
- 前端：43 文件 / 266 项通过；TypeScript 检查与生产构建通过。构建仍提示现有大体积 chunk（主包超过 500 kB）。
- 测试仅使用隔离 H2、MockMVC、mock provider 与浏览器 DOM 测试环境；未启动本地业务应用，未运行 Windows/Unix 启动认证，未调用真实 AI 服务，未触及生产库或服务器。
- 尚未验证：真实 MySQL、反向代理 HTTPS、真实服务商目录/推理、线上/真实浏览器视觉行为。下一步先协调迁移编号与运维启用条件，再由统一集成流程安排验收。

复现 Java 测试（指定 Java 17 环境）：

```bash
./mvnw -q -Dskip.installnodenpm -Dskip.npm '-Dtest=AiDnsLookupTest,AiSettingsApiTest,AiSafetyTest,AiStorageUnavailableApiTest,PersonalAiGatewayTest,*MigrationTest,FlywayFreshDatabaseTest,FlywayStageOneUpgradeTest,AuthenticationApiTest,EmailAuthenticationTest,TushareQuoteProviderTest,MarketDataClientOverseasTest' test
```

前端：`npm --prefix frontend test -- --run`、`npm --prefix frontend run typecheck`、`npm --prefix frontend run build`。
