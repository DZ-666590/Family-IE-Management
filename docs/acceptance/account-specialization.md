# 资金账户细分

## 设计与兼容边界

保留 AccountType CASH / BANK / WALLET 和账户 ID，不增加新账务账户种类。
采用现有账户资料扩展，不引入支付 SDK 或外部服务：本模块是手工账本，
接入支付平台既不必要也不属于授权范围。

- CASH：现金，无专属字段。
- BANK：可选 bankName（最多 80 字符）与 cardLastFour（四位 ASCII 数字）。
  不收集完整卡号、密码、CVV。
- WALLET：可选 walletProvider，ALIPAY / WECHAT / OTHER。
  老账户 null 显示“电子钱包（未细分）”，不按名称推断。
  新界面选择钱包时默认“其他电子钱包”，可显式选择支付宝余额或微信余额。

创建与响应包含新增字段。PATCH 中 null/省略保留原资料；银行字段空字符串清除。
钱包可选择 OTHER；旧客户端不传平台时保留已有平台。
切换大类会自动清除不适用的旧字段；显式提交不适用于目标大类的非空字段返回 400。
编辑账户资料与确认/更正期初余额继续分开提交，不改变余额或初始化状态。

资金选择器、付款预览、账户筛选和卡片使用统一分类文案。
未初始化/归档账户仍不可用。账户资料维护仍仅限家庭 OWNER/ADMIN；
收支、贷款、投资仍通过原账户 ID 和现有服务关联，不重建账户或流水。

## 迁移协调

本分支暂用 V26__account_specialization.sql，H2 与 MySQL 两份。
**合入 stage2 前必须与其他模块统一编号及执行顺序**，此编号不是发布承诺。
迁移只新增三个可空字段和字段类型约束；不更新历史行的金融含义，
不修改余额、初始化确认、外键或历史流水。
不得在已执行环境中直接重命名迁移；先核查目标 Flyway 历史再决定合入方案。
本任务不连接生产库，不推送、部署或运行本地业务应用。

## 验证范围

- AccountApiTest：旧钱包不自动分类；钱包创建、类型切换、资料保留/清除；
  ID/期初确认/余额保留；完整卡号和错误字段组合拒绝；普通成员不可改。
- CashAccountingApiTest：改类型不能替代初始化，已有支出及余额不丢失，
  改为微信余额后余额不足仍拒绝。
- AccountSpecialization.test.tsx：列表/资金选项分类、未初始化禁用、
  编辑银行转钱包仅发送资料且清除银行字段。
- 使用独立 H2/MockMVC 和前端测试。MySQL 实机迁移、浏览器视觉验收及部署后验证
  留待统一集成阶段执行。

## 本分支实测结果（2026-09-08）

- 前端：43 个测试文件、262 项测试通过；TypeScript 检查与生产构建通过。
  构建保留现有大体积 chunk 提示。
- 后端：AccountApiTest 10、AccountAuthorizationConcurrencyApiTest 2、
  CashAccountingApiTest 22、CashOpeningConcurrencyTest 6、
  TransactionAccountApiTest 5、LoanAccountingApiTest 19、WealthAccountingApiTest 23，
  共 87 项通过，无失败、错误或跳过。
- 已观察新增接口/前端测试先失败，再在实现后通过。
- 未运行本地业务应用、Windows/Unix 启动认证、生产数据库操作或部署。
- 当前会话没有可调用的 Mind MCP 工具，交接信息保存在本说明。
