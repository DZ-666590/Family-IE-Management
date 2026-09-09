import {act,render,screen} from '@testing-library/react';
import {QueryClient,QueryClientProvider} from '@tanstack/react-query';
import {useLivePortfolio,sameRecordedPositions} from './live-quotes';
import type {RequestFn} from '../common';
import type {Portfolio} from '../../api/contracts';

it('does not overlay stale holdings after a trade or a manual price correction',()=>{
 const value=(quantity:number,source='BAOSTOCK')=>({positions:[{accountId:1,securityId:1,quantity,cost:'10.00',realizedProfit:'0.00',source,price:'12.00',tradeDate:'2026-09-09'}],totals:{}} as Portfolio);
 expect(sameRecordedPositions(value(2),value(1,'TENCENT'))).toBe(false);
 expect(sameRecordedPositions(value(1,'MANUAL'),value(1,'TENCENT'))).toBe(false);
 expect(sameRecordedPositions(value(1),value(1,'TENCENT'))).toBe(true);
});

it('polls once per minute only while the page is visible and keeps the previous result',async()=>{
 vi.useFakeTimers();let visibility:DocumentVisibilityState='visible';
 const visible=vi.spyOn(document,'visibilityState','get').mockImplementation(()=>visibility);
 const response={portfolio:{positions:[],totals:{}},quotes:[],nextRefreshSeconds:60,partial:false};
 const request=vi.fn(async()=>response);
 function View(){const data=useLivePortfolio(request as RequestFn,true);return <span>{data.data?'loaded':'waiting'}</span>;}
 const client=new QueryClient({defaultOptions:{queries:{retry:false}}});
 const view=render(<QueryClientProvider client={client}><View/></QueryClientProvider>);
 try{
  await act(async()=>{await vi.advanceTimersByTimeAsync(1);});
  expect(request).toHaveBeenCalledTimes(1);
  await act(async()=>{await vi.advanceTimersByTimeAsync(60001);});
  expect(request).toHaveBeenCalledTimes(2);
  await act(async()=>{visibility='hidden';document.dispatchEvent(new Event('visibilitychange'));});
  await act(async()=>{await vi.advanceTimersByTimeAsync(120001);});
  expect(request).toHaveBeenCalledTimes(2);expect(screen.getByText('loaded')).toBeInTheDocument();
 }finally{view.unmount();client.clear();visible.mockRestore();vi.useRealTimers();}
});
