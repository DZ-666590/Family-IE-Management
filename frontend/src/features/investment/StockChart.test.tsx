import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { StockChart } from './StockChart';
import type { RequestFn } from '../common';

const chart = vi.hoisted(() => ({ setSymbol: vi.fn(), setPeriod: vi.fn(), setDataLoader: vi.fn(), createIndicator: vi.fn(), resize: vi.fn() }));
const dispose = vi.hoisted(() => vi.fn());
vi.mock('klinecharts', () => ({ init: () => chart, dispose }));
const stock = { id: 5, tsCode: '000001.SZ', name: '平安银行' };
function show(request: RequestFn, symbol = stock) { return render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><StockChart request={request} security={symbol}/></QueryClientProvider>); }
const data = { symbol: '000001.SZ', source: 'BAOSTOCK', adjustment: 'qfq', asOf: '2026-09-07', fetchedAt: '2026-09-08T08:00:00Z', stale: false, supported: true, bars: [{ timestamp: 1788710400000, open: 11.87, close: 11.70, high: 11.88, low: 11.65, volume: 108727552, turnover: 1275606340.01 }] };
it('loads selected stock candles, hands actual bars to KLineChart, and disposes on close', async () => {
  const request = vi.fn(async () => data);
  const view = show(request as RequestFn);
  await waitFor(() => expect(chart.setDataLoader).toHaveBeenCalled());
  const loader = chart.setDataLoader.mock.calls.at(-1)![0];
  const callback = vi.fn();
  loader.getBars({ type: 'init', callback });
  expect(callback).toHaveBeenCalledWith(data.bars, false);
  expect(screen.getByText(/BaoStock/)).toBeInTheDocument();
  await userEvent.selectOptions(screen.getByLabelText('复权方式'), 'none');
  await waitFor(() => expect(request).toHaveBeenCalledWith('/api/securities/5/candles?adjust=none'));
  view.unmount();
  expect(dispose).toHaveBeenCalled();
});
it('shows explicit unsupported coverage rather than a fake or empty chart', async () => {
  show((async () => ({ ...data, supported: false, bars: [] })) as RequestFn, { id: 8, name: '万达轴承', tsCode: '920002.BJ' });
  expect(await screen.findByText(/暂未覆盖这只股票/)).toBeInTheDocument();
  expect(screen.queryByRole('img', { name: /K 线图/ })).not.toBeInTheDocument();
});
it('keeps stale history visibly identified and offers retry after failure', async () => {
  let fail = true;
  show((async () => { if (fail) throw new Error('行情服务暂时不可用'); return { ...data, stale: true }; }) as RequestFn);
  await screen.findByText('行情服务暂时不可用');
  fail = false;
  await userEvent.click(screen.getByRole('button', { name: '重新加载行情' }));
  expect(await screen.findByText(/缓存行情/)).toBeInTheDocument();
});
