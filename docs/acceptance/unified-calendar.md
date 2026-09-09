# 全局统一日期 / 月份选择验收记录

范围：`2026-09-09-quotes-fx-calendar.md` 的 Task 2。仅修改共享 `DateField`、其组件测试及非投资/汇率页面的原生日期输入；没有启动业务服务器、安装或变更依赖、提交、推送、合并或部署。

## 方案选择

| 方案 | 结论 | 原因 |
| --- | --- | --- |
| 继续使用浏览器原生 `date` / `month` | 不采用 | 不同浏览器的日历、中文文本、清除和键盘行为不一致，也无法稳定保证抽屉内定位及 Escape 层级。 |
| 复用已安装的 Semi `DatePicker` 2.103.0 | 采用 | 项目已依赖 Semi；默认中文本地化，提供日期/月模式、键盘输入、禁用日期、清除和可定制弹层容器，不增加包体依赖。 |
| 新增日历库或自研日历 | 不采用 | 会新增依赖或重复实现闰年、月份导航、无障碍与定位逻辑，收益不足。 |

视觉沿用现有家账产品的蓝 / 石墨 / 浅灰体系：`#4b6bee`（并兼容现有 `--ledger-primary`）、`#20272e`、`#67727e`、`#e5e9ee`、`#f7f8fa`。触发器高 40px、8px 圆角；弹层 12px 圆角并使用克制阴影。选中项保持蓝色，键盘焦点有清晰蓝色焦点环，移动端宽度限制在视口内。未引入新的字体或视觉体系。

## 共享组件行为

- `DateField` 接收受控 ISO 日期 `YYYY-MM-DD` 或月份 `YYYY-MM`；`undefined` 被规范为空字符串，不会经 UTC / `toISOString()` 往返。选择输出由本地年、月、日字段组装，避免时区偏移。
- `mode="date"` 为默认；`mode="month"` 输出月份字符串。有效闰日可选、可手工输入。
- 保留现有处理器所需的 `onChange(event)` 契约；选择或清除时 `event.target.value`、`target.name`、`target.id` 来自真实可聚焦输入。
- 将 `name`、`id`、`aria-label`、`aria-labelledby`、`aria-describedby`、`aria-invalid`、`required`、`min`、`max`、`disabled`、`readOnly` 直接放到可聚焦输入，继续兼容 `FormData`、原生必填校验及 `FormError` 的按名称聚焦/错误关联。
- 手工输入会校验格式、实际日历日期、闰日和 `min` / `max`；越界或不存在的日期不会通知业务状态，并设置原生自定义校验错误。空的非必填字段保持有效。
- 无效手工文本会显示与输入关联的错误；离开字段时恢复调用方最后确认的受控值，避免筛选条件与画面文字不一致。必须保持非空的筛选器使用 `allowClear={false}`，不显示会被调用方拒绝的清除操作。
- Semi 日历用同一 `min` / `max` 规则禁用不可选日期。所有日历导航按钮不提交外围表单。
- 输入获得焦点后可用 ArrowDown 打开日历并将焦点移至已选日期（或首个可选日期）；日历关闭时 Enter 保留正常表单提交，只有日历正在编辑时才阻止提交。
- 显式清除控件使用可聚焦的 `role="button"`，支持 Enter/空格，避免在包裹标签中加入第二个原生表单控件；清除后焦点回到输入。只读字段不可编辑或打开日历；显式禁用和祖先 `fieldset[disabled]` 均会禁用触发器。祖先 `fieldset` 动态切换时由属性观察同步。
- 抽屉内弹层挂载到最近的 `.side-sheet`，非抽屉场景挂载到 `document.body`。日历打开时第一次 Escape 只关闭日历、阻止抽屉捕获并把焦点还给触发器；第二次 Escape 才交给抽屉关闭。

## 已替换范围

已将 11 个 owned 文件中的 21 个原生日期 / 月份输入替换为 `DateField`：

- 资产：`AssetsPage.tsx`（4）
- 预算：`BudgetsPage.tsx`（2）
- 总览：`DashboardPage.tsx`（1）
- 账本：`FundingAccountCreator.tsx`（1）、`FxTransfersPanel.tsx`（1）、`TransactionsPage.tsx`（3）、`accounting-flows.tsx`（1）
- 贷款：`LoanPayoffPanel.tsx`（1）、`LoanPrepaymentPanel.tsx`（1）、`LoansPage.tsx`（4）
- 周期账单：`RecurringPage.tsx`（2）

`InvestmentsPage.tsx` 与 `ExchangeRatesPanel.tsx` 的 3 个日期输入已由集成任务完成替换；生产代码没有残留原生 date/month 输入。

## 父任务精确集成

两个文件都添加：

```tsx
import { DateField } from '../../shared/DateField';
```

`InvestmentsPage.tsx` 两处替换（保留原属性和处理器，仅将元素改为 `DateField` 并移除 `type="date"`）：

```tsx
<DateField name="tradedOn" required max={businessDate()} value={trade.tradedOn}
  onChange={e => setTrade({ ...trade, tradedOn: e.target.value })} />

<DateField name="effectiveOn" required value={manual.effectiveOn}
  onChange={e => setManual({ ...manual, effectiveOn: e.target.value })} />
```

另检查父任务行情改动后新增的全部 `type="date"`；当前文件基线除上述两处没有其他日期字段。

`ExchangeRatesPanel.tsx` 一处替换：

```tsx
<DateField min="1999-01-01" max={businessDate()} allowClear={false} value={asOf}
  onChange={e => { setAsOf(e.target.value); refresh.reset(); }} />
```

集成后执行：

```text
rg 'type="(date|month)"' frontend/src --glob '*.tsx' --glob '!**/*.test.tsx'
```

期望无生产代码结果。

## 验证记录

- `DateField.test.tsx`：10 个组件测试覆盖日/月输出、真实 Semi 闰日选择、抽屉内挂载、格式和上下界、可见错误、显式清除、名称/id/错误关联、disabled/readOnly/祖先 fieldset、键盘打开/焦点、正常 Enter 提交、两阶段 Escape/焦点恢复、导航不提交表单。
- 核心日期组件此前分别在 `Pacific/Kiritimati`（UTC+14）与 `America/Los_Angeles` 时区通过，ISO 日/月没有偏移。
- 最新目标组件与受影响页面联合 Vitest：10 个测试文件、62 个测试通过。
- TypeScript 只读检查：`tsc --noEmit` 通过。
- `git diff --check` 通过。

集成任务最终统一执行：52 个文件、305 项前端测试全部通过，类型检查及生产构建通过。日历使用受支持的 `motion={false}` 立即开关；退出不依赖动画结束事件，并补充了 blur 回退及不可清空筛选的直接回归。
