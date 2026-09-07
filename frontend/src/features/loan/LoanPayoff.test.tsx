import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, within, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { LoansPage } from './LoansPage';
import { LoanPayoffPanel } from './LoanPayoffPanel';
import type { Account, Loan } from '../../api/contracts';
import { ApiError } from '../../api/client';
import type { RequestFn } from '../common';

const loan = { id: 4, name: '结清测试', type: 'OTHER', fundingMode: 'FINANCED_PURCHASE', accountingOn: '2026-01-01', accountingInitialized: true, paymentAccountId: 1, currentPrincipal: '2000.00', principal: '2000.00', scheduledRepaymentTotal: '2150.00', remainingRepaymentTotal: '2150.00', paidRepaymentTotal: '0.00', annualRate: '0.1', termMonths: 2, repaymentMethod: 'CUSTOM', startOn: '2026-01-01', status: 'ACTIVE' };
const page = <T,>(items: T[]) => ({ items, page: 0, size: 50, totalElements: items.length, totalPages: items.length ? 1 : 0, hasNext: false });
const accounts = [{ id: 1, name: '日常账户', openingConfirmed: true, availableBalance: '0.00' }, { id: 2, name: '还款账户', openingConfirmed: true, availableBalance: '2200.00' }];

it('shows server whole-loan totals and confirms a quoted selected-account payoff with stable retry', async () => {
 const writes: Record<string, unknown>[] = []; const quotes: string[] = [];
 const request: RequestFn = async <T,>(path: string, options?: Parameters<RequestFn>[1]) => {
  if (options?.method === 'POST') {
   expect(path).toBe('/api/loans/4/payoff'); writes.push(options.body as Record<string, unknown>);
   if (writes.length === 1) throw new ApiError('临时失败，请重试', { status: 503 });
   return { id: 9, operationKind: 'PAYOFF', cashAmount: '2100.00' } as T;
  }
  if (path.includes('/payoff-quote?')) { quotes.push(path); const account = Number(new URLSearchParams(path.split('?')[1]).get('paymentAccountId')); return { principalAmount: '2000.00', dueInterestAmount: '100.00', interestAmount: '100.00', futureScheduledInterest: '50.00', cashAmount: '2100.00', paymentAccountId: account, availableBalance: account === 2 ? '2200.00' : '0.00', planToken: `token-${account}` } as T; }
  return (path === '/api/loans/4' ? loan : path.startsWith('/api/loans?') ? page([loan]) : path.startsWith('/api/accounts?') ? page(accounts) : path === '/api/members' ? [] : path.endsWith('/prepayments') ? [] : page([])) as T;
 };
 const user = userEvent.setup(); render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><LoansPage request={request} role="OWNER" /></QueryClientProvider>);
 expect(await screen.findByText('当前有效计划总金额')).toBeInTheDocument(); expect(screen.getByText('计划剩余本息')).toBeInTheDocument(); expect(screen.getByText('累计已还现金')).toBeInTheDocument();
 await user.click(await screen.findByRole('button', { name: '一次结清' }));
 const drawer = await screen.findByRole('dialog', { name: '结清测试 · 一次结清' });
 await user.selectOptions(within(drawer).getByLabelText('本次付款账户'), '2');
 await waitFor(() => expect(within(drawer).getByRole('button', { name: '确认一次结清' })).toBeEnabled());
 expect(within(drawer).getByText('未到期计划利息（本次不收取）')).toBeInTheDocument();
 await user.click(within(drawer).getByRole('button', { name: '确认一次结清' })); await screen.findByText('临时失败，请重试');
 await user.click(within(drawer).getByRole('button', { name: '确认一次结清' }));
 await waitFor(() => expect(writes).toHaveLength(2));expect(writes[0]).toEqual(writes[1]);expect(writes[0]).toMatchObject({ paymentAccountId: 2, interestAmount: null, planToken: 'token-2' });expect(writes[0].idempotencyKey).toBeTruthy();expect(quotes.some(q => q.includes('paymentAccountId=2'))).toBe(true);
});

it('keeps a late quote for the previous account out of the confirmation and refreshes a rejected plan', async () => {
 let resolveOld: (value: unknown) => void = () => {}; let selectedQuotes = 0; const writes: Record<string, unknown>[] = [];
 const request: RequestFn = async <T,>(path: string, options?: Parameters<RequestFn>[1]) => {
  if (options?.method === 'POST') { writes.push(options.body as Record<string, unknown>); if (writes.length === 1) throw new ApiError('计划已变化，请重新核对', { status: 409, code: 'LOAN_PLAN_CHANGED' }); return {} as T; }
  const selected = new URLSearchParams(path.split('?')[1]).get('paymentAccountId');
  if (selected === '1') return new Promise(resolve => { resolveOld = resolve as (value: unknown) => void; });
  selectedQuotes++; return { principalAmount: '2000.00', dueInterestAmount: '100.00', interestAmount: '100.00', futureScheduledInterest: '50.00', cashAmount: selectedQuotes === 1 ? '2100.00' : '2110.00', paymentAccountId: 2, availableBalance: '2200.00', planToken: `selected-${selectedQuotes}` } as T;
 };
 const user = userEvent.setup(); const paid = vi.fn().mockResolvedValue(undefined);
 render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><LoanPayoffPanel loan={loan as Loan} accounts={accounts as Account[]} request={request} onClose={() => {}} onPaid={paid} /></QueryClientProvider>);
 await user.selectOptions(screen.getByLabelText('本次付款账户'), '2');
 await waitFor(() => expect(screen.getByRole('button', { name: '确认一次结清' })).toBeEnabled());
 resolveOld({ principalAmount: '9000.00', cashAmount: '9999.00', paymentAccountId: 1, planToken: 'outdated' });
 await user.click(screen.getByRole('button', { name: '确认一次结清' }));
 await screen.findByText('计划已变化，请重新核对');
 await waitFor(() => expect(selectedQuotes).toBe(2));
 expect(paid).not.toHaveBeenCalled();expect(writes).toHaveLength(1);expect(writes[0].planToken).toBe('selected-1');
 await waitFor(() => expect(screen.getByRole('button', { name: '确认一次结清' })).toBeEnabled());
 await user.click(screen.getByRole('button', { name: '确认一次结清' }));
 await waitFor(() => expect(paid).toHaveBeenCalledOnce());expect(writes[1]).toMatchObject({ planToken: 'selected-2', paymentAccountId: 2 });
});
