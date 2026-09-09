# 历史汇率自动补齐验收记录

范围：`2026-09-09-quotes-fx-calendar.md` 的 Task 1。仅模块 worktree；没有启动业务服务器、读取凭据、修改真实家庭账务、提交、推送或部署。

## 行为与约束

- `/api/exchange-rates/history` 现在返回 `{from,to,rows,state,retryAfter,detail}`。认证仍按家庭成员检查。支持 1–90 个自然日；面板提供最近 30/90 天。
- 查询先返回缓存及 `UPDATING`，单后台线程获取缺失范围。每个实例同时仅一个历史/凭证补齐任务，无积压任务队列；已有范围的并发查询共享该任务。失败保留缓存，60 秒后可重试。面板仅在更新中轮询，后台标签页不轮询，并提供失败说明及重试按钮。
- Frankfurter 固定为 `https://api.frankfurter.dev/v2/rates`，使用 `base=CNY&quotes=USD,HKD&providers=ECB&from=...&to=...`。汇率始终转换为 CNY per unit。请求无家庭、账户、凭据字段。
- 每个请求最多 32 KiB 响应、20 秒总截止时间；禁用重定向、自动重试、Cookie、压缩。范围最多 180 行，每个发布日必须同时含 USD/HKD、合法正数、相同日期及 CNY base，拒绝重复货币与越界日期。
- 范围首尾缺日时，通过对应 `date=` 请求确认实际发布日，避免把来源尚未覆盖的短区间当作周末。每次范围采集最多 3 个有界 HTTP 请求，均在数据库事务之外。返回数据的连续缺口超过 7 天时保守报告获取失败/范围不受支持，不登记覆盖成功。
- 全部响应验证后，以一个短数据库事务持久化该范围的完整币种对和覆盖记录；异常回滚整个范围。已有不可变快照保留，修正值仍产生新 revision。
- `fx_history_coverage` 持久化查询覆盖并可复用包含范围；较早历史范围可跨重启复用，最近两天涉及的范围一小时后可重新获取，以免冻结当天尚未发布的数据。
- `fx_date_resolutions` 只保存提供商明确返回的“请求日期 → 发布日”映射。凭证引用只接受精确日期快照或该映射；不会把任意更早的缓存工作日自动冻结到缺失日期。
- 对没有凭证的历史报表日期，`ExchangeRateHistory.requestValuationDate(LocalDate)` 可独立异步获取权威日期映射。即使发布日范围已有周五/周一记录，周日仍必须由 `date=周日` 明确解析；不会猜测前一个缓存日期。该方法只写汇率批次和日期映射，不绑定/修改凭证。返回 `READY` / `UPDATING` / `FAILED` / `BUSY` / `UNAVAILABLE`；其他任务占用工作线程时不排队，`BUSY.retryAfter` 为 5 秒后，失败冷却为 60 秒。报表控制器的启用开关和界面重试由父任务接入。
- 当前估值单独使用 `valuationReference` / `valuationConvert`，可即时回退到不晚于估值日的最新发布汇率，并保留来源、实际发布日及 `ESTIMATE` / `STALE` 状态。该 API 返回独立的 `ValuationRate` 类型；`reference` / `bind` / `historical` 仍严格，用于账务引用和历史记录，不能使用估值回退重写成本。
- 原有已绑定引用及冲正引用数值保持不变。V38 将迁移前引用标记为 `LEGACY_UNVERIFIED`，不悄悄重算；新解析引用标记为 `RESOLVED`，冲正继承原引用的标记。
- `/api/exchange-rates/audit` 返回当前认证家庭的 `{unverifiedReferences}`。只计数该家庭的 `LEGACY_UNVERIFIED` 已绑定引用，忽略客户端提供的家庭 ID；面板对成员显示待核对数量，并明确原记录保留、不会自动重写。
- 定时任务受既有 `app.fx.scheduled` 开关控制；启动/每日补近期 90 天。每轮最多自动解析 8 个实际缺失凭证日期，失败日期冷却一小时。游标轮转避免早期失败日期阻塞后续日期；失败有不包含家庭标识的日志。网络完成后才开始凭证绑定事务。
- 面板日期输入未在本任务中修改，交由父任务统一接入 DateField。

