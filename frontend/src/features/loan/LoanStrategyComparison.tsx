import type { Loan, LoanPrepaymentPreview } from '../../api/contracts';
import { dateText, money } from '../common';

export function LoanStrategyComparison({ preview, method }: { preview: LoanPrepaymentPreview; method: Loan['repaymentMethod'] }) {
 const { before, after } = preview;
 return <section className="loan-strategy-comparison">
  <table aria-label="提前还款前后对比"><thead><tr><th scope="col">未来计划</th><th scope="col">调整前</th><th scope="col">调整后</th></tr></thead><tbody>
   <tr><th scope="row">待还本金</th><td>{money(before.principalAmount)}</td><td>{money(after.principalAmount)}</td></tr>
   <tr><th scope="row">下期付款</th><td>{money(before.nextPaymentAmount)}</td><td>{money(after.nextPaymentAmount)}</td></tr>
   <tr><th scope="row">剩余期数</th><td>{before.periodCount} 期</td><td>{after.periodCount} 期</td></tr>
   <tr><th scope="row">计划到期日</th><td>{dateText(before.maturityOn)}</td><td>{dateText(after.maturityOn)}</td></tr>
   <tr><th scope="row">剩余计划利息</th><td>{money(before.totalInterest)}</td><td>{money(after.totalInterest)}</td></tr>
   <tr><th scope="row">剩余计划本息</th><td>{money(before.repaymentTotal)}</td><td>{money(after.repaymentTotal)}</td></tr>
  </tbody></table>
  {preview.strategy === 'REDUCE_TERM' && before.periodCount === after.periodCount && <p className="source-note">本次金额尚不足以减少整期，计划到期日不变，末期付款减少；逐期付款不超过原计划上限。</p>}
  {method !== 'EQUAL_PAYMENT' && <p className="source-note">原计划各期付款可能不同。缩期按原逐期金额作为上限；上方显示的是下期金额，并非固定月供。</p>}
  {method === 'CUSTOM' && <p className="source-note">自定义计划保留原日期；固定期限按原本金比例分配，并按每期原利息与期初本金比例测算利息。</p>}
  <details><summary>查看每期金额和日期</summary><div className="loan-plan-comparison-scroll"><table aria-label="逐期还款金额对比"><thead><tr><th scope="col">日期</th><th scope="col">原本息</th><th scope="col">新本金</th><th scope="col">新利息</th><th scope="col">新本息</th></tr></thead><tbody>{before.schedule.map((row, index) => { const next = after.schedule[index];return <tr key={row.dueOn}><th scope="row">{dateText(row.dueOn)}</th><td>{money(row.paymentAmount)}</td><td>{next ? money(next.principal) : '已缩减'}</td><td>{next ? money(next.interest) : '—'}</td><td>{next ? money(next.paymentAmount) : '—'}</td></tr>; })}</tbody></table></div></details>
 </section>;
}
