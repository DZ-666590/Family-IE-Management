# 多币种投资与汇率表实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** 在不破坏现有人民币账务的前提下，提供港美股投资、汇率历史表、实际换汇与品牌化账户分类。

**Architecture:** 原币账本按币种平衡；参考汇率采用独立不可变快照，人民币报表显式折算；实际换汇使用独立双边资金来源。先交付可独立验证的汇率基础，再放开完整外币业务。

**Tech Stack:** Java 17, Spring Boot, JdbcTemplate, Flyway MySQL/H2, React/TypeScript, TanStack Query, BigDecimal。

**Spec:** `docs/superpowers/specs/2026-09-08-multicurrency-investments-and-accounts.md`（用户已确认）。

## Global Constraints

- 现有 `codex/family-finance-stage-2` linked worktree；不新建分支，保护 `.idea` 用户文件。
- 不修改已发布 V1–V28、不清库；本轮不启动本地业务服务器。
- 金额两位结算；汇率持久化十二位，内部 DECIMAL128，接口十进制字符串。
- 不以参考价当实际成交价，不默认为 1，不混加不同币种。
- 外币记账须在币种账本及全部报表完成后才启用；中间提交不推送部署。
- 每项先写失败测试、验证失败，再实现、验证通过；最后进行交叉模块回归。

## 验证命令

后端使用已有依赖，不启动外部业务服务器：

```sh
JAVA_HOME=/Users/kevinye/Library/Java/JavaVirtualMachines/ms-17.0.18/Contents/Home ./mvnw resources:resources resources:testResources compiler:compile compiler:testCompile -Dtest='ExchangeRate*Test' surefire:test
```

前端：`npm run typecheck && npm test -- --run`（在 frontend）。最终后端选择 `*Test,!StageTwo*SmokeTest`；CI 结果和实际部署另行确认。

## Task 1: 汇率快照、提供者与只读/刷新 API

**Files:** 新建 `src/main/java/com/familyfinance/fx/` 的 `ExchangeRateProvider`、`FrankfurterRateProvider`、`ExchangeRateService`、`ExchangeRateController`；V29 `exchange_rates` H2/MySQL 迁移；`src/test/java/com/familyfinance/fx/ExchangeRateServiceTest.java`、`ExchangeRateApiTest.java`、`FrankfurterRateProviderTest.java`。

**Interfaces:** `ExchangeRateService.table(LocalDate asOf)` 返回固定 CNY/HKD/USD 行，每行包含 currency、cnyPerUnit 字符串或 null、effectiveOn、fetchedAt、source、状态；`history(LocalDate from, LocalDate to)` 最多 90 天；`refresh()` 合并并发且限流。内部 `ExchangeRateProvider.fetch(LocalDate asOf)` 返回同源、同日每单位原币折合 CNY 的批次。历史补抓显式使用日期。

- [ ] 写单元测试验证以下例子，先运行确认缺少实现失败：

```java
assertThat(new BigDecimal("100").multiply(new BigDecimal("7.000000000000")))
    .isEqualByComparingTo("700"); // USD native * CNY-per-USD
// 固定 clock=2026-09-08，9月7日的快照供9月8日使用；9月9日绝不能用于9月8日。
// 缺 HKD 的批次拒绝整批，不允许只写入 USD；同日修订保留两个版本。
```

- [ ] 实现固定 Frankfurter HTTPS 地址、ECB provider、有限超时、响应体大小限制、重定向禁用、日期/币种/正数/重复字段验证；不发送家庭数据。
- [ ] 保存批次及行到 `fx_rate_batches` / `fx_rates`，唯一摘要防重复、事务整批写入。抓取在事务外；查询不联网。失败保留旧值并给出状态；最多 4 个自然日标记新鲜。
- [ ] GET `/api/exchange-rates?asOf=`、GET `/history?from=&to=` 对家庭成员开放；POST `/refresh` 仅管理角色且 CSRF，服务端限流；用现有 ApiEnvelope 和异常格式。
- [ ] 测试实际 H2 持久化、日期选择、历史范围、权限/CSRF、刷新失败、修订与重复批次。更新显式检查最大 Flyway 版本的测试。
- [ ] 测试通过后只提交本任务文件。

## Task 2: 汇率表与品牌化账户视图

**Files:** 新建 `frontend/src/features/investment/ExchangeRatesPanel.tsx` 及测试；修改 `InvestmentsPage.tsx` 页签；新建 `frontend/src/features/ledger/AccountIdentity.tsx` 和 `AccountTypePicker.tsx`、对应样式/测试；修改 `TransactionsPage.tsx` 与账户付款选择组件。

**Interfaces:** 消费 Task 1 的汇率表/history/refresh；账户继续使用已有 `walletProvider`、`bankName`、`cardLastFour`，不猜测历史平台。

- [ ] 测试表格正确展示 `1 USD = 7.000000 CNY`，MISSING 不显示 0，刷新错误不清空旧行情，日期切换不串响应；成员没有刷新按钮。
- [ ] 实现汇率页签、30/90天历史、日期查询和状态反馈，不增加侧边栏入口。
- [ ] 测试支付宝/微信选项保存仍为 WALLET+provider；旧未细分钱包保持原值，键盘选择可用。
- [ ] 实现共享图标身份、分类卡片和付款列表，复用现有主题；品牌 SVG 仅使用许可可核对资源，否则文字徽标。
- [ ] 前端测试、类型检查及构建通过，只提交拥有的文件。

## Task 3: 币种账本与账户约束

**Files:** 修改 `accounting/LedgerEntryInput.java`、`LedgerValidation.java`、`LedgerStore.java`、`LedgerPostingService.java`、`LedgerRequestDigest.java`、`ledger/AccountService.java`、`investment/InvestmentAccountService.java`；新增币种公共类型与后续迁移；新增 `MultiCurrencyLedgerTest.java`。

