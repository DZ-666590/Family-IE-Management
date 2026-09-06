import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { AssetsPage } from './AssetsPage';
import { InvestmentsPage } from '../investment/InvestmentsPage';
import { securityResolvePayload } from '../investment/InvestmentsPage';
import { assetUpdatePayload } from './AssetsPage';
import type { RequestFn } from '../common';
import { ApiError } from '../../api/client';

const wrap = (node: React.ReactNode) => <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>{node}</QueryClientProvider>;

it('shows server asset values and quote provenance without member mutations', async () => {
  const request = vi.fn(async (path: string) => {
    if (path.startsWith('/api/assets')) return { items: [{ id: 4, name: '滨江小家', type: 'PROPERTY', ownerMemberId: 1, acquiredOn: '2021-05-01', purchaseValue: '2600000.00', currentValue: '2850000.00', status: 'ACTIVE', createdBy: 7, archivedAt: null, property: { address: '滨江区', areaSqm: 89, usageType: '自住' }, vehicle: null }] };
    if (path === '/api/portfolio') return { positions: [], totals: { cost: '0.00', marketValue: '0.00', realizedProfit: '0.00', unrealizedProfit: '0.00', totalProfit: '0.00', unpricedPositions: 0 } };
    if (path.startsWith('/api/investment-accounts')) return { items: [] };
    if (path.startsWith('/api/investment-trades')) return { items: [] };
    if (path === '/api/market-quotes') return [{ securityId: 8, tsCode: '600000.SH', name: '浦发银行', price: '10.25', source: 'MANUAL', tradeDate: '2026-08-31', fetchedAt: null, stale: true, error: null }];
    throw new Error(`unexpected ${path}`);
  });
  const { unmount } = render(wrap(<AssetsPage request={request as RequestFn} role="MEMBER" />));
  expect((await screen.findAllByText('¥2,850,000.00')).length).toBeGreaterThan(0);
  expect(screen.queryByRole('button', { name: '新建资产' })).not.toBeInTheDocument();
  unmount();
  render(wrap(<InvestmentsPage request={request as RequestFn} role="MEMBER" />));
  await userEvent.click(screen.getByRole('button', { name: '行情' }));
  expect(await screen.findByText('手工价格')).toBeInTheDocument();
  expect(screen.getByText('行情已过期')).toBeInTheDocument();
});

it('offers security registration when an investment search has no matches', async () => {
  const request = vi.fn(async (path: string) => {
    if (path === '/api/portfolio') return { positions: [], totals: { cost: '0.00', marketValue: '0.00', realizedProfit: '0.00', unrealizedProfit: '0.00', totalProfit: '0.00', unpricedPositions: 0 } };
    if (path.startsWith('/api/investment-accounts')) return { items: [{ id: 1, name: '证券账户', brokerName: '测试券商', currency: 'CNY', status: 'ACTIVE', createdBy: 1, archivedAt: null }] };
    if (path.startsWith('/api/investment-trades')) return { items: [] };
    if (path === '/api/market-quotes') return [];
    if (path.startsWith('/api/securities/search')) return { items: [] };
    throw new Error(`unexpected ${path}`);
  });
  render(wrap(<InvestmentsPage request={request as RequestFn} role="OWNER" />));

  await userEvent.click(await screen.findByRole('button', { name: '记一笔投资' }));
  expect(await screen.findByRole('button', { name: '登记证券' })).toBeInTheDocument();
});

it('normalizes a six-digit A-share code with the selected market', () => {
  expect(securityResolvePayload({ code: ' 000001 ', market: 'SZ', name: '平安银行' }))
    .toEqual({ tsCode: '000001.SZ', name: '平安银行' });
});

it('omits immutable fields when editing an asset', () => {
  expect(assetUpdatePayload({
    id: 4,
    name: '滨江小家',
    type: 'PROPERTY',
    ownerMemberId: '1',
    acquiredOn: '2021-05-01',
    purchaseValue: '2600000.00',
    currentValue: '2850000.00',
    address: '滨江区',
    areaSqm: '89',
    usageType: '自住',
    brandModel: '',
    plateHint: '',
    purchaseYear: ''
  })).toEqual({
    name: '滨江小家',
    ownerMemberId: 1,
    property: { address: '滨江区', areaSqm: '89', usageType: '自住' },
    vehicle: null
  });
});

