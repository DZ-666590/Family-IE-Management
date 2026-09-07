# 统一家庭账务内核：执行设计

依据用户已确认的设计与执行授权：人工同步完整家庭资产、现金和负债；系统严格保证账内一致；允许先备份再清空业务数据，保留账号和家庭。现有 Stage 2、Java 17 / Spring Boot / MySQL / React 保持不变。

## 边界与不变量

- 不创建新开发分支，不触碰用户 `.idea` 改动，不提交服务器地址、凭据或数据库备份。
- 所有资金变动通过统一复式过账入口；原业务表是业务明细/投影，不再独立决定现金余额。
- CNY 金额使用整数分，借贷合计相等；现金账户正式余额不得为负；贷款本金不得为负。
- 付款检查本金、利息、费用合计；未来收入不提供当前资金。业务日期不晚于上海当天。
- 账户期初、借款、本金偿还、内部转账不是收入/费用。已登记存量资产/贷款通过期初净资产对应；新购买/放款必须指明资金对应账户。
- 同一事务完成分录、资金余额、业务状态更新，失败全部回滚；金额校验使用数据库当前状态，不依赖前端缓存。
- 正式分录不覆盖或删除；更正通过冲销+替代，原业务 ID 可保持，历史可追溯；自动贷款/周期流水的资金字段不能从通用流水接口独立修改。
- 更正/删除收入和期初不得使现金为负；回溯更正需要检查受影响的按日余额，不能用未来收入弥补过去的缺口。
- 幂等键相同但请求内容不同必须拒绝；前端每次新表单生成新键，同一次保存重试沿用原键。
- 财务对象归档仅影响可操作/可见性，不能删除经济余额。非零资金账户/贷款禁止直接归档，资产处置须有明确账务动作。
- 保留贷款计算策略；本轮不新增银行连接、授信透支、税务、多币种、微服务或消息系统。提前还款仍按已明确的本金管理模型处理，不能宣称银行结清。

## 数据与上线策略

业务数据已按授权备份、恢复验证后清理；登录账号、家庭、成员关系、邀请、分类和证券目录保留，资金账户期初归零。备份仅存服务器受限目录，不进入仓库。之后测试写入使用隔离数据库/专用测试身份，不修改用户录入的数据。

新迁移只加结构，不包含自动清库。已存在但没有对应新账的旧数据，禁止猜测回填；初始化状态必须明确，必要时返回 `ACCOUNTING_NOT_INITIALIZED` 阻止正式资金写入。

各任务只在本地提交，全部接入及验证后才推送 Stage 2。不得中途部署混用两套资金规则的版本。

## 架构

新增 `com.familyfinance.accounting`，提供复式凭证、不可变分录、余额投影、来源关联和报表查询。

- `LedgerAccountKind`：CASH、ASSET、LOAN、INCOME、EXPENSE、EQUITY。CASH/ASSET/EXPENSE 借增；其他贷增。
- 账户编码：`CASH:<financialAccountId>`、`LOAN:<loanId>`、`ASSET:<assetId>`、`POSITION:<investmentAccountId>:<securityId>`、`INCOME:<categoryId>`、`EXPENSE:<categoryId>`、`EQUITY:OPENING`。投资收益/估值可使用明确的系统科目，不伪装现金。
- 凭证记录家庭、业务来源类型/ID、请求键、请求摘要、业务日期、录入时间、操作人和冲销关联。
- 凭证分录包含账户、借方分、贷方分、分类/成员维度。所有分录与余额投影同事务写入。来源索引指向当前有效业务版本，历史原单+反向单保留。
- 余额投影必须能从分录重建；余额锁和历史读必须考虑 MySQL REPEATABLE READ，建议 JDBC 锁定当前读，不能用早期快照 SUM 作为扣款依据。

内部最小接口由第一任务实现并保持供后续任务使用：

```java
record LedgerEntryInput(String accountCode, LedgerAccountKind kind,
    long debitCents, long creditCents, Long categoryId, Long memberId) {}
record LedgerPostingCommand(long householdId, String sourceType, long sourceId,
    String idempotencyKey, LocalDate effectiveOn, long actorId,
    List<LedgerEntryInput> entries) {}
// LedgerPostingService
LedgerReceipt post(LedgerPostingCommand command);
LedgerReceipt replace(LedgerPostingCommand command);
LedgerReceipt reverse(long householdId, String sourceType, long sourceId,
    String idempotencyKey, long actorId);
// LedgerReadService
long balance(long householdId, String accountCode);
```

HTTP 不开放任意分录提交，用户只操作受权限和业务校验保护的账户、转账、收支、贷款等命令。具体 API 扩展保持既有字段兼容，新增余额、资金来源、开账方式和审计信息。

## 接入要求

1. 资金账户：期初以 CASH/EQUITY 对应；普通账户不允许负期初。增加账内当前/可用余额，提供期初确认与可审计更正；转账两端同家庭、不同账户、金额正、资金足够。
2. 收支：收入 CASH/INCOME，支出 EXPENSE/CASH；修改、撤销调用账务更正；未来日期拒绝正式过账。周期确认通过相同规则，余额不足保留待确认。
3. 贷款：区分期初存量与实际放款，后者必须选择收款账户。按期偿还借 LOAN+EXPENSE，贷 CASH；提前还本金借 LOAN、贷 CASH。余额不足不写业务记录、不改变计划；合计本金和账务贷款余额一致。
4. 投资：投资账户绑定独立或指定的资金账户，买入必须有资金，卖出按持仓成本结转并记录收益/费用，分红入资金账户；开账持仓与新买入分开，禁止无来源增加可用现金。
5. 资产：区分期初登记和新购买；购买需要资金来源，估值属于非现金变动；归档不能直接抹掉价值，处置必须反映对价或明确损失。
6. 报表：分别呈现现金流、收入费用与净资产，预算明确为费用预算或债务现金计划。本金不混入生活费用；资金、贷款、投资、估值不能重复计数，所有视图可核对到同一业务来源。
7. 用户体验：延续现有风格；资金操作展示付款账户当前/可用余额及预计剩余金额。余额不足提示账户、可用、应付和差额。保留未保存保护、字段错误定位、分页、权限与刷新行为。

## 验收标准

- 现金0还款1100失败，本金/期次/流水/分录均不变；现金恰1100还本金1000+利息100成功，现金0、贷款减1000、费用100。
- 同账户100同时支付两笔80，最多一笔成功；同键同请求不重复，不同请求拒绝。
- 内部转账净资产/收入费用不变，余额随两端变化；其他账户有钱不能为错误付款账户兜底。
- 删除收入、更改期初、回溯编辑不能产生负现金；更正有原单和反向单。
- 买入资金减少与持仓增加对应；卖出/分红资金与收益对应；估值不增加现金。
- 归档不凭空抹掉资金/负债；余额重建与业务子账一致。
- 新旧界面关键流程、真实 MySQL 并发和回滚验证、全量前后端检查、Stage 2 CI/CD 和公共版本核验。
