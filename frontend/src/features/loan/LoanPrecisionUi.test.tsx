import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { LoanPrepaymentPanel } from './LoanPrepaymentPanel';
import { ApiError } from '../../api/client';
import type { Account, Loan } from '../../api/contracts';
import type { RequestFn } from '../common';

const loan = { id: 4, name: '分币贷款', paymentAccountId: 1, currentPrincipal: '3.60', accountingOn: '2026-01-01', repaymentMethod: 'CUSTOM' } as Loan;
const accounts = [{ id: 1, name: '零钱', openingConfirmed: true, availableBalance: '1.00' }] as Account[];
const initialPolicy = { minimumInstallmentAmount: null, sourceNote: null, revision: 0 };
function fixture(path: string, policy = initialPolicy as { minimumInstallmentAmount: string | null; sourceNote: string | null; revision: number }) {
 const p = new URLSearchParams(path.split('?')[1]);
 const rows = ['0.02', '0.03', '0.04', '0.05'].map((paymentAmount, i) => ({ installmentNo: i + 1, dueOn: `2027-0${i + 1}-01`, principal: '0.01', interest: ['0.01', '0.02', '0.03', '0.04'][i], paymentAmount, remainingPrincipal: '0.01', precisePrincipalAmount: '0.008333333333', preciseInterestAmount: '0.036000000000', interestCarryAmount: '-0.004000000000', roundingPolicy: 'CUMULATIVE_CENTS_V1', principalRoundingAmount: '0.001666666667' }));
 const summary = { principalAmount: '3.00', periodCount: 4, maturityOn: '2027-04-01', nextPaymentOn: '2027-01-01', nextPaymentAmount: '0.02', totalInterest: '0.10', repaymentTotal: '3.10', schedule: rows };
 const options = [{ periods: 1, allowed: true, reason: null, firstPaymentAmount: '3.01', roundingPolicy: 'CUSTOM_REALLOCATION_V1' }, { periods: 2, allowed: false, reason: 'BELOW_CONTRACT_MINIMUM', firstPaymentAmount: null, roundingPolicy: null }, { periods: 4, allowed: true, reason: null, firstPaymentAmount: '0.02', roundingPolicy: 'CUMULATIVE_CENTS_V1' }];
 if (path.endsWith('/repayment-policy')) return policy;
 if (path.includes('/term-options?')) return { remainingPrincipal: '3.60', duePrincipal: '0.00', dueInterest: '0.04', policy, options };
 return { dueInstallments: [{ installmentId: 1, installmentNo: 1, dueOn: '2026-01-01', principalAmount: '0.00', interestAmount: '0.04', cashAmount: '0.04' }], duePrincipalAmount: '0.00', dueInterestAmount: '0.04', additionalPrincipal: '0.60', totalPrincipalAmount: '0.60', totalInterestAmount: '0.04', totalCashAmount: '0.64', paymentAccountId: 1, availableBalance: '1.00', balanceAfter: '0.36', paidOn: p.get('paidOn'), strategy: p.get('strategy') ?? 'REDUCE_PAYMENT', targetPeriods: p.has('targetPeriods') ? Number(p.get('targetPeriods')) : null, before: summary, after: summary, termOptions: options, policy, planToken: `policy-${policy.revision}` };
}
function mount(request: RequestFn) {
 render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><LoanPrepaymentPanel loan={loan} accounts={accounts} request={request} onClose={vi.fn()} onPaid={vi.fn()} onPayoff={vi.fn()} /></QueryClientProvider>);
 return userEvent.setup();
}
it('shows settled cent components and actual unequal tiny payments, with rounding explanation and explicit feasible period choices', async () => {
 const request: RequestFn = async <T,>(path: string) => fixture(path) as T;
 const user = mount(request); fireEvent.change(screen.getByLabelText('额外提前偿还本金'), { target: { value: '0.60' } });
 expect(await screen.findByRole('button', { name: '确认还款 ¥0.64' })).toBeEnabled();
 const bill = screen.getByRole('region', { name: '本次还款明细' });
 expect(bill).toHaveTextContent('本金合计¥0.60'); expect(bill).toHaveTextContent('利息合计¥0.04'); expect(bill).toHaveTextContent('预计付款后余额¥0.36');
 await user.click(screen.getByText('查看每期金额和日期'));
 const table = screen.getByRole('table', { name: '逐期还款金额对比' });
 for (const amount of ['¥0.02', '¥0.03', '¥0.04', '¥0.05']) expect(table).toHaveTextContent(amount);
 expect(screen.getByText(/分币尾差已分配/)).toBeInTheDocument(); expect(screen.queryByText(/0\.036000000000/)).not.toBeInTheDocument();
 expect(screen.getByText(/仅计算可行性/)).toBeInTheDocument();
 await user.click(screen.getByRole('radio', { name: /自选更短期数/ }));
 const choices = await screen.findByLabelText('后续还款期数');
 expect(within(choices).getByRole('option', { name: /2 期/ })).toBeDisabled(); expect(within(choices).getByRole('option', { name: /1 期/ })).toBeEnabled();
 expect(within(choices).queryByRole('option', { name: /4 期/ })).not.toBeInTheDocument();
 expect(screen.getByText(/可能提高部分期次付款/)).toBeInTheDocument();
});

