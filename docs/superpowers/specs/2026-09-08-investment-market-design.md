# 投资持仓：真实证券、行情与初始化

用户已批准把云端验证的数据源接入投资持仓，并取消自行登记股票。范围为手工同步投资账目，不提供真实下单。

## 用户流程

- 首次进入显示初始化引导：创建/选择投资账户并关联已初始化现金账户；选择录入已有持仓（OPENING，不扣现金）或确认暂时没有持仓。完成状态按家庭持久化，清仓、刷新、换设备不会重新要求初始化。已有投资业务视为已使用，保留历史。
- 股票由系统同步真实目录，按代码/名称搜索选择；用户无需输入名称或交易所。移除登记入口，旧 resolve 接口不得再创建用户自定义股票。历史自定义证券保留历史和清仓能力，不伪装成真实行情。
- 持仓与行情页可打开 K 线，也可搜索未持有的股票看行情。KLineChart 展示日/周/月、成交量、MA、MACD、缩放与十字线。未复权用于估值，前复权仅用于图表；明确来源、数据日期、过期或覆盖不足。

## 数据与边界

- Java/Spring Boot、MySQL 仍是业务主系统。新增只监听 127.0.0.1:8091 的只读 Python 行情适配器，固定 AKShare 1.18.88、BaoStock 0.9.3。不接收家庭、交易、资金账户数据。
- AKShare 目录约 5558 条已云端探测；目录按交易所接口得到市场身份，不凭用户输入猜测。目录完整验证后才发布缓存；失败保留上一版。
- BaoStock 负责沪深日线，成交量统一股、成交额元。北交所暂未验证成功，明确不支持 K 线；不自动拼接腾讯或东方财富不同口径数据。
- 每证券/复权方式缓存行情，有限超时、请求范围、并发与缓存容量；失败可返回标明 stale 的旧数据，无缓存则返回可理解错误。BaoStock 会话串行，网络调用不得无限挂起。禁止通用任意 URL/API 代理。
- 日线仅包含已结束交易日；周/月由前端对同一份日线聚合，标明未结束周期。行情数据不是现金账本真值，不修改成交成本。
- Java 同步真实证券引用并保留原 ID；无效/不完整目录不得覆盖已有引用。首次目录未准备好时用户得到状态与重试入口，而非登记股票提示。
- 初始化状态、目录标识采用增量 Flyway 迁移；不清数据、不改用户 .idea 文件、不新开分支。

## 接口契约

现有 `/api/securities/search` 返回 Page<Security>（id,market,tsCode,name,securityType,active）。

`GET /api/securities/catalog-status` 返回 `{state,count,updatedAt,error}`。

`GET /api/securities/{id}/candles?adjust=none|qfq` 返回 `{symbol,source,adjustment,asOf,fetchedAt,stale,supported,bars}`；bars 为 `{timestamp,open,high,low,close,volume,turnover}`，timestamp 为上海交易日 00:00 的 epoch 毫秒，turnover 为成交金额（元）。最多最近约 2 年日线。不支持返回 supported=false、空 bars、明确状态。

`GET /api/investment-setup` 返回 `{completed,hasAccounts,hasTrades}`；`POST /api/investment-setup/complete` 仅管理员可用，要求有效资金关联的投资账户，幂等完成。两者由通用 ApiEnvelope 包装。

## 交付约束

沿用 codex/family-finance-stage-2；只做相关单元/接口/前端检查，不恢复 Windows/Unix 本地启动 CI，不启动本地项目。云端部署准备可安装隔离行情适配器，但不得改数据库业务数据。推送后由 GitHub Actions 自动部署，不等待其完成。本次先完成代码与验证，推送按用户授权范围执行。

AKShare 声明面向学术研究，当前课程项目使用；商用及再分发需另行审查数据授权。一次云端探测不等于稳定性保证。
