import { act,fireEvent,render,screen,waitFor,within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient,QueryClientProvider } from '@tanstack/react-query';
import { TradeStockPicker } from './TradeStockPicker';
import type { RequestFn } from '../common';
it('searches and selects a US stock in one control and resolves its server identity',async()=>{
 const changed=vi.fn();const instrument={symbol:'AAPL',name:'Apple',market:'US',currency:'USD',exchange:'NASDAQ',timezone:'America/New_York'};
 const request=vi.fn(async(path:string,options?:{method?:string})=>options?.method==='POST'?{...instrument,id:7,tsCode:'AAPL.NASDAQ.US',active:true,securityType:'STOCK'}:path.includes('overseas')?{items:[instrument],state:'READY',stale:false,hasNext:false}:path.includes('catalog-status')?{count:5000,state:'READY'}:{items:[]});
 render(<QueryClientProvider client={new QueryClient({defaultOptions:{queries:{retry:false}}})}><TradeStockPicker request={request as RequestFn} value={null} onChange={changed}/></QueryClientProvider>);
 await userEvent.click(screen.getByRole('button',{name:'美股'}));
 const picker=await screen.findByRole('combobox',{name:'证券'});await waitFor(()=>expect(picker).toHaveAttribute('aria-disabled','false'));
 await userEvent.click(picker);await userEvent.click(await screen.findByRole('option',{name:/AAPL.*Apple/}));
 await waitFor(()=>expect(changed).toHaveBeenCalledWith(expect.objectContaining({id:7,currency:'USD',market:'US'})));
});

it('keeps the same focused input editable while a remote keyword request is pending',async()=>{
 let finish!: (value:unknown)=>void;
 const pending=new Promise(resolve=>{finish=resolve;});
 const request=async(path:string)=>path.includes('q=18')?pending:path.includes('overseas')?{items:[],state:'READY',stale:false,hasNext:false}:path.includes('catalog-status')?{count:1,state:'READY'}:{items:[]};
 render(<QueryClientProvider client={new QueryClient({defaultOptions:{queries:{retry:false}}})}><TradeStockPicker request={request as RequestFn} value={null} onChange={()=>{}}/></QueryClientProvider>);
 const user=userEvent.setup();await user.click(screen.getByRole('button',{name:'港股'}));
 const control=screen.getByRole('combobox',{name:'证券'});await waitFor(()=>expect(control).toHaveAttribute('aria-disabled','false'));
 await user.click(control);const input=within(control).getByRole('textbox');await user.type(input,'18');
 await new Promise(resolve=>setTimeout(resolve,350));
 expect(input).not.toBeDisabled();expect(input).toHaveFocus();
 await user.type(input,'10');expect(input).toHaveValue('1810');
 await act(async()=>finish({items:[],state:'READY',stale:false,hasNext:false}));
 expect(within(control).getByRole('textbox')).toBe(input);expect(input).toHaveValue('1810');
});

it('finds Xiaomi in HK from the default all-market search without submitting IME fragments',async()=>{
 const instrument={symbol:'01810',name:'小米集團－Ｗ',market:'HK',currency:'HKD',exchange:'HKEX',timezone:'Asia/Hong_Kong'};
 const changed=vi.fn();const paths:string[]=[];
 const request=async(path:string,options?:{method?:string})=>{
  paths.push(path);if(options?.method==='POST')return {...instrument,id:81,tsCode:'01810.HK'};
  if(path.includes('overseas'))return {items:path.includes('market=HK')&&decodeURIComponent(path).includes('q=小米')?[instrument]:[],state:'READY',stale:false,hasNext:false};
  return path.includes('catalog-status')?{count:1,state:'READY'}:{items:[]};
 };
 render(<QueryClientProvider client={new QueryClient({defaultOptions:{queries:{retry:false}}})}><TradeStockPicker request={request as RequestFn} value={null} onChange={changed}/></QueryClientProvider>);
 const user=userEvent.setup();const control=screen.getByRole('combobox',{name:'证券'});
 await waitFor(()=>expect(control).toHaveAttribute('aria-disabled','false'));await user.click(control);const input=within(control).getByRole('textbox');
 fireEvent.compositionStart(input);fireEvent.change(input,{target:{value:'xiaom'}});
 await act(async()=>{await new Promise(resolve=>setTimeout(resolve,350));});
 expect(paths.some(path=>path.includes('q=xiaom'))).toBe(false);
 fireEvent.change(input,{target:{value:'小米'}});fireEvent.compositionEnd(input,{data:'小米'});
 await user.click(await screen.findByRole('option',{name:/01810.*小米/}));
 await waitFor(()=>expect(changed).toHaveBeenCalledWith(expect.objectContaining({id:81,market:'HK'})));
});