it('replaces all policy fields, preserves conflicting edits, then uses a new policy-bound preview for repayment', async () => {
 let current = initialPolicy as { minimumInstallmentAmount: string | null; sourceNote: string | null; revision: number }; const writes: Record<string, unknown>[] = []; const payments: Record<string, unknown>[] = [];
 const request: RequestFn = async <T,>(path: string, options?: Parameters<RequestFn>[1]) => {
  if (options?.method === 'PATCH') { const body = options.body as typeof current; writes.push(body); if (writes.length === 1) { current = { minimumInstallmentAmount: '0.05', sourceNote: '其他管理员', revision: 1 }; throw new ApiError('合同规则已变更', { status: 409, code: 'LOAN_POLICY_CHANGED' }); } current = { ...body, revision: 2 }; return current as T; }
  if (options?.method === 'POST') { payments.push(options.body as Record<string, unknown>); return {} as T; }
  return fixture(path, current) as T;
 };
 const user = mount(request); fireEvent.change(screen.getByLabelText('额外提前偿还本金'), { target: { value: '0.60' } });
 await screen.findByRole('button', { name: '确认还款 ¥0.64' }); await user.click(await screen.findByRole('button', { name: '编辑合同规则' }));
 await user.type(screen.getByLabelText('最低常规每期还款额（可选）'), '0.10'); await user.type(screen.getByLabelText('合同依据（可选）'), '合同第 8 条');
 expect(screen.getByRole('button', { name: '确认还款' })).toBeDisabled();
 await user.click(screen.getByRole('button', { name: '保存合同规则' })); await screen.findByText('合同规则已变更');
 expect(screen.getByLabelText('最低常规每期还款额（可选）')).toHaveValue('0.10'); expect(screen.getByLabelText('合同依据（可选）')).toHaveValue('合同第 8 条');
 await user.click(screen.getByRole('button', { name: '读取最新规则并保留输入' })); await screen.findByText(/已载入最新版本/);
 await user.click(screen.getByRole('button', { name: '保存合同规则' }));
 await user.click(await screen.findByRole('button', { name: '确认还款 ¥0.64' })); await waitFor(() => expect(payments).toHaveLength(1));
 expect(writes).toEqual([{ minimumInstallmentAmount: '0.10', sourceNote: '合同第 8 条', revision: 0 }, { minimumInstallmentAmount: '0.10', sourceNote: '合同第 8 条', revision: 1 }]);
 expect(payments[0].planToken).toBe('policy-2'); expect(screen.getByText(/末期结清金额可低于/)).toBeInTheDocument();
});

