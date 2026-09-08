import { QueryClient, QueryClientProvider, useQuery } from '@tanstack/react-query';
import { act, render, renderHook, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ReferenceQuote, useMissingQuotesRefresh } from './quote-experience';
import type { RequestFn } from '../common';
const wrap = (child: React.ReactNode) => <QueryClientProvider client={new QueryClient({defaultOptions:{queries:{retry:false},mutations:{retry:false}}})}>{child}</QueryClientProvider>;
const quote = {symbol:'000021.SZ',source:'BAOSTOCK',adjustment:'none',asOf:'2026-09-07',fetchedAt:'2026-09-08T00:00:00Z',stale:false,supported:true,bars:[{timestamp:1788710400000,open:20,high:22,low:19,close:21.35,volume:100,turnover:2135}]};
it('shows an unadjusted reference quote before any holding exists and never autofills a trade', async () => {
  const request=vi.fn(async()=>quote); const usePrice=vi.fn();
  render(wrap(<ReferenceQuote request={request as RequestFn} security={{id:5,name:'深科技',tsCode:'000021.SZ'}} onUsePrice={usePrice}/>));
  expect(await screen.findByText('¥21.35')).toBeInTheDocument();
  expect(screen.getByText(/2026.09.07/)).toBeInTheDocument();
  expect(usePrice).not.toHaveBeenCalled();
  await userEvent.click(screen.getByRole('button',{name:'填入参考价'}));
  expect(usePrice).toHaveBeenCalledWith('21.35');
  expect(request).toHaveBeenCalledWith('/api/securities/5/candles?adjust=none');
});
it('distinguishes unsupported stocks and allows retry after a quote error', async () => {
  let broken=true;
  render(wrap(<ReferenceQuote request={(async()=>{if(broken)throw new Error('offline');return {...quote,symbol:'920002.BJ',supported:false,bars:[]};}) as RequestFn} security={{id:5,name:'测试股票',tsCode:'920002.BJ'}}/>));
  await screen.findByText('暂时无法获取参考报价'); broken=false;
  await userEvent.click(screen.getByRole('button',{name:'重试报价'}));
  expect(await screen.findByText('当前行情源暂未覆盖这只股票')).toBeInTheDocument();
  expect(screen.queryByRole('button',{name:'填入参考价'})).not.toBeInTheDocument();
});
it('contains an incomplete quote response without crashing the investment form', async()=>{
  render(wrap(<ReferenceQuote request={(async()=>({items:[]})) as RequestFn} security={{id:5,name:'深科技',tsCode:'000021.SZ'}}/>));
  expect(await screen.findByText('暂时无法获取参考报价')).toBeInTheDocument();
  expect(screen.queryByRole('button',{name:'填入参考价'})).not.toBeInTheDocument();
});
it('automatically refreshes a missing held quote once and never replays money writes', async()=>{
  const request=vi.fn(async()=>({state:'ERROR',refreshed:0,error:'MARKET_UPSTREAM_UNAVAILABLE',quotes:[]}));
  function Check(){const result=useMissingQuotesRefresh(request as RequestFn,true,[{securityId:5,tsCode:'000021.SZ',quantity:100,price:null}]);return <span>{result.error ? '报价更新失败' : '等待'}</span>;}
  render(wrap(<Check/>));
  await screen.findByText('报价更新失败');
  expect(request).toHaveBeenCalledTimes(1);
  expect(request).toHaveBeenCalledWith('/api/market-quotes/refresh',{method:'POST'});
});
it('does not refresh for a member, a closed position or unsupported market', async()=>{
  const request=vi.fn();
  function Check(){useMissingQuotesRefresh(request as RequestFn,false,[{securityId:1,tsCode:'000021.SZ',quantity:100,price:null}]);useMissingQuotesRefresh(request as RequestFn,true,[{securityId:2,tsCode:'000001.SZ',quantity:0,price:null},{securityId:3,tsCode:'920002.BJ',quantity:100,price:null}]);return <span>就绪</span>;}
  render(wrap(<Check/>));await waitFor(()=>expect(screen.getByText('就绪')).toBeInTheDocument());expect(request).not.toHaveBeenCalled();
});

it('updates a missing holding price after refresh through the actual query cache', async()=>{
  let quoted=false;
  const request=vi.fn(async(path:string)=>{
    if(path==='/api/market-quotes/refresh'){quoted=true;return {state:'READY'};}
    if(path==='/api/portfolio')return [{securityId:5,tsCode:'000021.SZ',quantity:100,price:quoted?'21.35':null}];
    return [];
  });
  function Holding(){const query=useQuery({queryKey:['portfolio'],queryFn:()=>request('/api/portfolio')});const positions=query.data as Array<{securityId:number;tsCode:string;quantity:number;price:string|null}>|undefined;useMissingQuotesRefresh(request as RequestFn,true,positions);return <span>{positions?.[0]?.price??'等待报价'}</span>;}
  render(wrap(<Holding/>));
  expect(await screen.findByText('21.35')).toBeInTheDocument();
  expect(request.mock.calls.filter(([path])=>path==='/api/market-quotes/refresh')).toHaveLength(1);
});

it('shares a cooldown between automatic and manual refresh and defers a newly missing symbol', async()=>{
  vi.useFakeTimers();
  try {
    const request=vi.fn(async()=>({state:'READY',refreshed:1,error:null,quotes:[]}));
    const client=new QueryClient({defaultOptions:{queries:{retry:false},mutations:{retry:false}}});
    const wrapper=({children}:{children:React.ReactNode})=><QueryClientProvider client={client}>{children}</QueryClientProvider>;
    const {result,rerender}=renderHook(({id})=>useMissingQuotesRefresh(request as RequestFn,true,[{securityId:id,tsCode:'000021.SZ',quantity:100,price:null}]),{initialProps:{id:5},wrapper});
    await act(async()=>{await vi.advanceTimersByTimeAsync(1);});
    expect(request).toHaveBeenCalledTimes(1);
    rerender({id:6});
    act(()=>result.current.mutate());
    await act(async()=>{await vi.advanceTimersByTimeAsync(59_999);});
    expect(request).toHaveBeenCalledTimes(1);
    await act(async()=>{await vi.advanceTimersByTimeAsync(1_001);});
    expect(request).toHaveBeenCalledTimes(2);
    expect(result.current.error).toBeNull();
  } finally {vi.useRealTimers();}
});
