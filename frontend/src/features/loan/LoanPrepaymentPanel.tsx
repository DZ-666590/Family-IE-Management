import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import Button from '@douyinfe/semi-ui/lib/es/button';
import type { Account, Loan, LoanPrepayment, LoanPrepaymentPreview, PrepaymentStrategy } from '../../api/contracts';
import { ApiError } from '../../api/client';
import { businessDate, newIdempotencyKey } from '../../shared/runtime';
import { AccountOptions, PaymentPreview, useFundsRefresh } from '../accounting';
import { Drawer, FormError, money, type RequestFn } from '../common';
import { LoanStrategyComparison } from './LoanStrategyComparison';

type Body = { amount: string; paidOn: string; paymentAccountId: number; strategy: PrepaymentStrategy; idempotencyKey: string; planToken: string };
type Attempt = { body: Body; preview: LoanPrepaymentPreview };
const rejectedBeforePosting = new Set(['LOAN_PLAN_CHANGED', 'INSUFFICIENT_FUNDS', 'ACCOUNT_ARCHIVED', 'ACCOUNTING_NOT_INITIALIZED', 'ACCOUNTING_BALANCE_MISMATCH', 'LOAN_CLOSED', 'VALIDATION_ERROR', 'ACCOUNT_ACTIVITY_BEFORE_OPENING', 'LOAN_PAYMENT_BEFORE_OPENING', 'LOAN_PAYMENT_CHRONOLOGY', 'STALE_REFERENCE', 'LOAN_OVERDUE_INSTALLMENTS', 'LOAN_FIXED_TERM_INFEASIBLE', 'LOAN_PLAN_INVALID', 'LOAN_PAYOFF_REQUIRED']);

