import {useState} from 'react';
import {useMutation,useQuery,useQueryClient} from '@tanstack/react-query';
import Modal from '@douyinfe/semi-ui/lib/es/modal';
import Button from '@douyinfe/semi-ui/lib/es/button';
import {CalendarClock,ArrowRight,Repeat2} from 'lucide-react';
import type {Account,InvestmentAccount,Membership,Page} from '../../api/contracts';
import {DateField} from '../../shared/DateField';
import {businessDate,newIdempotencyKey} from '../../shared/runtime';
import {readAllPages} from '../../shared/pagination';
import {PaymentPreview,cents,tradeCash,unitPrice,useFundsRefresh} from '../accounting';
import {ConfirmDialog,FormError,QueryState,StatusTag,dateText,money,type RequestFn} from '../common';
import {TradeStockPicker,securityCurrency,type SecuritySelection} from './TradeStockPicker';
import {useInvestmentPlans,frequencyLabel,type InvestmentPlan,type InvestmentPlanOccurrence,type PlanFrequency} from './investment-plans';
import './investment-plans.scss';

type Props={request:RequestFn;manager:boolean;accounts:InvestmentAccount[];cashAccounts:Account[]};
export function InvestmentPlansPanel({request,manager,accounts,cashAccounts}:Props){
 const [planPage,setPlanPage]=useState(0),[occurrencePage,setOccurrencePage]=useState(0);
 const query=useInvestmentPlans(request,planPage,occurrencePage),cache=useQueryClient();
 const [editor,setEditor]=useState<InvestmentPlan|'new'|null>(null);
 const [payment,setPayment]=useState<InvestmentPlanOccurrence|null>(null);
 const [skip,setSkip]=useState<InvestmentPlanOccurrence|null>(null),[reason,setReason]=useState('');
 const [ending,setEnding]=useState<InvestmentPlan|null>(null),[notice,setNotice]=useState('');
 const refresh=async()=>{await Promise.all(['investment-plans','notifications','accounts','portfolio','investment-trades','dashboard','net-worth'].map(key=>cache.invalidateQueries({queryKey:[key]})));};
 const action=useMutation({mutationFn:({path,body}:{path:string;body:unknown})=>request(path,{method:'POST',body,headers:{'Idempotency-Key':newIdempotencyKey()}}),onSuccess:async()=>{setSkip(null);setEnding(null);await refresh();setNotice('已更新，未确认的实际成交不会自动记账。');}});
 const pending=query.data?.occurrences?.filter(o=>o.state==='PENDING')??[];
 const history=query.data?.occurrences?.filter(o=>o.state!=='PENDING')??[];
 return <section className="investment-plans" aria-label="定投计划">
  <header className="investment-plans-heading"><div><h2>让计划有节奏</h2><p>到期提醒，成交后由你确认。</p></div>{manager&&<Button theme="solid" onClick={()=>setEditor('new')}>新建定投计划</Button>}</header>
  {notice&&<p role="status">{notice}</p>}<FormError error={action.error}/>
  <QueryState loading={query.isLoading} error={query.error} empty={false}>
   <div className="plan-section-heading"><h3>待确认</h3><span>{query.data?.pendingCount??pending.length} 期</span></div>
   {!pending.length?<div className="plan-quiet"><CalendarClock size={24} aria-hidden="true"/><span>当前页没有待确认定投，到期后会在提醒中心通知负责人。</span></div>:pending.map(item=><article className="plan-due" key={item.id}>
    <div><span className="plan-symbol">{item.symbol} · {dateText(item.dueOn)}</span><h4>{item.securityName}</h4><p>{item.planName} · {item.accountName}</p>{item.remindAt&&new Date(item.remindAt)>new Date()&&<p>下次提醒 {new Date(item.remindAt).toLocaleString('zh-CN')}</p>}</div>
    <div className="plan-due-amount"><span>本期计划</span><strong>{money(item.amount,item.currency)}</strong></div>
    {manager&&<div className="plan-due-actions"><Button theme="solid" disabled={action.isPending} onClick={()=>setPayment(item)}>确认已成交</Button><details><summary>更多操作</summary><div><button disabled={action.isPending} onClick={()=>{action.reset();setReason('');setSkip(item);}}>跳过本期</button><button disabled={action.isPending} onClick={()=>action.mutate({path:`/api/investment-plans/occurrences/${item.id}/snooze`,body:{option:'TWO_HOURS'}})}>两小时后提醒</button><button disabled={action.isPending} onClick={()=>action.mutate({path:`/api/investment-plans/occurrences/${item.id}/snooze`,body:{option:'TOMORROW'}})}>明天提醒</button></div></details></div>}
   </article>)}
   <div className="plan-section-heading"><h3>我的计划</h3><span>金额是目标，不是成交结果</span></div>
   {!query.data?.plans?.length?<div className="plan-quiet"><Repeat2 size={24} aria-hidden="true"/><span>选择证券、金额和周期，开始第一个提醒计划。</span></div>:<div className="plan-cards">{query.data.plans.map(plan=><article key={plan.id}>
    <header><span className="plan-symbol">{plan.symbol}</span><StatusTag tone={plan.state==='ACTIVE'?'blue':'neutral'}>{plan.state==='ACTIVE'?'进行中':plan.state==='PAUSED'?'已暂停':'已结束'}</StatusTag></header><h3>{plan.name}</h3><p>{plan.securityName} · {plan.accountName}</p><div className="plan-card-amount"><strong>{money(plan.amount,plan.currency)}</strong><span> / {frequencyLabel[plan.frequency]}</span></div><p>下次提醒 {plan.state==='ACTIVE'?dateText(plan.nextDueOn):'—'}</p>
    {manager&&plan.state!=='ENDED'&&<footer><button onClick={()=>setEditor(plan)}>编辑计划</button><button disabled={action.isPending} onClick={()=>action.mutate({path:`/api/investment-plans/${plan.id}/state`,body:{state:plan.state==='ACTIVE'?'PAUSED':'ACTIVE'}})}>{plan.state==='ACTIVE'?'暂停':'恢复'}</button><button disabled={action.isPending} onClick={()=>{action.reset();setEnding(plan);}}>结束</button></footer>}
   </article>)}</div>}
   <div className="plan-pagination"><button disabled={planPage===0} onClick={()=>setPlanPage(p=>p-1)}>上一页计划</button><button disabled={!query.data?.hasMorePlans} onClick={()=>setPlanPage(p=>p+1)}>下一页计划</button></div>
   <div className="plan-section-heading"><h3>执行记录</h3><span>与真实成交关联</span></div>
   {history.length?<div className="responsive-data"><table><thead><tr><th>提醒日期</th><th>证券</th><th>计划金额</th><th>确认时金额</th><th>结果</th></tr></thead><tbody>{history.map(item=><tr key={item.id}><td>{dateText(item.dueOn)}</td><td>{item.securityName}</td><td>{money(item.amount,item.currency)}</td><td>{money(item.actualAmount,item.currency)}</td><td><OccurrenceResult item={item}/></td></tr>)}</tbody></table></div>:<p className="plan-quiet">当前页没有已处理记录。</p>}
   <div className="plan-pagination"><button disabled={occurrencePage===0} onClick={()=>setOccurrencePage(p=>p-1)}>上一页期次</button><button disabled={!query.data?.hasMoreOccurrences} onClick={()=>setOccurrencePage(p=>p+1)}>下一页期次</button></div>
  </QueryState>
  {editor&&<PlanEditor request={request} plan={editor==='new'?undefined:editor} accounts={accounts} cashAccounts={cashAccounts} onClose={()=>setEditor(null)} onSaved={async()=>{setEditor(null);setNotice('提醒计划已保存；不会自动买入或扣款。');await refresh();}}/>}
  {payment&&<PlanConfirmation request={request} occurrence={payment} accounts={accounts} cashAccounts={cashAccounts} onClose={()=>setPayment(null)} onSaved={async()=>{setPayment(null);setNotice('实际成交已记账。');await refresh();}}/>}
  <ConfirmDialog open={Boolean(ending)} title="结束定投计划" detail={<><p>结束后不再生成新期次，已有待确认事项与成交历史仍保留。</p><FormError error={action.error}/></>} confirmLabel="结束计划" loading={action.isPending} onClose={()=>setEnding(null)} onConfirm={()=>ending&&action.mutate({path:`/api/investment-plans/${ending.id}/state`,body:{state:'ENDED'}})}/>
  {skip&&<Modal visible title="跳过本期定投" footer={null} maskClosable={false} onCancel={()=>{if(!action.isPending)setSkip(null);}} className="plan-modal"><form className="feature-form" onSubmit={e=>{e.preventDefault();action.mutate({path:`/api/investment-plans/occurrences/${skip.id}/skip`,body:{reason}});}}><p>只跳过 {dateText(skip.dueOn)} 这一期，不扣款，下一期照常提醒。</p><FormError error={action.error}/><label>原因（可选）<input maxLength={200} value={reason} onChange={e=>setReason(e.target.value)}/></label><Button htmlType="submit" theme="solid" loading={action.isPending}>确认跳过</Button></form></Modal>}
 </section>;
}