**Interfaces:** LedgerEntryInput 新增 currency，历史六参数构造默认 CNY；读写、冲销保留 currency；所有科目注册校验币种不变。旧 CNY 摘要兼容，新增外币摘要包含币种。

- [ ] 测试 `1 USD 借 / 1 CNY 贷` 拒绝；同币借贷相等通过；旧 CNY 重放不新增凭证；外币科目不可改币种。
- [ ] 迁移补 CNY、按币种校验平衡，公共损益/权益科目拆分；外币功能开关默认关闭。
- [ ] 现金余额、日序校验、账户初始化保留原币；仅在同币账户间进行普通互转；钱包平台 CNY 校验服务端执行。
- [ ] 贷款/资产/周期规则入口明确拒绝非 CNY；投资账户必须与资金账户、证券币种一致。
- [ ] 回归账本、贷款、资产、账户、幂等与迁移测试后提交。

## Task 4: 换汇资金流水

**Files:** 新建 `accounting/FxTransferService.java`、请求/响应/控制器和迁移；前端在现金账户互转中新增换汇模式；新增 `FxTransferApiTest.java`。

**Interfaces:** POST `/api/fx-transfers` 请求包含转出/转入账户、原币本金、到账金额、转出币种费用、日期；更正/冲销延续现有来源与请求键机制。

- [ ] 测试 CNY本金7000、手续费10、USD到账1000：余额分别-7010/+1000，费用10，换汇本金不算支出。
- [ ] 一个事务内按币种各自平衡并关联同一来源；锁家庭/账户，余额不足全部回滚。
- [ ] 实现双边预览、实际汇率与参考汇率分开；相同请求重放成功，不同载荷拒绝；冲销遇到账款已花掉时整笔拒绝。
- [ ] 测试权限、跨家庭、日期、金额上界、并发重放、更正与冲销，再提交。

## Task 5: 证券身份、港美股交易和历史折算

**Files:** `investment/SecurityService.java`、`Security.java`、`InvestmentTradeService.java`、`InvestmentAccountingService.java`、`PositionCalculator.java`、`market/QuoteRefreshService.java` 及证券/价格迁移；前端 `StockPicker.tsx`、`InvestmentsPage.tsx`、`StockChart.tsx` 与 contracts；新增跨市场交易测试。

**Interfaces:** 持久证券唯一键 `(market, exchange, symbol)`，可信目录解析，不信任客户端传入 currency；港美股复用现有适配器，未复权价格用于估值。

- [ ] 测试同代码跨市场不冲突，HKD证券不能从USD账户扣款；期初不扣现金，卖出不超过持仓。
- [ ] 支持六位单价、四位数量，金额最终两位；记录不可变参考汇率/历史人民币成本，最后清仓结清尾差。
- [ ] 创建投资统一 A/HK/US 搜索，切市场清选项，无同币账户时在流程内引导建立并返回草稿。
- [ ] 测试缺行情可按实际价记账、缺汇率报告标不完整；当前汇率变动不改历史成本；买卖/分红/费用与更正/冲销回归后提交。

## Task 6: 全报表、快照与发布验收

**Files:** `reporting/PortfolioService.java`、`NetWorthService.java`、`DashboardService.java`、`accounting/LedgerReportingService.java`、预算/年度统计/CSV消费者及前端 money 格式化；新增 `MultiCurrencyReportingTest.java`。

**Interfaces:** 原币分组与CNY折算分别返回，携带价格/汇率日期和完整性；收支/预算按历史折算，资产当前值按报告日参考价；缺失时总额为空而非省略外币。

- [ ] 测试 CNY100+USD100*7=800，而非200；缺USD汇率时总额不伪报100；历史报告不使用未来数据。
- [ ] 补齐所有 SUM/金额格式化/快照/CSV消费者，人民币总收益使用历史成本而非今天汇率乘原币收益；保留旧快照版本。
- [ ] 验证完整H2/MySQL迁移、纯CNY回归、前端测试/typecheck/build、后端针对性全套；记录证据。
- [ ] 完整验证后才打开外币功能开关。发布须经过授权、备份和云端FX连通验证；CI 与线上版本分别核对，不回滚旧程序写入多币种数据。

## 当前进度

- [x] 设计确认、代码基线与已有 worktree 核对。
- [x] Task 1 汇率后端实现，H2 持久化、接口权限与提供者解析测试通过；定时任务为显式 opt-in，尚未云端验证。
- [x] Task 2 汇率页签、账户分类卡片和账户/初始化/付款预览图标落地；原生 select 选项仍为文字，图标选择列表及视觉验收未完成。
- [x] Task 3 币种分录基础、按币种平账、冲销和人民币幂等兼容测试通过；只是基础部分，账户服务仍禁止外币创建。
- [ ] Task 3 剩余：外币账户与普通现金业务适配、门控、账户币种约束。
- [ ] Task 4 实际换汇与双边资金流水。
- [ ] Task 5 港美股投资创建、精细单价和历史成本。
- [ ] Task 6 全报表、跨币种汇总、MySQL/云端验证与发布。

### 开发节点证据（非完整交付）

2026-09-08，本批全量后端回归报告 626 项，0 失败/错误，2 跳过；前端 273 项全过、类型检查和构建通过。随后补充历史汇率源回撤修订断言与最早日期边界测试，运行对应针对性测试。所有财务写入仍是原有 CNY 业务，没有放开外币账户/交易。

独立实现任务在写文件前因额度中止，改为主任务实施并自行复核；没有独立审查结论。浏览器拒绝打开本地静态预览文件，没有绕过策略，因此没有视觉通过结论。未推送 GitHub、未改动服务器与数据库。