export function LoanPrepaymentPanel({ loan, accounts, request, onClose, onPaid, onPayoff }: { loan: Loan; accounts: Account[]; request: RequestFn; onClose: () => void; onPaid: () => Promise<void>; onPayoff: () => void }) {
 const [draft, setDraft] = useState(() => ({ amount: '', paidOn: businessDate(), paymentAccountId: String(loan.paymentAccountId), strategy: 'REDUCE_PAYMENT' as PrepaymentStrategy, idempotencyKey: newIdempotencyKey() }));
 const [sessionKey] = useState(newIdempotencyKey);const [attempt, setAttempt] = useState<Attempt | null>(null);
 const cache = useQueryClient();const fundsError = useFundsRefresh();
 const params = new URLSearchParams({ amount: draft.amount, paidOn: draft.paidOn, paymentAccountId: draft.paymentAccountId, strategy: draft.strategy });
 const enabled = Boolean(draft.amount.trim() && draft.paidOn && draft.paymentAccountId);
 const preview = useQuery({ queryKey: ['loan-prepayment-preview', loan.id, sessionKey, params.toString()], queryFn: () => request<LoanPrepaymentPreview>(`/api/loans/${loan.id}/prepayment-preview?${params}`), enabled, retry: false, refetchOnWindowFocus: false });
 const submit = useMutation({ mutationFn: (body: Body) => request<LoanPrepayment>(`/api/loans/${loan.id}/prepay`, { method: 'POST', body }), onError: error => {
  fundsError(error);
  if (error instanceof ApiError && error.status >= 400 && error.status < 500 && rejectedBeforePosting.has(error.code ?? '')) setAttempt(null);
  if (error instanceof ApiError && ['LOAN_PLAN_CHANGED', 'INSUFFICIENT_FUNDS', 'ACCOUNT_ARCHIVED', 'ACCOUNTING_NOT_INITIALIZED'].includes(error.code ?? '')) void preview.refetch();
 }, onSuccess: async () => {
  await Promise.all(['loans', 'loan-schedule', 'loan-prepayments', 'accounts', 'transactions'].map(key => cache.invalidateQueries({ queryKey: [key] })));
  await onPaid();
 } });
 const confirm = () => {
  if (attempt) { submit.mutate(attempt.body);return; }
  if (!enabled || !preview.data || preview.isFetching || preview.isError) return;
  const next = { body: { ...draft, paymentAccountId: Number(draft.paymentAccountId), planToken: preview.data.planToken }, preview: preview.data };
  setAttempt(next);submit.mutate(next.body);
 };
 const update = (field: 'amount' | 'paidOn' | 'paymentAccountId' | 'strategy', value: string) => { submit.reset();setDraft(old => ({ ...old, [field]: value, idempotencyKey: newIdempotencyKey() })); };
 const displayed = attempt?.preview ?? (enabled && !preview.isFetching && !preview.isError ? preview.data : undefined);
 const selected = accounts.find(account => String(account.id) === draft.paymentAccountId);
 const account = selected && displayed ? { ...selected, availableBalance: displayed.availableBalance } : selected;
 const error = submit.error ?? preview.error;
 return <Drawer open draft={{ ...draft, attemptedRequest: attempt?.body ?? null }} sessionKey={sessionKey} busy={submit.isPending} title={`${loan.name} · 提前还款`} description={`当前剩余本金 ${money(loan.currentPrincipal)}；选择部分偿还本金后的未来计划。`} onClose={onClose}>
  <form className="feature-form loan-prepayment-form" onSubmit={event => { event.preventDefault();confirm(); }}>
   <FormError error={error} />
   <fieldset className="feature-form" disabled={submit.isPending || attempt !== null}>
    <label>提前还款金额<input required name="amount" inputMode="decimal" value={draft.amount} onChange={event => update('amount', event.target.value)} /></label>
    <label>还款日期<input required name="paidOn" type="date" min={loan.lastPaymentOn ?? loan.accountingOn ?? undefined} max={businessDate()} value={draft.paidOn} onChange={event => update('paidOn', event.target.value)} /></label>
    <label>本次付款账户<select required name="paymentAccountId" value={draft.paymentAccountId} onChange={event => update('paymentAccountId', event.target.value)}><option value="">请选择</option><AccountOptions accounts={accounts} /></select></label>
    <fieldset className="loan-strategy-options"><legend>未来还款方式</legend>
     <label><input type="radio" name="strategy" value="REDUCE_TERM" checked={draft.strategy === 'REDUCE_TERM'} onChange={() => update('strategy', 'REDUCE_TERM')} /><span><strong>缩短还款年限</strong><small>保留原逐期付款上限，余额还清后结束</small></span></label>
     <label><input type="radio" name="strategy" value="REDUCE_PAYMENT" checked={draft.strategy === 'REDUCE_PAYMENT'} onChange={() => update('strategy', 'REDUCE_PAYMENT')} /><span><strong>减少月供</strong><small>保留当前剩余期数和到期日期</small></span></label>
    </fieldset>
   </fieldset>
   {preview.isFetching && <p role="status">正在预览未来还款计划…</p>}
   {displayed && <><LoanStrategyComparison preview={displayed} method={loan.repaymentMethod} /><PaymentPreview account={account} amount={displayed.cashAmount} /></>}
   {attempt && !submit.isPending && <p role="status">上次提交的结果尚未确认。请用原付款信息核对本次提前还款结果；重复核对不会重复记账。</p>}
   <p className="source-note">本次仅偿还本金。到期未付期次须先确认；全部本金及实际利息请使用「一次结清」。计划利息变化为账内测算，实际合同与费用请向贷款方核对。</p>
   <div className="form-footer"><Button disabled={Boolean(attempt) || submit.isPending} onClick={onPayoff}>改为一次结清</Button><Button disabled={!enabled || preview.isFetching || submit.isPending} onClick={() => { submit.reset();void preview.refetch(); }}>重新预览还款计划</Button><Button htmlType="submit" theme="solid" loading={submit.isPending} disabled={submit.isPending || (!attempt && (!enabled || !preview.data || preview.isFetching || preview.isError))}>{attempt ? '核对本次提前还款结果' : '确认提前还款'}</Button></div>
  </form>
 </Drawer>;
}
