import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { TransfersPanel, AccountingHistory } from './accounting-flows';
import type { Account } from '../../api/contracts';
import type { RequestFn } from '../common';
const cash = (id: number, name: string, availableBalance: string): Account => ({ id, name, availableBalance, balance: availableBalance, openingBalance: availableBalance, openingConfirmed: true, openingOn: '2026-01-01', archivedAt: null, type: 'BANK', currency: 'CNY' });
it('previews both transfer accounts exactly and submits a single transfer with its own key', async () => {
  const writes: any[] = [];
  const request: RequestFn = async <T,>(path: string, options?: any) => {
    if (options?.method === 'POST') { writes.push(options.body); return { id: 4 } as T; }
    return { items: [], page: 0, size: 20, totalElements: 0, totalPages: 0, hasNext: false } as T;
  };
  render(<QueryClientProvider client={new QueryClient()}><TransfersPanel request={request} role="OWNER" accounts={[cash(1, '工资卡', '10.30'), cash(2, '零钱', '0.10')]} onHistory={() => {}} /></QueryClientProvider>);
  const user = userEvent.setup();
  await user.click(screen.getByRole('button', { name: '记录账户互转' }));
  const form = within(screen.getByRole('dialog'));
  await user.selectOptions(form.getByLabelText('转出账户'), '1');
  await user.selectOptions(form.getByLabelText('转入账户'), '2');
  await user.type(form.getByLabelText('互转金额'), '10.20');
  expect(screen.getByText('预计余额 ¥0.10')).toBeInTheDocument();
  expect(screen.getByText('预计余额 ¥10.30')).toBeInTheDocument();
  await user.click(form.getByRole('button', { name: '保存互转记录' }));
  expect(writes).toEqual([expect.objectContaining({ fromAccountId: 1, toAccountId: 2, amount: '10.20', idempotencyKey: expect.stringMatching(/^[a-f0-9]{32}$/) })]);
});
it('paginates complete audit history and displays immutable reversal legs after deletion', async () => {
  const request = vi.fn(async (path: string) => {
    const page = path.includes('page=1') ? 1 : 0;
    return { items: [{ journalId: page ? 1 : 2, sourceType: 'TRANSACTION', sourceId: 8, operation: page ? 'POST' : 'REVERSE', revision: 1, effectiveOn: '2026-01-01', recordedAt: '2026-01-02T00:00:00Z', actorId: 7, reversesJournalId: page ? null : 1, legs: [{ accountCode: 'CASH:1', debit: '10.00', credit: '0.00', categoryId: null, memberId: null }] }], page, size: 20, totalElements: 21, totalPages: 2, hasNext: !page };
  });
  render(<QueryClientProvider client={new QueryClient()}><AccountingHistory request={request as RequestFn} source={{ sourceType: 'TRANSACTION', sourceId: 8 }} /></QueryClientProvider>);
  const user = userEvent.setup();
  await user.click(await screen.findByText('冲回原入账'));
  expect(screen.getByText('现金账户')).toBeInTheDocument();
  await user.click(screen.getByRole('button', { name: '下一页' }));
  expect(await screen.findByText('入账')).toBeInTheDocument();
  expect(request).toHaveBeenCalledWith('/api/accounting/history?page=1&size=20&sourceType=TRANSACTION&sourceId=8', { responseType: 'page' });
});
