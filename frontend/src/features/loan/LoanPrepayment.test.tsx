import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { LoansPage } from './LoansPage';
import { ApiError } from '../../api/client';
import type { Loan } from '../../api/contracts';
import type { RequestFn } from '../common';

const loan: Loan = { fundingMode: 'OPENING', accountingOn: '2026-01-01', accountingInitialized: true, lastPaymentOn: null, id: 4, name: '提前还款测试', type: 'OTHER', linkedAssetId: null, memberId: null, assignedUserId: 7, paymentAccountId: 1, paymentCategoryId: 2, principal: '20000.00', annualRate: '0.03', termMonths: 360, repaymentMethod: 'EQUAL_PRINCIPAL', startOn: '2026-09-05', currentPrincipal: '20000.00', status: 'ACTIVE' };
const page = <T,>(items: T[]) => ({ items, page: 0, size: 50, totalElements: items.length, totalPages: items.length ? 1 : 0, hasNext: false });
function mount(request: RequestFn) {
  return render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><LoansPage request={request} role="OWNER" userId={7} /></QueryClientProvider>);
}

it('opens prepayment without secure-context randomUUID and reuses the key on retry', async () => {
  // Ordinary HTTP exposes getRandomValues but not randomUUID.
  const getRandomValues = crypto.getRandomValues.bind(crypto);
  vi.stubGlobal('crypto', { getRandomValues });
  const writes: Array<{ additionalPrincipal: string; paidOn: string; idempotencyKey: string }> = [];
  const request: RequestFn = async <T,>(path: string, options?: Parameters<RequestFn>[1]) => {
    if (options?.method === 'POST') {
      if (path !== '/api/loans/4/repayment') throw new Error(`Wrong repayment endpoint: ${path}`);
      writes.push(options.body as typeof writes[number]);
      if (writes.length === 1) throw new ApiError('临时失败，请重试', { status: 503 });
      return { id: 1, transactionId: 1, amount: '100.00', remainingPrincipal: '19900.00', status: 'ACTIVE' } as T;
    }
    if (path.endsWith('/repayment-policy')) return { minimumInstallmentAmount: null, sourceNote: null, revision: 0 } as T;
    if (path.includes('/term-options?')) return { remainingPrincipal: '20000.00', duePrincipal: '0.00', dueInterest: '0.00', options: [], policy: { minimumInstallmentAmount: null, sourceNote: null, revision: 0 } } as T;
    if (path === '/api/loans/4') return loan as T;
    if (path.includes('/repayment-preview?')) {
      const params = new URLSearchParams(path.split('?')[1]);
      const summary = { principalAmount: '19900.00', periodCount: 360, maturityOn: '2056-09-05', nextPaymentOn: '2026-10-05', nextPaymentAmount: '100.00', totalInterest: '0.00', repaymentTotal: '19900.00', schedule: [] };
      return { dueInstallments: [], duePrincipalAmount: '0.00', dueInterestAmount: '0.00', additionalPrincipal: params.get('additionalPrincipal'), totalPrincipalAmount: params.get('additionalPrincipal'), totalInterestAmount: '0.00', totalCashAmount: params.get('additionalPrincipal'), balanceAfter: '19900.00', policy: { minimumInstallmentAmount: null, sourceNote: null, revision: 0 }, termOptions: [], targetPeriods: null, strategy: params.get('strategy'), paymentAccountId: 1, paidOn: params.get('paidOn'), availableBalance: '20000.00', planToken: 'prepay-token', before: summary, after: summary } as T;
    }
    return (path.startsWith('/api/loans?') ? page([loan]) : path === '/api/members' ? [] : path.startsWith('/api/accounts?') ? page([{ id: 1, name: '还款账户', openingConfirmed: true, availableBalance: '20000.00' }]) : page([])) as T;
  };
  try {
    const user = userEvent.setup(); mount(request);
    await user.click(await screen.findByRole('button', { name: '提前还款' }));
    const drawer = await screen.findByRole('dialog', { name: '提前还款测试 · 提前还款' });
    expect(screen.queryByRole('dialog', { name: /还款计划/ })).not.toBeInTheDocument();
    await user.type(within(drawer).getByLabelText('额外提前偿还本金'), '100.00');
    await user.click(within(drawer).getByRole('button', { name: '确认还款 ¥100.00' }));
    await screen.findByText('临时失败，请重试');
    await user.click(within(drawer).getByRole('button', { name: '核对本次还款结果' }));
    await screen.findByRole('dialog', { name: '提前还款测试 · 还款计划' });
    expect(writes).toHaveLength(2);
    expect(writes[0].additionalPrincipal).toBe('100.00');
    expect(writes[0].idempotencyKey).toMatch(/^[0-9a-f]{32}$/);
    expect(writes[1].idempotencyKey).toBe(writes[0].idempotencyKey);
  } finally { vi.unstubAllGlobals(); }
});

it('does not offer installment confirmation for a future due date', async () => {
  const request: RequestFn = async <T,>(path: string) => (path === '/api/loans/4' ? loan : path.startsWith('/api/loans?') ? page([loan]) : path.includes('/schedule') ? page([
    { id: 1, installmentNo: 1, dueOn: '2000-01-01', principal: '50.00', interest: '5.00', status: 'PENDING', confirmedTransactionId: null },
    { id: 2, installmentNo: 2, dueOn: '3999-01-01', principal: '50.00', interest: '5.00', status: 'PENDING', confirmedTransactionId: null }
  ]) : path === '/api/members' ? [] : page([])) as T;
  const user = userEvent.setup(); mount(request);
  await user.click(await screen.findByRole('button', { name: '查看计划' }));
  const drawer = await screen.findByRole('dialog', { name: /还款计划/ });
  expect(within(drawer).getAllByRole('button', { name: '确认还款' })).toHaveLength(1);
  expect(within(drawer).getByText('未到期')).toBeInTheDocument();
});
