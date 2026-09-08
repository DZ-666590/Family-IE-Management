import { useState } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { StockPicker } from './StockPicker';
import { InvestmentSetup } from './InvestmentSetup';
import { aggregateBars } from './chart-data';
import type { Security } from '../../api/contracts';
import type { RequestFn } from '../common';

const security: Security = { id: 5, market: 'SZ', tsCode: '000001.SZ', name: '平安银行', active: true, securityType: 'STOCK' };
const page = (items: Security[]) => ({ items, page: 0, size: 20, totalPages: 1, totalElements: items.length, hasNext: false });
function wrap(child: React.ReactNode) {
  return <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })}>{child}</QueryClientProvider>;
}

it('selects a real catalog stock and preserves it while searching another name', async () => {
  const request = vi.fn(async (path: string) => path.includes('catalog-status') ? { state: 'READY', count: 5558 } : page(path.includes('nothing') ? [] : [security]));
  function Picker() {
    const [value, setValue] = useState<Security | null>(null);
    return <StockPicker request={request as RequestFn} value={value} onChange={setValue}/>;
  }
  render(wrap(<Picker/>));
  const user = userEvent.setup();
  await screen.findByRole('option', { name: '000001.SZ · 平安银行' });
  await user.selectOptions(screen.getByLabelText('证券'), '5');
  await user.type(screen.getByLabelText('证券搜索'), 'nothing');
  await screen.findByText('没有找到匹配股票，请检查代码或名称。');
  expect(screen.getByLabelText('证券')).toHaveValue('5');
  expect(screen.queryByRole('button', { name: '登记证券' })).not.toBeInTheDocument();
  expect(request.mock.calls.every(([path]) => !path.includes('/resolve'))).toBe(true);
});

it('distinguishes a failed stock search from an empty directory and provides retry', async () => {
  let failed = true;
  const request = vi.fn(async (path: string) => {
    if (path.includes('catalog-status')) return { state: 'READY', count: 5558 };
    if (failed) throw new Error('连接中断');
    return page([security]);
  });
  render(wrap(<StockPicker request={request as RequestFn} value={null} onChange={() => {}}/>));
  await screen.findByText('股票搜索暂时不可用');
  failed = false;
  await userEvent.click(screen.getByRole('button', { name: '重试搜索' }));
  expect(await screen.findByRole('option', { name: '000001.SZ · 平安银行' })).toBeInTheDocument();
});

const account = { id: 3, name: '证券账户', brokerName: '券商', fundingAccountId: 7, currency: 'CNY', status: 'ACTIVE' as const, createdBy: 1, archivedAt: null };
const cash = { id: 7, name: '资金卡', type: 'BANK' as const, currency: 'CNY', openingBalance: '0.00', balance: '0.00', availableBalance: '0.00', openingConfirmed: true, openingOn: '2026-01-01', archivedAt: null };
it('routes existing holdings to OPENING without completing setup before a save', async () => {
  const request = vi.fn(async () => ({ completed: false, hasAccounts: true, hasTrades: false }));
  const opening = vi.fn();
  render(wrap(<InvestmentSetup request={request as RequestFn} manager accounts={[account]} cashAccounts={[cash]} onCreateAccount={() => {}} onOpening={opening}/>));
  await userEvent.click(await screen.findByRole('button', { name: '录入已有持仓' }));
  expect(opening).toHaveBeenCalledWith('3');
  expect(request.mock.calls).toHaveLength(1);
  expect(screen.getByText(/不会再次扣减现金/)).toBeInTheDocument();
});

it('persists explicitly empty investment setup and hides it after confirmation', async () => {
  const request = vi.fn(async (_path: string, options?: { method?: string }) => ({ completed: options?.method === 'POST', hasAccounts: true, hasTrades: false }));
  render(wrap(<InvestmentSetup request={request as RequestFn} manager accounts={[account]} cashAccounts={[cash]} onCreateAccount={() => {}} onOpening={() => {}}/>));
  await userEvent.click(await screen.findByRole('button', { name: '暂时没有持仓，完成初始化' }));
  await waitFor(() => expect(screen.queryByRole('region', { name: '投资初始化' })).not.toBeInTheDocument());
  expect(request).toHaveBeenCalledWith('/api/investment-setup/complete', { method: 'POST' });
});

it('does not offer mutations to members or accept an uninitialized cash account', async () => {
  const request = vi.fn(async () => ({ completed: false, hasAccounts: true, hasTrades: false }));
  const view = render(wrap(<InvestmentSetup request={request as RequestFn} manager={false} accounts={[account]} cashAccounts={[cash]} onCreateAccount={() => {}} onOpening={() => {}}/>));
  await screen.findByText(/请家庭管理员/);
  expect(screen.queryByRole('button', { name: '录入已有持仓' })).not.toBeInTheDocument();
  view.unmount();
  render(wrap(<InvestmentSetup request={request as RequestFn} manager accounts={[account]} cashAccounts={[{ ...cash, openingConfirmed: false }]} onCreateAccount={() => {}} onOpening={() => {}}/>));
  await screen.findByRole('link', { name: '初始化现金账户' });
  expect(screen.queryByRole('button', { name: '暂时没有持仓，完成初始化' })).not.toBeInTheDocument();
});

const bar = (date: string, open: number, close: number, high: number, low: number, volume: number) => ({ timestamp: Date.parse(`${date}T00:00:00+08:00`), open, close, high, low, volume, turnover: volume * 10 });
it('aggregates weekly OHLC and sums shares and yuan across Shanghai trading dates', () => {
  const values = [bar('2026-09-04', 10, 11, 12, 9, 100), bar('2026-09-07', 11, 12, 13, 10, 200), bar('2026-09-08', 12, 10, 14, 8, 300)];
  expect(aggregateBars(values, 'week')).toEqual([values[0], { timestamp: Date.parse('2026-09-07T00:00:00+08:00'), open: 11, close: 10, high: 14, low: 8, volume: 500, turnover: 5000 }]);
  expect(values[1].volume).toBe(200);
});
it('does not mix months or years and keeps daily values unchanged', () => {
  const values = [bar('2025-12-31', 10, 11, 12, 9, 100), bar('2026-01-02', 11, 12, 13, 10, 200)];
  expect(aggregateBars(values, 'month')).toEqual(values);
  expect(aggregateBars(values, 'day')).toEqual(values);
  expect(aggregateBars([], 'week')).toEqual([]);
});