it('offers a payoff route when no positive cash schedule is feasible without silently switching strategy', async () => {
 const request: RequestFn = async <T,>(path: string) => { if (path.includes('/term-options?')) return { remainingPrincipal: '0.01', duePrincipal: '0.00', dueInterest: '0.00', policy: initialPolicy, options: [] } as T; if (path.includes('/repayment-preview?')) throw new ApiError('没有正现金期次', { status: 409, code: 'LOAN_FIXED_TERM_INFEASIBLE' }); return fixture(path) as T; };
 const user = mount(request); fireEvent.change(screen.getByLabelText('额外提前偿还本金'), { target: { value: '0.01' } });
 await user.click(screen.getByRole('radio', { name: /自选更短期数/ }));
 expect(await screen.findByText(/没有可用的后续期数/)).toBeInTheDocument(); expect(screen.getByRole('button', { name: '改为一次结清' })).toBeEnabled();
 expect(screen.getByRole('radio', { name: /自选更短期数/ })).toBeChecked(); expect(screen.getByRole('button', { name: '确认还款' })).toBeDisabled();
});

it('uses current-extra term options to recover from an infeasible retained plan and explicitly chooses ADJUST_TERM', async () => {
 const optionReads: string[] = []; const writes: Record<string, unknown>[] = [];
 const request: RequestFn = async <T,>(path: string, options?: Parameters<RequestFn>[1]) => {
  const p = new URLSearchParams(path.split('?')[1]);
  if (options?.method === 'POST') { writes.push(options.body as Record<string, unknown>); return {} as T; }
  if (path.includes('/term-options?')) {
   optionReads.push(p.get('additionalPrincipal')!);
   return { remainingPrincipal: p.get('additionalPrincipal') === '0' ? '3.60' : '0.01', duePrincipal: '0.00', dueInterest: '0.04', policy: initialPolicy, options: [{ periods: 1, allowed: p.get('additionalPrincipal') === '3.59', reason: null, firstPaymentAmount: '0.01', roundingPolicy: 'CUSTOM_REALLOCATION_V1' }, { periods: 4, allowed: false, reason: 'NO_POSITIVE_CASH_SCHEDULE', firstPaymentAmount: null, roundingPolicy: null }] } as T;
  }
  if (path.includes('/repayment-preview?') && p.get('strategy') === 'REDUCE_PAYMENT') throw new ApiError('保留期数不可行', { status: 409, code: 'LOAN_FIXED_TERM_INFEASIBLE' });
  return fixture(path) as T;
 };
 const user = mount(request); fireEvent.change(screen.getByLabelText('额外提前偿还本金'), { target: { value: '3.59' } });
 await screen.findByText('保留期数不可行'); await user.click(screen.getByRole('radio', { name: /自选更短期数/ }));
 await user.selectOptions(await screen.findByLabelText('后续还款期数'), '1');
 await user.click(await screen.findByRole('button', { name: '确认还款 ¥0.64' })); await waitFor(() => expect(writes).toHaveLength(1));
 expect(optionReads).toContain('3.59'); expect(writes[0]).toMatchObject({ strategy: 'ADJUST_TERM', targetPeriods: 1 });
});

it('does not turn sub-cent input into spendable cash and explains how to correct it', async () => {
 const reads: string[] = [];
 const request: RequestFn = async <T,>(path: string) => { reads.push(path); return fixture(path) as T; };
 mount(request); fireEvent.change(screen.getByLabelText('额外提前偿还本金'), { target: { value: '0.001' } });
 expect(await screen.findByText('额外本金请输入大于 0 且最多两位小数的金额。')).toBeInTheDocument();
 expect(screen.getByRole('button', { name: '确认还款' })).toBeDisabled(); expect(reads.some(path => path.includes('/repayment-preview?'))).toBe(false);
});
