import { render,screen,waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient,QueryClientProvider } from '@tanstack/react-query';
import { TradeStockPicker } from './TradeStockPicker';
import type { RequestFn } from '../common';
it('searches and selects a US stock in one control and resolves its server identity',async()=>{
 const changed=vi.fn();const instrument={symbol:'AAPL',name:'Apple',market:'US',currency:'USD',exchange:'NASDAQ',timezone:'America/New_York'};
 const request=vi.fn(async(path:string,options?:{method?:string})=>options?.method==='POST'?{...instrument,id:7,tsCode:'AAPL.NASDAQ.US',active:true,securityType:'STOCK'}:path.includes('overseas')?{items:[instrument],state:'READY',stale:false}:path.includes('catalog-status')?{count:5000,state:'READY'}:{items:[]});
 render(<QueryClientProvider client={new QueryClient({defaultOptions:{queries:{retry:false}}})}><TradeStockPicker request={request as RequestFn} value={null} onChange={changed}/></QueryClientProvider>);
 await userEvent.click(screen.getByRole('button',{name:'美股'}));
 const picker=await screen.findByRole('combobox',{name:'证券'});await waitFor(()=>expect(picker).toHaveAttribute('aria-disabled','false'));
 await userEvent.click(picker);await userEvent.click(await screen.findByRole('option',{name:/AAPL.*Apple/}));
 await waitFor(()=>expect(changed).toHaveBeenCalledWith(expect.objectContaining({id:7,currency:'USD',market:'US'})));
});
