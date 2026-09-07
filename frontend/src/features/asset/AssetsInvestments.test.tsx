import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { AssetsPage } from './AssetsPage';
import { InvestmentsPage } from '../investment/InvestmentsPage';
import { securityResolvePayload } from '../investment/InvestmentsPage';
import { assetUpdatePayload } from './AssetsPage';
import type { RequestFn } from '../common';
import { ApiError } from '../../api/client';

const wrap = (node: React.ReactNode) => <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>{node}</QueryClientProvider>;
const page = <T,>(items: T[]) => ({ items, page: 0, size: 50, totalElements: items.length, totalPages: items.length ? 1 : 0, hasNext: false });

it('protects programmatic security selection and only dismisses the top nested confirmation', async () => {
  let registered = false;
  const request: RequestFn = async <T,>(path: string) => {
    if (path === '/api/portfolio') return { positions: [], totals: { cost: '0', marketValue: '0', realizedProfit: '0', unrealizedProfit: '0', totalProfit: '0', unpricedPositions: 0 } } as T;
    if (path === '/api/market-quotes') return [] as T;
    if (path === '/api/securities/resolve') { registered = true; return { id: 5, tsCode: '000001.SZ', name: '平安银行' } as T; }
    if (path.startsWith('/api/securities/search')) return page(registered ? [{ id: 5, tsCode: '000001.SZ', name: '平安银行' }] : []) as T;
    return page([]) as T;
  };
  const user = userEvent.setup(); render(wrap(<InvestmentsPage request={request} role="OWNER" />));
  await user.click(screen.getByRole('button', { name: '记一笔投资' }));
  await user.click(screen.getByRole('button', { name: '登记证券' }));
  await user.type(screen.getByLabelText('六位代码'), '000001');
  await user.keyboard('{Escape}');
  await user.keyboard('{Escape}');
  expect(screen.getByRole('dialog', { name: '登记 A 股证券' })).toBeInTheDocument();
  expect(screen.queryByRole('dialog', { name: '放弃未保存的修改？' })).not.toBeInTheDocument();
  await user.type(screen.getByLabelText('证券名称'), '平安银行');
  await user.click(screen.getByRole('button', { name: '登记并选择' }));
  await waitFor(() => expect(screen.queryByRole('dialog', { name: '登记 A 股证券' })).not.toBeInTheDocument());
  expect(screen.getByRole('button', { name: '登记证券' })).toHaveFocus();
  expect(await screen.findByRole('option', { name: '000001.SZ · 平安银行' })).toBeInTheDocument();
  expect(screen.getByLabelText('证券')).toHaveValue('5');
  await user.keyboard('{Escape}');
  expect(screen.getByRole('dialog', { name: '放弃未保存的修改？' })).toBeInTheDocument();
});

it('makes a saved valuation clean and discards unfinished valuation fields when closing', async () => {
  let saves = 0;
  const asset = { id: 4, name: '车辆', type: 'OTHER', ownerMemberId: null, acquiredOn: null, purchaseValue: null, currentValue: '500', status: 'ACTIVE', createdBy: 7, archivedAt: null, property: null, vehicle: null };
  const request: RequestFn = async <T,>(path: string, options?: { method?: string }) => {
    if (options?.method === 'POST') { if (++saves > 1) throw new ApiError('估值保存失败', { status: 400 }); return { id: 9 } as T; }
    if (path.includes('/valuations')) return page([]) as T;
    if (path.startsWith('/api/assets')) return page([asset]) as T;
    if (path === '/api/members') return [] as T;
    if (path === '/api/net-worth') return { asset: '500', liability: '0', netWorth: '500' } as T;
    return page([]) as T;
  };
  const user = userEvent.setup(); render(wrap(<AssetsPage request={request} role="OWNER" />));
  await user.click((await screen.findAllByRole('button', { name: '估值' }))[0]);
  await user.type(screen.getByLabelText('当前价值'), '600');
  fireEvent.submit(screen.getByLabelText('当前价值').closest('form')!);
  await waitFor(() => expect(screen.getByLabelText('当前价值')).toHaveValue(''));
  await user.type(screen.getByLabelText('当前价值'), '700');
  fireEvent.submit(screen.getByLabelText('当前价值').closest('form')!);
  await screen.findByText('估值保存失败');
  await user.keyboard('{Escape}');
  expect(screen.getByRole('dialog', { name: '放弃未保存的修改？' })).toBeInTheDocument();
  await user.click(screen.getByRole('button', { name: '继续编辑' }));
  await user.clear(screen.getByLabelText('当前价值'));
  await user.keyboard('{Escape}');
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  await user.click(screen.getAllByRole('button', { name: '估值' })[0]);
  await user.type(screen.getByLabelText('依据说明'), '应清除的旧草稿');
  await user.keyboard('{Escape}');
  await user.click(screen.getByRole('button', { name: '放弃修改' }));
  await user.click(screen.getAllByRole('button', { name: '估值' })[0]);
  expect(screen.getByLabelText('依据说明')).toHaveValue('');
});

