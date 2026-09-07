import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { InvestmentsPage } from './InvestmentsPage';
import type { RequestFn } from '../common';

it('keeps an earlier dependent trade read-only instead of offering a money save', async () => {
  const security = { id: 2, name: '平安银行', tsCode: '000001.SZ' };
  const earlier = { id: 1, accountId: 3, security, type: 'BUY', quantity: 1, price: '10.00', fee: '0.00', cashImpact: '-10.00', tradedOn: '2026-01-02', createdBy: 7, sourceType: 'MANUAL', sourceId: null, cashAccountId: 4, accountingConfirmed: true };
  const page = (items: unknown[]) => ({ items, page: 0, size: 50, totalElements: items.length, totalPages: 1, hasNext: false });
  const request: RequestFn = async <T,>(path: string) => {
    if (path.includes('/api/investment-trades?accountId=3&securityId=2')) return page([{ ...earlier, id: 2, type: 'SELL' }]) as T;
    if (path.startsWith('/api/investment-trades')) return page([earlier]) as T;
    if (path === '/api/portfolio') return { positions: [], totals: { cost: '0', marketValue: '0', totalProfit: '0', unpricedPositions: 0 } } as T;
    if (path === '/api/market-quotes') return [] as T;
    if (path.startsWith('/api/investment-accounts')) return page([{ id: 3, name: '券商', cashAccountId: 4, status: 'ACTIVE' }]) as T;
    if (path.startsWith('/api/securities/search')) return page([security]) as T;
    return page([]) as T;
  };
  const user = userEvent.setup();
  render(<QueryClientProvider client={new QueryClient()}><InvestmentsPage request={request} role="OWNER" /></QueryClientProvider>);
  await user.click(screen.getByRole('button', { name: '交易' }));
  await user.click(await screen.findByRole('button', { name: '编辑' }));
  expect(await screen.findByText(/后续交易依赖此记录/)).toBeInTheDocument();
  expect(screen.getByRole('button', { name: '保存投资记录' })).toBeDisabled();
  expect(screen.getByLabelText('成交单价')).toBeDisabled();
  expect(screen.queryByRole('button', { name: '登记证券' })).not.toBeInTheDocument();
});
