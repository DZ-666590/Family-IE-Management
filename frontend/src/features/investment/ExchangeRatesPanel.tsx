import { useState } from 'react';
import { useMutation,useQuery,useQueryClient } from '@tanstack/react-query';
import { RefreshCw } from 'lucide-react';
import { businessDate } from '../../shared/runtime';
import { DataPanel,FormError,QueryState,type RequestFn } from '../common';
import './exchange-rates.scss';

interface RateRow{currency:string;cnyPerUnit?:string|null;effectiveOn?:string|null;fetchedAt?:string|null;source?:string|null;state:string;batchId?:number|null}
interface RateTable{asOf:string;rows:RateRow[];refreshState:string}
const names:Record<string,string>={CNY:'人民币',HKD:'港币',USD:'美元'};
function displayRate(raw?:string|null){
  if(!raw||!/^\d+(\.\d+)?$/.test(raw))return '—';
  const [whole,fraction='']=raw.split('.');
  const value=BigInt(whole)*1000000n+BigInt(fraction.padEnd(6,'0').slice(0,6))+(Number(fraction[6]??0)>=5?1n:0n);
  return `${value/1000000n}.${String(value%1000000n).padStart(6,'0')}`;
}
export function ExchangeRatesPanel({request,manager}:{request:RequestFn;manager:boolean}){
  const [asOf,setAsOf]=useState(businessDate());
  const [days,setDays]=useState(30);
  const cache=useQueryClient();
  const table=useQuery({queryKey:['exchange-rates',asOf],queryFn:({signal})=>request<RateTable>(`/api/exchange-rates?asOf=${asOf}`,{signal}),enabled:Boolean(asOf)});
  const start=new Date(`${asOf||businessDate()}T00:00:00Z`);start.setUTCDate(start.getUTCDate()-days+1);
  const from=start.toISOString().slice(0,10)<'1999-01-01'?'1999-01-01':start.toISOString().slice(0,10);
  const history=useQuery({queryKey:['exchange-rate-history',from,asOf],queryFn:({signal})=>request<RateRow[]>(`/api/exchange-rates/history?from=${from}&to=${asOf}`,{signal}),enabled:Boolean(asOf)});
  const refresh=useMutation({mutationFn:(day:string)=>request<RateTable>(`/api/exchange-rates/refresh?asOf=${day}`,{method:'POST'}),onSuccess:(data,day)=>{
    cache.setQueryData(['exchange-rates',day],data);void cache.invalidateQueries({queryKey:['exchange-rate-history']});
  }});
  const state=table.data?.refreshState;
  const notices:Record<string,string>={FAILED:'更新失败，保留上次汇率。',THROTTLED:'更新较频繁，请一分钟后再试。',UPDATING:'汇率正在更新…',SUCCESS:'汇率已更新。'};
  return <DataPanel title="汇率" action={manager&&<button className="text-action" disabled={refresh.isPending||!asOf} onClick={()=>refresh.mutate(asOf)}><RefreshCw size={15} aria-hidden="true"/>{refresh.isPending?'正在更新…':'更新汇率'}</button>}>
    <div className="fx-toolbar"><label>汇率日期<input type="date" min="1999-01-01" max={businessDate()} value={asOf} onChange={e=>{setAsOf(e.target.value);refresh.reset();}}/></label><p>1 单位原币折合人民币 · 日参考价，非实际成交价</p></div>
    <FormError error={refresh.error}/>{state&&notices[state]&&<p role="status">{notices[state]}</p>}
    <QueryState loading={table.isLoading} error={table.error}>
      <div className="fx-table-wrap"><table className="fx-table" aria-label="人民币参考汇率"><thead><tr><th>原币</th><th>折合人民币（CNY）</th><th>数据日期</th><th>来源 / 状态</th></tr></thead><tbody>{table.data?.rows.map(row=><tr key={row.currency}>
        <td><strong>1 {row.currency}</strong><span>{names[row.currency]??row.currency}</span></td><td className="fx-rate" title={row.cnyPerUnit??undefined}>{displayRate(row.cnyPerUnit)}</td><td>{row.effectiveOn??'—'}</td>
        <td>{row.source==='IDENTITY'?'基准币种':row.state==='MISSING'?'暂无汇率':<>{row.source}<span>{row.state==='STALE'?'已过期 · 仅作估算':'参考汇率'}</span></>}</td>
      </tr>)}</tbody></table></div>
    </QueryState>
    <div className="fx-history-heading"><h3>历史汇率</h3><label>历史范围<select value={days} onChange={e=>setDays(Number(e.target.value))}><option value={30}>最近30天</option><option value={90}>最近90天</option></select></label></div>
    <QueryState loading={history.isLoading} error={history.error} empty={history.data?.length===0} emptyTitle="尚无历史快照" emptyDetail="更新所选日期的汇率后会保留记录。">
      <div className="fx-table-wrap"><table className="fx-table" aria-label="历史汇率"><thead><tr><th>日期</th><th>原币</th><th>折合人民币（CNY）</th><th>来源</th></tr></thead><tbody>{history.data?.map(row=><tr key={`${row.batchId}-${row.currency}`}><td>{row.effectiveOn}</td><td>1 {row.currency}</td><td>{displayRate(row.cnyPerUnit)}</td><td>{row.source}</td></tr>)}</tbody></table></div>
    </QueryState>
  </DataPanel>;
}
