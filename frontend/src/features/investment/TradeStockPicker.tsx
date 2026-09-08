import {useEffect,useId,useRef,useState} from 'react';
import {useMutation,useQuery} from '@tanstack/react-query';
import Select from '@douyinfe/semi-ui/lib/es/select';
import type {Security} from '../../api/contracts';
import {StockPicker} from './StockPicker';
import {StockLabel} from './StockLabel';
import {useStockDropdown} from './useStockDropdown';
import {isOverseasInstrument,type OverseasInstrument,type OverseasSearch} from './overseas-market';
import {FormError,type RequestFn} from '../common';
export type SecuritySelection=Pick<Security,'id'|'tsCode'|'name'> & Partial<Security>;
export function securityCurrency(value?:SecuritySelection|null){return value?.currency??(value?.market==='HK'?'HKD':value?.market==='US'?'USD':'CNY');}
export function TradeStockPicker({request,value,onChange,disabled=false}:{request:RequestFn;value:SecuritySelection|null;onChange:(value:Security|null)=>void;disabled?:boolean}){
 const [market,setMarket]=useState<'CN'|'HK'|'US'>(()=>value?.market==='HK'||value?.market==='US'?value.market:'CN');
 useEffect(()=>{if(value)setMarket(value.market==='HK'||value.market==='US'?value.market:'CN');},[value?.id,value?.market]);
 return <div><nav className="market-switch" aria-label="投资市场">{([['CN','A 股'],['HK','港股'],['US','美股']] as const).map(([key,label])=><button type="button" key={key} disabled={disabled} aria-pressed={market===key} onClick={()=>{if(key!==market){setMarket(key);onChange(null);}}}>{label}</button>)}</nav>
  {market==='CN'?<StockPicker request={request} value={value} onChange={onChange} disabled={disabled}/>:<ForeignPicker key={market} market={market} request={request} value={value} onChange={onChange} disabled={disabled}/>}
 </div>;
}
function ForeignPicker({market,request,value,onChange,disabled}:{market:'HK'|'US';request:RequestFn;value:SecuritySelection|null;onChange:(value:Security)=>void;disabled:boolean}){
 const id=useId(),pickerId=`${id}-foreign`;const dropdown=useStockDropdown(pickerId);
 const [query,setQuery]=useState(''),[debounced,setDebounced]=useState('');const alive=useRef(true);
 useEffect(()=>{alive.current=true;return()=>{alive.current=false;};},[]);
 useEffect(()=>{const timer=setTimeout(()=>setDebounced(query.trim()),250);return()=>clearTimeout(timer);},[query]);
 const search=useQuery({queryKey:['overseas-search',market,debounced],queryFn:async({signal})=>{
  const result=await request<OverseasSearch>(`/api/overseas-market/search?market=${market}&q=${encodeURIComponent(debounced)}`,{signal});
  if(!result||!Array.isArray(result.items)||result.items.some(item=>!isOverseasInstrument(item,market)))throw new Error('股票目录响应不匹配，请重试');return result;
 },enabled:!disabled,retry:false,staleTime:60000});
 const resolve=useMutation({mutationFn:async(item:OverseasInstrument)=>{
  const result=await request<Security>('/api/securities/overseas/resolve',{method:'POST',body:{market,symbol:item.symbol}});
  if(!result?.id||result.market!==market||result.symbol!==item.symbol||result.currency!==item.currency)throw new Error('股票登记响应不匹配，请重试');return result;
 },onSuccess:result=>{if(alive.current)onChange(result);}});
 const label=(item:Pick<OverseasInstrument,'name'|'symbol'|'exchange'>)=><StockLabel name={item.name} code={item.symbol} exchange={item.exchange} accessibleLabel={`${item.symbol} · ${item.name}`}/>;
 const picked=value&&value.market===market&&value.symbol?{symbol:value.symbol,name:value.name,exchange:value.exchange??market,currency:value.currency??(market==='HK'?'HKD':'USD')}:null;
 const options=[...(search.data?.items??[])];if(picked&&!options.some(item=>item.symbol===picked.symbol))options.unshift({...picked,market,timezone:market==='HK'?'Asia/Hong_Kong':'America/New_York'});
 return <div className="stock-picker" id={pickerId} style={{position:'relative'}}>
  <span id={`${id}-label`} className="stock-picker__label">证券</span>
  <Select ref={dropdown.selectRef} aria-labelledby={`${id}-label`} aria-required filter remote onChangeWithObject className="stock-picker__select"
   value={picked?{value:picked.symbol,label:label(picked),instrument:picked}:undefined} disabled={disabled||resolve.isPending||(!search.data&&search.isFetching)}
   placeholder="搜索股票代码或名称，直接选择" loading={search.isFetching||resolve.isPending} onSearch={setQuery} onDropdownVisibleChange={dropdown.setMenuOpen}
   style={{width:'100%'}} dropdownMatchSelectWidth dropdownClassName="stock-picker-dropdown" dropdownStyle={{width:dropdown.controlWidth||'100%',minWidth:0}} rePosKey={dropdown.controlWidth}
   getPopupContainer={()=>document.getElementById(pickerId)!}
   optionList={options.map(item=>({value:item.symbol,label:label(item),instrument:item,disabled:item.currency!==(market==='HK'?'HKD':'USD')}))}
   onSelect={(_next,option)=>resolve.mutate(option.instrument as OverseasInstrument)} emptyContent="没有找到匹配股票"/>
  <FormError error={resolve.error||search.error}/>
  {search.error&&<button type="button" className="text-action" onClick={()=>void search.refetch()}>重试搜索</button>}
  {search.data?.state==='SYNCING'&&<p role="status">股票目录正在准备，请稍后重试。<button type="button" onClick={()=>void search.refetch()}>重试目录</button></p>}
 </div>;
}
