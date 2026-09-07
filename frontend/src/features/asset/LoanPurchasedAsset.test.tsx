import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { AssetsPage } from './AssetsPage';
import type { RequestFn } from '../common';
import { ApiError } from '../../api/client';

const page = (items: unknown[]) => ({ items, page: 0, size: 50, totalElements: items.length, totalPages: items.length ? 1 : 0, hasNext: false });
it('creates a manual vehicle with null optional year and plate in the actual POST payload', async () => {
  const request = vi.fn(async (path: string, opts?: any) => {
    if (path === '/api/assets' && opts?.method === 'POST') {
      if (opts.body.vehicle.purchaseYear !== null || opts.body.vehicle.plateHint !== null) {
        throw new ApiError('非必要车辆资料应留空', { status: 422, code: 'VALIDATION_ERROR' });
      }
      return { id: 9, ...opts.body };
    }
    if (path === '/api/members') return [];
    if (path === '/api/net-worth') return { asset: '0.00' };
    return page([]);
  });
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><AssetsPage request={request as RequestFn} role="OWNER" /></QueryClientProvider>);
  const user = userEvent.setup();
  await user.click(screen.getByRole('button', { name: '新建资产' }));
  await user.type(screen.getByLabelText('资产名称'), '自有车辆');
  await user.selectOptions(within(screen.getByRole('dialog')).getByLabelText('资产类型'), 'VEHICLE');
  await user.type(screen.getByLabelText('当前价值'), '1000.00');
  await user.type(screen.getByLabelText('品牌型号'), '实际车型');
  expect(screen.getByLabelText('购车年份')).toHaveValue(null);
  await user.click(screen.getByRole('button', { name: '保存资产' }));
  await waitFor(() => expect(request).toHaveBeenCalledWith('/api/assets', expect.objectContaining({ method: 'POST', body: {
    accountingMode: 'OPENING', accountingOn: expect.any(String), fundingAccountId: null, name: '自有车辆', type: 'VEHICLE', ownerMemberId: null,
    acquiredOn: null, purchaseValue: null, currentValue: '1000.00', property: null, vehicle: { brandModel: '实际车型', plateHint: null, purchaseYear: null }
  } })));
  await waitFor(() => expect(screen.queryByRole('button', { name: '保存资产' })).not.toBeInTheDocument());
});
it('completes a purchased vehicle with only known metadata and opens the original loan journal', async () => {
  const asset = { id: 8, name: '车辆1', type: 'VEHICLE', accountingMode: 'FINANCED_PURCHASE', accountingOn: '2026-01-01', acquiredOn: '2026-01-01', initialValue: '1000.00', purchaseValue: '1000.00', currentValue: '1000.00', status: 'ACTIVE', property: null, vehicle: null, ownerMemberId: null, createdBy: 7, archivedAt: null, acquisitionSourceType: 'LOAN_FINANCED_PURCHASE', acquisitionSourceId: 4, detailsPending: true };
  const request = vi.fn(async (path: string, opts?: any) => {
    if (opts?.method) return { ...asset, detailsPending: false };
    if (path.startsWith('/api/accounting/')) return page([]);
    if (path.startsWith('/api/assets')) return page([asset]);
    if (path === '/api/members') return [];
    if (path === '/api/net-worth') return { asset: '1000.00' };
    return page([]);
  });
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><AssetsPage request={request as RequestFn} role="OWNER" /></QueryClientProvider>);
  const user = userEvent.setup();
  expect((await screen.findAllByText('贷款购买物 · 资料待补齐')).length).toBeGreaterThan(0);
  await user.click(screen.getByRole('button', { name: '起始账务' }));
  await waitFor(() => expect(request.mock.calls.some(([path]) => path.includes('sourceType=LOAN_FINANCED_PURCHASE') && path.includes('sourceId=4'))).toBe(true));
  await user.keyboard('{Escape}');
  await user.click(screen.getAllByRole('button', { name: '编辑' })[0]);
  expect(screen.getByLabelText('购入价值')).toBeDisabled();
  expect(screen.getByLabelText('品牌型号')).toHaveValue('');
  expect(screen.getByLabelText('购车年份')).not.toBeRequired();
  await user.type(screen.getByLabelText('品牌型号'), '真实车型');
  await user.click(screen.getByRole('button', { name: '保存资产' }));
  await waitFor(() => expect(request).toHaveBeenCalledWith('/api/assets/8', expect.objectContaining({ method: 'PATCH', body: { name: '车辆1', ownerMemberId: null, property: null, vehicle: { brandModel: '真实车型', plateHint: null, purchaseYear: null } } })));
});