## 关键文件

- `src/main/java/com/familyfinance/fx/ExchangeRateHistory.java`：异步采集、限流、覆盖复用、后台凭证日期补齐。
- `ExchangeRateStore.java`：原子范围落库、权威请求日期映射。
- `FrankfurterRateProvider.java`：固定来源范围接口、完整币种对和边界校验。
- `FxJournalRates.java`：精确/权威引用、不可变绑定、遗留不确定性继承。
- `ExchangeRateService.java`、`ExchangeRateController.java`、`ExchangeRateScheduler.java`：接口与调度接入。
- H2/MySQL `V38__fx_history_resolution.sql`：两套等价新增迁移；未修改已发布迁移。
- `frontend/src/features/investment/ExchangeRatesPanel.tsx`：进度、缓存展示、错误重试；READY 后刷新参考汇率表。

## 验证记录

已观察的 red/green：

1. 缺失的 8 月日期错误使用已缓存的 7 月 1 日：先复现失败，再收紧引用查询。
2. 范围采集初始不存在、并发无共享、周末无权威映射：先复现失败，再实现异步获取及映射。
3. 新历史响应结构使旧面板失败；接入结构、缓存展示及重试后，面板 5 个组件测试通过。
4. READY 状态读取与新快照提交竞态、冲正丢失 `LEGACY_UNVERIFIED`：先复现失败，再修复。
5. 只有两天的缺失范围起点可能实际不受支持：先复现被错误接受，再增加提供商日期边界确认。
6. 当前周末估值缺少安全回退：联合测试先复现失败，再单独实现估值专用 API；同时断言严格凭证引用仍为空，且估值不使用未来汇率。
7. 遗留引用审计接口缺失时先观察 404、面板缺少提示时先观察组件失败，再实现家庭隔离计数和提示。
8. 发布日范围已就绪但没有周日凭证时，历史报表缺少权威日期映射：先观察两个新增测试失败，再实现独立报表日期采集。定向 `ExchangeRateHistoryTest` 10/10 通过，涵盖无账务写入、同日请求去重、繁忙状态、失败冷却和未来日期拒绝。

父任务联合回归中的 25 个 FX 测试全部通过；新增审计后，定向运行 `ExchangeRateApiTest` 的 3 个测试全部通过；独立报表日期采集后，`ExchangeRateHistoryTest` 的 10 个测试全部通过。当前 5 份 FX 测试报告合计 28 个用例、0 失败、0 错误。覆盖范围 30/90 天、91 天拒绝、范围数据原子回滚、缺失币种对/非法响应、失败缓存保留、并发去重、持久覆盖复用、READY 返回完整数据、周末权威解析、8 日期上限、原币分录和余额不变、历史/冲正不可变及遗留标记保留、当前估值回退与凭证权威引用隔离、独立报表日期解析，以及审计家庭隔离/认证/只读性。面板新增审计提示后 6 个组件测试全部通过。父任务继续负责全项目最终回归。

## 证据边界与后续

- 上游参数依据 [Frankfurter 官方 v2 文档](https://frankfurter.dev/) 的 Historical Rates / Time Series / Filtering by Provider。未以真实在线数据或产品云端验证替代测试；工具无法读取货币详情接口，本次没有确认实时数据可用性。
- 测试使用受控响应/H2/MockMVC/组件环境，未启动本地业务服务；MySQL V38 仅做与 H2 的 SQL 等价审阅，尚未在真实 MySQL 执行。
- 暂未对多应用实例提供分布式 HTTP 去重；数据库写入已有 `fx_sync_state` 事务锁保障 revision/覆盖写入一致。同实例并发请求共享后台任务。
- 超过 7 天的来源空洞会保守失败；不伪造节假日记录，不把来源失败视为完整历史。
- 遗留引用的不确定性通过数据库字段和汇率页的本家庭待核对数量展示；本次不添加全站逐凭证审核界面，也不改写既有历史账务。
