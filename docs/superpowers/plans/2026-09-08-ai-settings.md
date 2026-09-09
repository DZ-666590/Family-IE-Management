# 个人 AI 设置实施计划

Goal: 在账号设置维护本人 OpenAI 兼容地址、模型与加密密钥，提供受控服务端调用边界。
Architecture: 复用会话/CSRF、CurrentMembership、JPA/Flyway、Clarity 设置卡。独立 ai 包负责配置、AES-GCM、地址策略、HTTP 适配；业务调用只接受当前鉴权用户。
Tech Stack: Java 17 / Spring Boot 4.1.1 / React / Apache HttpClient 5 (Boot 管理版本)。

## 已授权边界
- 工作目录 module-ai-settings，分支 codex/module-ai-settings；不启动业务应用、不推送/合并/部署。
- 密钥仅内存输入与加密数据库；不回传、不打日志、不使用浏览器存储。
- HTTPS 必需；后端只信任容器 secure 状态，不能信任客户端随意传入的 forwarded 头。
- 自定义域名需精确运维允许名单；仅 HTTPS/443；禁止凭据、query、fragment、IP、编码路径、点路径；实际 DNS 建连地址全部为公网，禁止跳转、代理与自动重试。
- 保存不会联网。测试需明确确认，仅 GET models；不请求推理。不自动发送账目、调用工具或写财务数据。
- AES-256-GCM，随机 12 字节 nonce，用户 ID 绑定 AAD。服务器密钥为空/无效时 AI 功能不可用，其他业务可运行。删除仍允许。
- 统一集成后使用 V28；保留周期账单 V26，账户资料使用 V27。测试 H2 与 MySQL 独立脚本。

## 执行与验证
- [x] 配置 API：先 MockMVC 证明当前接口不存在；GET/PUT/DELETE，身份从会话获取，隔离同家庭用户，读取脱敏，替换地址必须重新输入密钥，HTTP 与未配置加密密钥拒绝。
- [x] 安全/调用：AES 篡改与用户绑定测试、非法地址/DNS 测试；Apache 客户端对解析结果直接建连，响应体/时间上限；内部 AiGateway 无 HTTP 推理入口；固定目录测试需确认。
- [x] UI：账号设置新增 AiSettingsCard，复用设置卡与草稿清理；普通 async 操作避免 React Query mutation 缓存密钥；加载/错误/保存/删除/确认测试/HTTP 禁用；UI 测试与 TypeScript/构建。
- [x] 回归：只运行 MockMVC/单元/Flyway 独立测试库；记录结果，审查差异，仅提交本模块文件。

## Build vs Reuse (2026-09-08)
1. Spring AI 2.0：官方支持 Boot 4.0/4.1；适合更完整 AI 能力，此次配置与一次调用无需引入自动配置/多模型体系。https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA-available-now/
2. LangChain4j：Java 17 可用，适合 AI 服务/工具编排，此次没有 RAG/工具需求。https://github.com/langchain4j/langchain4j/blob/main/docs/docs/get-started.md
3. Apache HttpClient 5：复用可注入 DNS resolver、TLS 与超时能力，避免手写 TLS 和 DNS 重绑定防护；新增一个 Boot 管理依赖。https://hc.apache.org/httpcomponents-client-5.6.x/current/httpclient5/apidocs/org/apache/hc/client5/http/impl/io/PoolingHttpClientConnectionManagerBuilder.html
4. JDK HttpClient：零依赖，但公开 API 不提供逐连接 DNS resolver，此次不选。

参考 SSRF allowlist / DNS / redirects：https://cheatsheetseries.owasp.org/cheatsheets/Server_Side_Request_Forgery_Prevention_Cheat_Sheet.html


## 2026-09-09 已批准变更

用户选择服务器统一阿里云百炼 qwen3.8-max，并要求完成后指导服务器填写 Key。实施：只读系统状态页；旧个人写接口405；环境配置与官方域名限制；图片内联调用、截断检测；个人/全局调用次数限制；单元/MockMVC/前端回归；独立 ai.env + systemd drop-in 操作指南。旧表及迁移保留，不删除数据。此次不扩展贷款上传/解析业务，不合并/推送/部署。
