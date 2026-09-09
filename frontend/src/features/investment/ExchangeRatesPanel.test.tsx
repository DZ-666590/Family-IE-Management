import { render, screen, within, fireEvent, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ExchangeRatesPanel } from './ExchangeRatesPanel';
import type { RequestFn } from '../common';

function setup(manager=true,unverifiedReferences=0) {
  const rows=[{ currency:'CNY',cnyPerUnit:'1.000000000000',state:'READY',source:'IDENTITY',effectiveOn:'2026-09-08' },
    { currency:'HKD',state:'MISSING' },{currency:'USD',cnyPerUnit:'7.000000000000',state:'READY',source:'ECB',effectiveOn:'2026-09-07'}];
  const request=vi.fn(async (path:string)=>path.endsWith('/audit')?{unverifiedReferences}:path.includes('/history')?{from:'2026-08-10',to:'2026-09-08',rows:[],state:'READY',retryAfter:null,detail:null}:{asOf:'2026-09-08',rows,refreshState:path.includes('/refresh')?'FAILED':'IDLE'});
  render(<QueryClientProvider client={new QueryClient({defaultOptions:{queries:{retry:false}}})}><ExchangeRatesPanel request={request as RequestFn} manager={manager}/></QueryClientProvider>);
  return request;
}
it('shows rate direction and missing values without pretending they are zero',async()=>{
  setup();
  const table=await screen.findByRole('table',{name:'人民币参考汇率'});
  expect(within(table).getByText('7.000000')).toBeInTheDocument();
  expect(within(table).getByText('1 USD')).toBeInTheDocument();
  expect(within(table).getByText('暂无汇率')).toBeInTheDocument();
  expect(within(table).queryByText('0.000000')).not.toBeInTheDocument();
});
it('shows household legacy uncertainty without suggesting automatic history rewrites',async()=>{
  setup(false,3);
  expect(await screen.findByText(/本家庭有 3 条历史汇率引用/)).toHaveTextContent('保留原记录，不会自动重写');
});
it('shows failed history acquisition with cached rows and a retry action',async()=>{
  const request=vi.fn(async(path:string)=>path.endsWith('/audit')?{unverifiedReferences:0}:path.includes('/history')?{
    from:'2026-08-10',to:'2026-09-08',state:'FAILED',retryAfter:'2026-09-08T00:00:00Z',detail:'历史获取失败，保留已有记录。',
    rows:[{currency:'USD',cnyPerUnit:'7.100000000000',effectiveOn:'2026-08-12',source:'ECB',state:'HISTORICAL',batchId:9}],
  }:{asOf:'2026-09-08',rows:[],refreshState:'IDLE'});
  render(<QueryClientProvider client={new QueryClient({defaultOptions:{queries:{retry:false}}})}><ExchangeRatesPanel request={request as RequestFn} manager={false}/></QueryClientProvider>);
  expect(await screen.findByText('历史获取失败，保留已有记录。')).toBeInTheDocument();
  expect(within(screen.getByRole('table',{name:'历史汇率'})).getByText('7.100000')).toBeInTheDocument();
  const calls=request.mock.calls.length;
  await userEvent.click(screen.getByRole('button',{name:'重试历史汇率'}));
  await waitFor(()=>expect(request.mock.calls.length).toBeGreaterThan(calls));
});
it('keeps the previous rates visible when refresh fails',async()=>{
  setup(); await screen.findByText('7.000000');
  await userEvent.click(screen.getByRole('button',{name:'更新汇率'}));
  expect(await screen.findByText('更新失败，保留上次汇率。')).toBeInTheDocument();
  expect(screen.getByText('7.000000')).toBeInTheDocument();
});
it('does not offer global refresh to ordinary members',async()=>{
  setup(false);await screen.findByText('7.000000');
  expect(screen.queryByRole('button',{name:'更新汇率'})).not.toBeInTheDocument();
});
it('does not query history before the earliest supported date',async()=>{
  const request=setup();await screen.findByText('7.000000');
  fireEvent.change(screen.getByLabelText('汇率日期'),{target:{value:'1999-01-01'}});
  await waitFor(()=>expect(request).toHaveBeenCalledWith('/api/exchange-rates/history?from=1999-01-01&to=1999-01-01',expect.anything()));
});
