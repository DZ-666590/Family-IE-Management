import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { AssetsPage } from './AssetsPage';
import type { RequestFn } from '../common';

const page = (items: unknown[]) => ({ items, page: 0, size: 50, totalElements: items.length, totalPages: items.length ? 1 : 0, hasNext: false });
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
