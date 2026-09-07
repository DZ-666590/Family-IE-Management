import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { RecurringPage } from './RecurringPage';
import type { RequestFn } from '../common';

it('previews the actual recurring cash payment before recording today without changing the due date', async () => {
  const page = (items: unknown[]) => ({ items, page: 0, size: 50, totalElements: items.length, totalPages: 1, hasNext: false });
  const request = vi.fn(async (path: string, options?: any) => {
    if (options?.method === 'POST') return {};
    if (path.startsWith('/api/recurring-rules')) return page([{ id: 1, amount: '0.20', kind: 'expense', accountId: 2, accountName: '零钱', categoryName: '水费', assignedUserId: 7 }]);
    if (path.startsWith('/api/recurring-occurrences')) return page([{ id: 3, ruleId: 1, dueOn: '2026-01-01', assignedUserId: 7, status: 'PENDING' }]);
    if (path.startsWith('/api/accounts')) return page([{ id: 2, name: '零钱', openingConfirmed: true, balance: '0.30', availableBalance: '0.30', openingOn: '2026-01-01' }]);
    return path === '/api/members' ? [] : page([]);
  });
  render(<QueryClientProvider client={new QueryClient()}><RecurringPage request={request as RequestFn} role="OWNER" userId={7}/></QueryClientProvider>);
  const user = userEvent.setup();
  await user.click(await screen.findByRole('button', { name: '确认入账' }));
  expect(await screen.findByText('预计余额 ¥0.10')).toBeInTheDocument();
  await user.click(screen.getByRole('button', { name: '记录本次账单' }));
  expect(request).toHaveBeenCalledWith('/api/recurring-occurrences/3/confirm', { method: 'POST' });
});

it('allows monthly rules to use the 31st and relies on the server for month-end clamping', async () => {
  const request = vi.fn(async (path: string) => {
    if (path === '/api/recurring-rules?includeInactive=true&page=0&size=50') return { items: [], page: 0, size: 50, totalElements: 0, totalPages: 0, hasNext: false };
    if (path === '/api/recurring-occurrences?status=PENDING&page=0&size=50') return { items: [], page: 0, size: 50, totalElements: 0, totalPages: 0, hasNext: false };
    if (path.startsWith('/api/accounts')) return { items: [{ id: 1, name: '日常账户', type: 'BANK', currency: 'CNY', openingBalance: '0.00', archivedAt: null }], page: 0, size: 50, totalElements: 1, totalPages: 1, hasNext: false };
    if (path.startsWith('/api/categories')) return { items: [{ id: 2, kind: 'expense', name: '餐饮', color: '#3370FF', defaultCategory: false, createdAt: '', parentId: null, level: 1, children: [] }], page: 0, size: 50, totalElements: 1, totalPages: 1, hasNext: false };
    if (path === '/api/members') return [{ id: 3, name: 'Kevin', roleLabel: '本人', createdAt: '' }];
    if (path.startsWith('/api/family/memberships')) return { items: [{ id: 4, userId: 7, email: 'demo@example.com', displayName: '演示用户', role: 'OWNER', status: 'ACTIVE' }], page: 0, size: 50, totalElements: 1, totalPages: 1, hasNext: false };
    throw new Error(`unexpected ${path}`);
  });
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const user = userEvent.setup();
  render(<QueryClientProvider client={client}><RecurringPage request={request as RequestFn} role="OWNER" userId={7} /></QueryClientProvider>);
  await user.click(await screen.findByRole('button', { name: '新建周期规则' }));
  expect(screen.getByLabelText('每月日期')).toHaveAttribute('max', '31');
});