it('keeps a keyword across market switches and still finds HK stocks when the US source fails',async()=>{
 const instrument={symbol:'01810',name:'小米集團－Ｗ',market:'HK',currency:'HKD',exchange:'HKEX',timezone:'Asia/Hong_Kong'};
 const request=async(path:string)=>{
  if(path.includes('market=US'))throw new Error('upstream unavailable');
  if(path.includes('overseas'))return {items:decodeURIComponent(path).includes('q=小米')?[instrument]:[],state:'READY',stale:false,hasNext:false};
  return {items:[]};
 };
 const user=userEvent.setup();
 render(<QueryClientProvider client={new QueryClient({defaultOptions:{queries:{retry:false}}})}><TradeStockPicker request={request as RequestFn} value={null} onChange={()=>{}}/></QueryClientProvider>);
 const control=screen.getByRole('combobox',{name:'证券'});await user.click(control);
 const input=within(control).getByRole('textbox');await user.type(input,'小米');
 expect(await screen.findByRole('option',{name:/01810.*小米/})).toBeInTheDocument();
 await user.click(screen.getByRole('button',{name:'港股'}));await user.click(control);
 expect(within(control).getByRole('textbox')).toHaveValue('小米');
 expect(await screen.findByRole('option',{name:/01810.*小米/})).toBeInTheDocument();
});

it('explains unavailable catalogs instead of silently claiming no stocks match',async()=>{
 const request=async(path:string)=>path.includes('catalog-status')?{count:0,state:'DISABLED'}:path.includes('overseas')?{items:[],state:'ERROR',stale:false,hasNext:false,error:'unavailable'}:{items:[]};
 render(<QueryClientProvider client={new QueryClient({defaultOptions:{queries:{retry:false}}})}><TradeStockPicker request={request as RequestFn} value={null} onChange={()=>{}}/></QueryClientProvider>);
 expect(await screen.findByRole('button',{name:'重试港股'})).toBeInTheDocument();
 expect(await screen.findByRole('button',{name:'重试A股目录'})).toBeInTheDocument();
 expect(screen.getByRole('combobox',{name:'证券'})).toHaveAttribute('aria-disabled','false');
});

it('shows stale HK directory results but blocks selection until the directory is fresh',async()=>{
 const item={symbol:'01810',name:'小米集團－Ｗ',market:'HK',currency:'HKD',exchange:'HKEX',timezone:'Asia/Hong_Kong'};
 const request=async(path:string)=>path.includes('catalog-status')?{count:1,state:'READY'}:path.includes('overseas')?{items:path.includes('market=HK')?[item]:[],state:'READY',stale:true,hasNext:false}:{items:[]};
 render(<QueryClientProvider client={new QueryClient({defaultOptions:{queries:{retry:false}}})}><TradeStockPicker request={request as RequestFn} value={null} onChange={()=>{}}/></QueryClientProvider>);
 await userEvent.click(screen.getByRole('combobox',{name:'证券'}));
 expect(await screen.findByRole('option',{name:/01810.*小米/})).toHaveAttribute('aria-disabled','true');
 expect(screen.getByRole('button',{name:'重试港股'})).toBeInTheDocument();
});

it('does not show a catalog failure for a disabled existing security',()=>{
 render(<QueryClientProvider client={new QueryClient()}><TradeStockPicker request={async()=>{throw new Error('Disabled picker must not fetch');}} disabled value={{id:1,tsCode:'000001.SZ',name:'平安银行'}} onChange={()=>{}}/></QueryClientProvider>);
 expect(screen.queryByRole('button',{name:'重试A股目录'})).not.toBeInTheDocument();
});