function PlanEditor({request,plan,accounts,cashAccounts,onClose,onSaved}:Omit<Props,'manager'>&{plan?:InvestmentPlan;onClose:()=>void;onSaved:()=>Promise<void>}){
 const [key]=useState(newIdempotencyKey);
 const [security,setSecurity]=useState<SecuritySelection|null>(plan?{id:plan.securityId,name:plan.securityName,tsCode:plan.symbol,currency:plan.currency,symbol:plan.symbol,market:plan.currency==='USD'?'US':plan.currency==='HKD'?'HK':'SH'}:null);
 const [name,setName]=useState(plan?.name??''),[amount,setAmount]=useState(plan?.amount??''),[frequency,setFrequency]=useState<PlanFrequency>(plan?.frequency??'MONTHLY');
 const [firstDueOn,setFirstDueOn]=useState(plan?.firstDueOn??businessDate()),[accountId,setAccountId]=useState(String(plan?.accountId??'')),[assignedUserId,setAssignedUserId]=useState(String(plan?.assignedUserId??''));
 const members=useQuery({queryKey:['memberships','all-options'],queryFn:()=>readAllPages(p=>request<Page<Membership>>(`/api/family/memberships?page=${p}&size=50`,{responseType:'page'}))});
 const currency=securityCurrency(security),account=accounts.find(a=>String(a.id)===accountId),cash=cashAccounts.find(a=>a.id===account?.fundingAccountId);
 const valid=Boolean(security&&account?.currency===currency&&cash?.currency===currency&&cash.openingConfirmed&&cash.openingOn&&!cash.archivedAt&&cents(amount)!>0n&&firstDueOn&&assignedUserId);
 const save=useMutation({mutationFn:()=>request(plan?`/api/investment-plans/${plan.id}`:'/api/investment-plans',{method:plan?'PATCH':'POST',headers:{'Idempotency-Key':key},body:{name:name.trim()||`${security?.name}定投`,accountId:Number(accountId),securityId:security?.id,amount,frequency,firstDueOn,assignedUserId:Number(assignedUserId)}}),onSuccess:onSaved});
 return <Modal visible title={plan?'编辑定投计划':'创建定投计划'} width={900} footer={null} maskClosable={false} closeOnEsc={!save.isPending} onCancel={()=>{if(!save.isPending)onClose();}} className="plan-modal">
  <form onSubmit={e=>{e.preventDefault();if(valid)save.mutate();}}><div className="plan-dialog-grid"><fieldset disabled={save.isPending} className="feature-form plan-settings"><FormError error={save.error||members.error}/>
   <TradeStockPicker request={request} value={security} disabled={save.isPending} onChange={value=>{setSecurity(value);if(value&&account?.currency!==securityCurrency(value))setAccountId('');}}/>
   <label className="plan-amount-input">每期计划金额<div><input aria-label="每期计划金额" name="amount" required inputMode="decimal" value={amount} onChange={e=>setAmount(e.target.value)}/><span>{currency}</span></div></label>
   <fieldset className="plan-frequency"><legend>提醒周期</legend>{(Object.keys(frequencyLabel) as PlanFrequency[]).map(f=><button type="button" aria-pressed={frequency===f} key={f} onClick={()=>setFrequency(f)}>{frequencyLabel[f]}</button>)}</fieldset>
   <label>首次提醒日期<DateField name="firstDueOn" required min={plan?undefined:businessDate()} value={firstDueOn} onChange={e=>setFirstDueOn(e.target.value)}/></label>
   {frequency==='MONTHLY'&&Number(firstDueOn.slice(-2))>28&&<p>没有对应日期的月份，在当月最后一天提醒。</p>}
   <label>投资账户<select name="accountId" required value={accountId} onChange={e=>setAccountId(e.target.value)}><option value="">选择同币种投资账户</option>{accounts.filter(a=>a.status==='ACTIVE'&&a.currency===currency).map(a=><option key={a.id} value={a.id}>{a.name}</option>)}</select></label>
   {!accounts.some(a=>a.status==='ACTIVE'&&a.currency===currency)&&<p>请先<a href="/workspace/investments?tab=accounts">创建 {currency} 投资账户并关联现金账户</a>，然后再设置计划。</p>}
   <label>提醒负责人<select name="assignedUserId" required value={assignedUserId} onChange={e=>setAssignedUserId(e.target.value)}><option value="">选择家庭成员</option>{members.data?.filter(m=>m.status==='ACTIVE').map(m=><option value={m.userId} key={m.userId}>{m.displayName}</option>)}</select></label>
   <label>计划名称（可选）<input name="name" maxLength={80} placeholder={security?`${security.name}定投`:'例如：长期积累'} value={name} onChange={e=>setName(e.target.value)}/></label>
  </fieldset><aside className="plan-summary"><span className="plan-summary-label">计划摘要</span><h3>{security?.name??'选择一只证券'}</h3><strong className="plan-summary-amount">{money(amount,currency)}</strong><dl><div><dt>频率</dt><dd>{frequencyLabel[frequency]}</dd></div><div><dt>首次提醒</dt><dd>{dateText(firstDueOn)}</dd></div><div><dt>扣款现金账户</dt><dd>{cash?.name??'随投资账户关联'}</dd></div><div><dt>当前可用现金</dt><dd>{money(cash?.availableBalance,currency)}</dd></div></dl>
   <div className="plan-confirmation-path"><CalendarClock size={20}/><span>到期提醒</span><ArrowRight size={16}/><span>确认成交后记账</span></div>
   <p>使用投资账户关联的现金账户，币种必须一致。余额只在确认实际成交时扣减。</p>{account&&(!cash?.openingConfirmed||!cash?.openingOn||cash.archivedAt)&&<p role="alert">请先初始化关联现金账户，或在投资账户中更新资金来源。</p>}
   {plan&&<p>已有期次保持原计划，不会因编辑而重写。</p>}
  </aside></div><footer className="plan-modal-footer"><span>仅创建提醒，不会自动买入或扣款</span><Button htmlType="submit" theme="solid" disabled={!valid} loading={save.isPending}>{plan?'保存计划':'创建提醒计划'}</Button></footer></form>
 </Modal>;
}