it('records a buy without sending an explicit trade source that the public API rejects', async () => {
  const today = new Date().toISOString().slice(0, 10);
  const request = vi.fn(async (path: string, options?: { method?: string }) => {
    if (path === '/api/portfolio') return { positions: [], totals: { cost: '0.00', marketValue: '0.00', realizedProfit: '0.00', unrealizedProfit: '0.00', totalProfit: '0.00', unpricedPositions: 0 } };
    if (path.startsWith('/api/investment-accounts')) return { items: [{ id: 1, name: '证券账户', brokerName: '测试券商', currency: 'CNY', status: 'ACTIVE', createdBy: 1, archivedAt: null }] };
    if (path.startsWith('/api/investment-trades') && options?.method === 'POST') return {};
    if (path.startsWith('/api/investment-trades')) return { items: [] };
    if (path === '/api/market-quotes') return [];
    if (path.startsWith('/api/securities/search')) return { items: [{ id: 5, tsCode: '000001.SZ', name: '平安银行' }] };
    throw new Error(`unexpected ${path}`);
  });
  const user = userEvent.setup();
  render(wrap(<InvestmentsPage request={request as RequestFn} role="OWNER" />));
  await user.click(await screen.findByRole('button', { name: '记一笔投资' }));
  const dialog = screen.getByRole('dialog', { name: '记一笔投资' });
  await user.selectOptions(within(dialog).getByLabelText('投资账户'), '1');
  expect(await within(dialog).findByRole('option', { name: '000001.SZ · 平安银行' })).toBeInTheDocument();
  await user.selectOptions(within(dialog).getByLabelText('证券'), '5');
  await user.type(within(dialog).getByLabelText('数量'), '100');
  await user.type(within(dialog).getByLabelText('成交单价'), '10.00');
  await user.click(within(dialog).getByRole('button', { name: '保存投资记录' }));
  await waitFor(() => expect(request).toHaveBeenCalledWith('/api/investment-trades', expect.objectContaining({ method: 'POST' })));
  expect(request).toHaveBeenCalledWith('/api/investment-trades', expect.objectContaining({
    method: 'POST',
    body: expect.not.objectContaining({ sourceType: expect.anything(), sourceId: expect.anything() })
  }));
  expect(request).toHaveBeenCalledWith('/api/investment-trades', expect.objectContaining({
    method: 'POST',
    body: { accountId: 1, securityId: 5, type: 'BUY', quantity: '100', price: '10.00', fee: '0', tradedOn: today }
  }));
});

it('keeps the loan-reference conflict visible when archiving a loan-linked asset is rejected', async () => {
  const request = vi.fn(async (path: string, options?: { method?: string }) => {
    if (path.startsWith('/api/assets') && options?.method === 'DELETE') {
      throw new ApiError('资产仍被贷款引用，无法归档', { status: 409, code: 'RESOURCE_IN_USE' });
    }
    if (path.startsWith('/api/assets')) return { items: [{ id: 4, name: '滨江小家', type: 'PROPERTY', ownerMemberId: 1, acquiredOn: '2021-05-01', purchaseValue: '2600000.00', currentValue: '2850000.00', status: 'ACTIVE', createdBy: 7, archivedAt: null, property: { address: '滨江区', areaSqm: 89, usageType: '自住' }, vehicle: null }] };
    if (path.startsWith('/api/members')) return [];
    if (path === '/api/net-worth') return { asset: '2850000.00', liability: '0.00', netWorth: '2850000.00' };
    if (path.startsWith('/api/loans')) return { items: [] };
    throw new Error(`unexpected ${path}`);
  });
  const user = userEvent.setup();
  render(wrap(<AssetsPage request={request as RequestFn} role="OWNER" />));
  const archiveButtons = await screen.findAllByRole('button', { name: '归档' });
  await user.click(archiveButtons[0]);
  await user.click(screen.getByRole('button', { name: '归档资产' }));
  expect(await screen.findByText('资产仍被贷款引用，无法归档')).toBeInTheDocument();
});