it('shows server asset values and quote provenance without member mutations', async () => {
  const request = vi.fn(async (path: string) => {
    if (path.startsWith('/api/assets')) return page([{ id: 4, name: '滨江小家', type: 'PROPERTY', ownerMemberId: 1, acquiredOn: '2021-05-01', purchaseValue: '2600000.00', currentValue: '2850000.00', status: 'ACTIVE', createdBy: 7, archivedAt: null, property: { address: '滨江区', areaSqm: 89, usageType: '自住' }, vehicle: null }]);
    if (path === '/api/portfolio') return { positions: [], totals: { cost: '0.00', marketValue: '0.00', realizedProfit: '0.00', unrealizedProfit: '0.00', totalProfit: '0.00', unpricedPositions: 0 } };
    if (path.startsWith('/api/investment-accounts')) return page([]);
    if (path.startsWith('/api/investment-trades')) return page([]);
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
    if (path.startsWith('/api/investment-accounts')) return page([{ id: 1, name: '证券账户', brokerName: '测试券商', currency: 'CNY', status: 'ACTIVE', createdBy: 1, archivedAt: null }]);
    if (path.startsWith('/api/investment-trades')) return page([]);
    if (path === '/api/market-quotes') return [];
    if (path.startsWith('/api/securities/search')) return page([]);
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
    if (path.startsWith('/api/investment-accounts')) return page([{ id: 1, name: '证券账户', brokerName: '测试券商', currency: 'CNY', status: 'ACTIVE', createdBy: 1, archivedAt: null }]);
    if (path.startsWith('/api/investment-trades') && options?.method === 'POST') return {};
    if (path.startsWith('/api/investment-trades')) return page([]);
    if (path === '/api/market-quotes') return [];
    if (path.startsWith('/api/securities/search')) return page([{ id: 5, tsCode: '000001.SZ', name: '平安银行' }]);
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
    if (path.startsWith('/api/assets')) return page([{ id: 4, name: '滨江小家', type: 'PROPERTY', ownerMemberId: 1, acquiredOn: '2021-05-01', purchaseValue: '2600000.00', currentValue: '2850000.00', status: 'ACTIVE', createdBy: 7, archivedAt: null, property: { address: '滨江区', areaSqm: 89, usageType: '自住' }, vehicle: null }]);
    if (path.startsWith('/api/members')) return [];
    if (path === '/api/net-worth') return { asset: '2850000.00', liability: '0.00', netWorth: '2850000.00' };
    if (path.startsWith('/api/loans')) return page([]);
    throw new Error(`unexpected ${path}`);
  });
  const user = userEvent.setup();
  render(wrap(<AssetsPage request={request as RequestFn} role="OWNER" />));
  const archiveButtons = await screen.findAllByRole('button', { name: '归档' });
  await user.click(archiveButtons[0]);
  await user.click(screen.getByRole('button', { name: '归档资产' }));
  expect(await screen.findByText('资产仍被贷款引用，无法归档')).toBeInTheDocument();
});