function OccurrenceResult({item}:{item:InvestmentPlanOccurrence}){
 if(item.state==='SKIPPED')return <>已跳过{item.reason?' · '+item.reason:''}</>;
 if(item.tradeReversed)return <>原成交已撤销 · 交易 #{item.tradeId}<small>保留本期处理历史，不会再次自动扣款。</small></>;
 const current=item.currentTrade;
 return <>已记账 · 交易 #{item.tradeId}{current&&<small>现成交记录：{current.quantity} 股 × {money(current.price,item.currency)}，手续费 {money(current.fee,item.currency)} · {dateText(current.tradedOn)}</small>}</>;
}

function PlanConfirmation({request,occurrence,accounts,cashAccounts,onClose,onSaved}:Omit<Props,'manager'>&{occurrence:InvestmentPlanOccurrence;onClose:()=>void;onSaved:()=>Promise<void>}){
 const [key]=useState(newIdempotencyKey),[quantity,setQuantity]=useState(''),[price,setPrice]=useState(''),[fee,setFee]=useState('0'),[tradedOn,setTradedOn]=useState(businessDate()),[ack,setAck]=useState(false);
 const fundsError=useFundsRefresh(),cash=cashAccounts.find(a=>a.id===occurrence.fundingAccountId),account=accounts.find(a=>a.id===occurrence.accountId);
 const total=tradeCash(quantity,price,fee),balance=cents(cash?.availableBalance),payment=cents(total);
 const positivePrice=unitPrice(price);
 const valid=ack&&Boolean(quantity.replace(/[0.]/g,'').length>0&&positivePrice!==null&&positivePrice>0n&&account&&account.fundingAccountId===occurrence.fundingAccountId&&cash?.currency===occurrence.currency&&cash.openingConfirmed&&cash.openingOn&&!cash.archivedAt&&payment!==null&&payment>0n&&balance!==null&&balance>=payment);
 const save=useMutation({mutationFn:()=>request(`/api/investment-plans/occurrences/${occurrence.id}/confirm`,{method:'POST',headers:{'Idempotency-Key':key},body:{quantity,price,fee,tradedOn}}),onError:fundsError,onSuccess:onSaved});
 return <Modal visible title="确认本期实际成交" width={850} footer={null} maskClosable={false} closeOnEsc={!save.isPending} onCancel={()=>{if(!save.isPending)onClose();}} className="plan-modal"><form onSubmit={e=>{e.preventDefault();if(valid)save.mutate();}}>
  <div className="plan-dialog-grid"><fieldset className="feature-form plan-settings" disabled={save.isPending}><FormError error={save.error}/><div className="plan-trade-title"><span>{occurrence.symbol} · {dateText(occurrence.dueOn)}</span><h3>{occurrence.securityName}</h3><p>计划 {money(occurrence.amount,occurrence.currency)}；以实际成交为准。</p></div>
   <label>实际成交数量<input name="quantity" required inputMode="decimal" value={quantity} onChange={e=>setQuantity(e.target.value)}/></label>
   <label>实际成交单价<input name="price" required inputMode="decimal" value={price} onChange={e=>setPrice(e.target.value)}/></label>
   <label>手续费<input name="fee" required inputMode="decimal" value={fee} onChange={e=>setFee(e.target.value)}/></label>
   <label>成交日期<DateField name="tradedOn" required max={businessDate()} value={tradedOn} onChange={e=>setTradedOn(e.target.value)}/></label>
   <label className="plan-ack"><input type="checkbox" checked={ack} onChange={e=>setAck(e.target.checked)}/>我已在券商完成实际买入，以上是成交记录</label>
  </fieldset><aside className="plan-summary"><span className="plan-summary-label">本次记账</span><PaymentPreview account={cash} amount={total}/>{(!account||account.fundingAccountId!==occurrence.fundingAccountId)&&<p role="alert">投资账户已归档或资金关联已更改，请先核对账户设置；本期不会改用其他账户扣款。</p>}<p>只会登记这一期。其他未确认期次不会合并扣款。</p></aside></div>
  <footer className="plan-modal-footer"><span>实际扣款 {money(total,occurrence.currency)}</span><Button htmlType="submit" theme="solid" disabled={!valid} loading={save.isPending}>确认已成交并记账</Button></footer>
 </form></Modal>;
}
