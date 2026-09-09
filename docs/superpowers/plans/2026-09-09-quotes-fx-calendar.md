# 盘中参考报价、历史汇率与统一日历 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans with bounded parallel domain tasks. User has authorized implementation; do not pause for further design approval.

**Goal:** 交易时段60秒参考报价、历史汇率自动补齐、全局统一日期/月选择。

**Architecture:** 在既有模块worktree中继续，保留证券搜索修复。盘中价格独立内存缓存，正式历史日线仍单独持久化；FX历史采集和凭证引用补齐分开，网络请求不持有账务事务锁。日历复用既有Semi组件，封装统一DateField，保持ISO字符串、校验和表单错误关联。

**Tech Stack:** Java17/Spring Boot/MySQL/Flyway，Python行情适配器，React19/Semi UI/React Query/BigDecimal。

**Spec:** 本文件设计约束及用户确认的60秒报价方案。

## Execution status — 2026-09-09

下列清单保留最初计划，最终执行状态以本节及验收文档为准：Task 1、2、3 已实现并完成独立复审；日期字段已全部集成。Java 687 项中 685 通过、2 跳过；前端 305 项通过，类型检查和构建成功；Python 行情 53 项及部署安全 25 项通过。未启动本地业务服务，未执行生产 MySQL 迁移或线上浏览器验收。仅交付模块分支，不合并或部署 Stage2。

详见 `docs/acceptance/quotes-fx-calendar-integration.md`，包含公开报价延迟、常规交易日历及历史汇率首次异步响应的边界。

## Global Constraints

- 仅本模块分支，不擅自合并Stage2或部署；不修改其他worktree的IDE或业务改动。
- 金额计算使用BigDecimal；报价不自动发起交易，不改写实际成交价格、成本或已保存历史快照。
- 区分上游报价时间、采集时间、延迟/失败/休市；不得把每60秒请求说成无延迟实时行情。
- 公开HTTP接口固定域名、限制响应大小/耗时，失败保留最后有效值；不向外部提供商发送家庭数据、账户信息或凭据。
- 不改已发布迁移；如FX需新增表使用V38，两套H2/MySQL迁移同步。盘中缓存不新增金融账务表。
- 不启动本地业务服务器；测试用受控数据和单元/组件/MockMVC。云端验证必须区分网络可达和产品上线。
- 日历沿用#4b6bee、#20272e、#67727e、#e5e9ee、#f7f8fa与现有字体，不新增组件依赖。

## Task 1: Historical FX (independent)

Own: Java fx package, fx tests, V38 migrations, ExchangeRatesPanel.tsx/tests. Do not alter other feature pages or application.yml without coordinating.

- [ ] Reproduce: historical view reads stored snapshots only, backfill never fetches missing dates, earlier arbitrary batches can bind incorrectly.
- [ ] Extend fixed Frankfurter provider with bounded date-range retrieval (`from`, `to`, ECB, CNY→USD/HKD), validate complete pairs/dates/positive decimals, atomic per-batch persistence and reusable coverage; unsupported ranges must be visible, not silently partial success.
- [ ] History30/90 requests trigger bounded missing-data acquisition without a long DB transaction; provide progress/retry and retain cache on failure. Startup/daily can seed recent90 days; automatic jobs must respect feature flag and tests must not access real network.
- [ ] Resolve journal reference for the actual requested date via authoritative provider resolution or exact stored date; weekends may use the provider-returned previous publication date. Do not treat any old cached rate as authoritative for a missing weekday. Preserve existing bound references; unresolved ones auto-fetch bounded dates then bind. Record legacy uncertainty rather than rewriting history silently.
- [ ] Red/green tests: Aug1 missing while Sep8 exists; range30/90 and bound limits; weekend resolution; absent currency pair rejected; failed fetch preserves data; concurrency avoids duplicate fetches; native cash and immutable bound references unchanged.
- [ ] Keep Panel date input unchanged; parent integrates DateField after Task2 to prevent shared-file editing.

## Task 2: Global DateField (independent)

Own: shared DateField.tsx/scss/test, all native date/month inputs EXCEPT InvestmentsPage.tsx and ExchangeRatesPanel.tsx. Supply precise integration examples for those two files to parent.

- [ ] Reuse installed Semi DatePicker, evaluate native/Semi/new-calendar briefly; choose existing dependency if it preserves required functionality.
- [ ] DateField accepts current controlled ISO `value`, standard `name/id/aria-label/required/min/max/disabled/readOnly`, date or month modes, and onChange event target.value compatible with existing handlers.
- [ ] Render styled Chinese popup anchored inside drawer, consistent40px trigger, 8–12px radius, blue selection, clear focus state, mobile containment. Escape first closes calendar, then enclosing drawer; calendar navigation cannot submit parent form.
- [ ] Preserve date-only values across timezones, valid leap days, min/max, empty optional values, fieldset busy/disabled behavior, keyboard and manual entry; do not silently change transaction dates.
- [ ] Replace owned date/month inputs with DateField using apply_patch. Tests cover day/month output, bounds, disabled/readOnly, explicit clear, Escape/focus and form names.

## Task 3: Intraday Quotes and daily cutoff (parent)

Own: market Python adapter/server/tests, Java market/reporting and their tests, InvestmentsPage.tsx, quote-experience helpers/contracts (coordinate date integration only after workers finish).

- [ ] Verify public spot candidates and cache/timestamp semantics; prefer per-security endpoints and shared60sec server cache over repeatedly downloading every market. Confirm unavailable/delayed markets honestly.
- [ ] Add validated quote endpoint to loopback adapter: `{symbol,market,currency,price,quotedAt,fetchedAt,source,delayMinutes,status}`; validate instrument identity, numeric price/time, bounded requests, failure fallback; no user-provided upstream URLs.
- [ ] Java authenticated endpoint resolves IDs server-side, returns live reference quotes/portfolio projection without persisting them as daily closes; multiple households share quote cache, never credentials or balances.
- [ ] Visible investment page polls60sec in trading windows; hidden pages stop, inactive markets stop repeated fetches; manual refresh respects shared throttle. Display source/time/delay and retain daily fallback when live unavailable. Values used for estimates only, historical endpoints remain daily.
- [ ] Replace blanket exclude-today daily cutoff with after-close safety windows per market/timezone (including US DST); do not invent bars on holidays or force incomplete current session bars into history. Cache must not hide new after-close data for6h.
- [ ] Tests: quote identities/invalid timestamps, cache singleflight/failure, closing-time boundaries/weekends/DST, live affects estimated market value not recorded cost/history, foreground/background polling behavior.

## Integration / Review

- [ ] Parent alone edits the two excluded date fields after worker reports. No concurrent Maven compiler runs in shared target; FX worker runs first, parent waits before Java tests.
- [ ] Verify full frontend typecheck/tests/build, Python adapter/release tests, targeted FX/market/reporting Java tests followed by regression suite.
- [ ] Independent read-only review per domain plus integration. Fix substantive findings, document provider limitations and verification boundaries.
- [ ] Commit only owned paths; push module branch for handoff without deploying shared Stage2.
