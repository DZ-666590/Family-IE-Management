import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within, act } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { LoansPage } from './LoansPage';
import { ApiError } from '../../api/client';
import type { RequestFn } from '../common';

const loan = { id: 4, name: '双策略测试', type: 'OTHER', fundingMode: 'OPENING', accountingOn: '2026-01-01', accountingInitialized: true, paymentAccountId: 1, currentPrincipal: '1200.00', principal: '1200.00', scheduledRepaymentTotal: '1200.00', remainingRepaymentTotal: '1200.00', paidRepaymentTotal: '0.00', annualRate: '0', termMonths: 12, repaymentMethod: 'EQUAL_PAYMENT', startOn: '2025-12-31', status: 'ACTIVE' };
const page = <T,>(items: T[]) => ({ items, page: 0, size: 50, totalElements: items.length, totalPages: items.length ? 1 : 0, hasNext: false });
const accounts = [{ id: 1, name: '日常账户', openingConfirmed: true, availableBalance: '500.00' }, { id: 2, name: '还款账户', openingConfirmed: true, availableBalance: '400.00' }];
function quote(path: string, token = 'original-token') {
 const params = new URLSearchParams(path.split('?')[1]);const strategy = params.get('strategy') ?? 'REDUCE_PAYMENT';const term = strategy === 'REDUCE_TERM';
 const summary = (after: boolean) => ({ principalAmount: after ? '900.00' : '1200.00', periodCount: after && term ? 9 : 12, maturityOn: after && term ? '2026-09-30' : '2026-12-31', nextPaymentOn: '2026-01-31', nextPaymentAmount: after && !term ? '75.00' : '100.00', totalInterest: '0.00', repaymentTotal: after ? '900.00' : '1200.00', schedule: [{ installmentNo: 1, dueOn: '2026-01-31', principal: after && !term ? '75.00' : '100.00', interest: '0.00', paymentAmount: after && !term ? '75.00' : '100.00', remainingPrincipal: after && !term ? '825.00' : '800.00' }] });
 return { dueInstallments: [], duePrincipalAmount: '0.00', dueInterestAmount: '0.00', additionalPrincipal: params.get('additionalPrincipal'), totalPrincipalAmount: params.get('additionalPrincipal'), totalInterestAmount: '0.00', totalCashAmount: params.get('additionalPrincipal'), balanceAfter: '200.00', policy: { minimumInstallmentAmount: null, sourceNote: null, revision: 0 }, termOptions: [], targetPeriods: null, strategy, paymentAccountId: Number(params.get('paymentAccountId')), paidOn: params.get('paidOn'), availableBalance: '500.00', planToken: token, before: summary(false), after: summary(true) };
}
function other(path: string) { if (path.endsWith('/repayment-policy')) return { minimumInstallmentAmount: null, sourceNote: null, revision: 0 }; if (path.includes('/term-options?')) return { remainingPrincipal: '1200.00', duePrincipal: '0.00', dueInterest: '0.00', options: [], policy: { minimumInstallmentAmount: null, sourceNote: null, revision: 0 } }; return path === '/api/loans/4' ? loan : path.startsWith('/api/loans?') ? page([loan]) : path.startsWith('/api/accounts?') ? page(accounts) : path === '/api/members' || path.endsWith('/prepayments') ? [] : page([]); }
async function mount(request: RequestFn) { const user=userEvent.setup();render(<QueryClientProvider client={new QueryClient({ defaultOptions:{ queries:{ retry:false } } })}><LoansPage role="OWNER" request={request} /></QueryClientProvider>);await user.click(await screen.findByRole('button',{name:'提前还款'}));return user; }

it('compares both strategies and replays the frozen committed request after preview refresh fails',async()=>{
 const writes: Record<string,unknown>[]=[];let committed=false;let previewReads=0;
 const request:RequestFn=async <T,>(path:string,options?:Parameters<RequestFn>[1])=>{
  if(options?.method==='POST'){writes.push(structuredClone(options.body as Record<string,unknown>));if(!committed){committed=true;throw new TypeError('提前还款响应丢失');}return {id:5} as T;}
  if(path.includes('/repayment-preview?')){previewReads++;if(committed)throw new ApiError('计划已变化，请重新预览',{status:409,code:'LOAN_PLAN_CHANGED'});return quote(path) as T;}return other(path) as T;
 };
 const user=await mount(request);const drawer=await screen.findByRole('dialog',{name:'双策略测试 · 提前还款'});
 await user.type(within(drawer).getByLabelText('额外提前偿还本金'),'300.00');await user.click(within(drawer).getByRole('radio',{name:/按原付款上限缩期/}));
 await waitFor(()=>expect(within(drawer).getByRole('button',{name:'确认还款 ¥300.00'})).toBeEnabled());
 expect(within(drawer).getByRole('table',{name:'提前还款前后对比'})).toHaveTextContent('9 期');expect(within(drawer).getByRole('table',{name:'提前还款前后对比'})).toHaveTextContent('2026.09.30');
 await user.click(within(drawer).getByRole('button',{name:'确认还款 ¥300.00'}));await screen.findByText('提前还款响应丢失');const reads=previewReads;
 await user.click(within(drawer).getByRole('button',{name:'重新预览还款计划'}));await waitFor(()=>expect(previewReads).toBeGreaterThan(reads));await screen.findByText('计划已变化，请重新预览');
 expect(within(drawer).getByLabelText('额外提前偿还本金')).toBeDisabled();const replay=within(drawer).getByRole('button',{name:'核对本次还款结果'});expect(replay).toBeEnabled();await user.click(replay);
 await screen.findByRole('dialog',{name:'双策略测试 · 还款计划'});expect(writes).toHaveLength(2);expect(writes[1]).toEqual(writes[0]);expect(writes[0]).toMatchObject({additionalPrincipal:'300.00',strategy:'REDUCE_TERM',planToken:'original-token',paymentAccountId:1});
});

it('ignores late previews for old inputs and requires a fresh confirmation after a rejected token',async()=>{
 let resolveOld:(value:unknown)=>void=()=>{};let currentReads=0;const writes:Record<string,unknown>[]=[];
 const request:RequestFn=async <T,>(path:string,options?:Parameters<RequestFn>[1])=>{
  if(options?.method==='POST'){writes.push(options.body as Record<string,unknown>);if(writes.length===1)throw new ApiError('请重新核对计划',{status:409,code:'LOAN_PLAN_CHANGED'});return {} as T;}
  if(path.includes('/repayment-preview?')){const p=new URLSearchParams(path.split('?')[1]);if(p.get('paymentAccountId')==='1')return new Promise(resolve=>{resolveOld=resolve as (value:unknown)=>void;});currentReads++;return quote(path,`fresh-${currentReads}`) as T;}return other(path) as T;
 };
 const user=await mount(request);await user.type(screen.getByLabelText('额外提前偿还本金'),'300.00');expect(screen.getByRole('button',{name:'确认还款'})).toBeDisabled();
 await user.selectOptions(screen.getByLabelText('本次付款账户'),'2');await waitFor(()=>expect(screen.getByRole('button',{name:'确认还款 ¥300.00'})).toBeEnabled());
 await act(async()=>resolveOld(quote('/preview?additionalPrincipal=300.00&strategy=REDUCE_TERM&paymentAccountId=1&paidOn=2026-01-01','stale-token')));
 expect(screen.getByRole('table',{name:'提前还款前后对比'})).toHaveTextContent('75.00');await user.click(screen.getByRole('button',{name:'确认还款 ¥300.00'}));await screen.findByText('请重新核对计划');
 await waitFor(()=>expect(currentReads).toBe(2));expect(writes).toHaveLength(1);await waitFor(()=>expect(screen.getByRole('button',{name:'确认还款 ¥300.00'})).toBeEnabled());await user.click(screen.getByRole('button',{name:'确认还款 ¥300.00'}));
 await waitFor(()=>expect(writes).toHaveLength(2));expect(writes[0]).toMatchObject({paymentAccountId:2,planToken:'fresh-1',strategy:'REDUCE_PAYMENT'});expect(writes[1]).toMatchObject({paymentAccountId:2,planToken:'fresh-2'});expect(writes[1].idempotencyKey).toBe(writes[0].idempotencyKey);
});

it('opens the updated current plan first and resets pagination when switching to retained history',async()=>{
 const queries:string[]=[];
 const request:RequestFn=async <T,>(path:string)=>{
  if(path.includes('/schedule?')){queries.push(path);const p=new URLSearchParams(path.split('?')[1]);const current=p.get('view')==='CURRENT';const pageIndex=Number(p.get('page'));const total=current?90:120;return {items:[{id:current?121+pageIndex*50:1+pageIndex*50,installmentNo:current?121+pageIndex*50:1+pageIndex*50,dueOn:current?'3999-01-31':'2026-01-31',principal:'10.00',interest:'0.00',status:current?'PENDING':'CANCELLED',confirmedTransactionId:null}],page:pageIndex,size:50,totalElements:total,totalPages:Math.ceil(total/50),hasNext:pageIndex<Math.ceil(total/50)-1} as T;}return other(path) as T;
 };
 const user=userEvent.setup();render(<QueryClientProvider client={new QueryClient({defaultOptions:{queries:{retry:false}}})}><LoansPage request={request} role="OWNER" /></QueryClientProvider>);
 await user.click(await screen.findByRole('button',{name:'查看计划'}));const drawer=await screen.findByRole('dialog',{name:'双策略测试 · 还款计划'});
 expect(await within(drawer).findByText('121')).toBeInTheDocument();expect(within(drawer).queryByText('已取消')).not.toBeInTheDocument();
 await user.selectOptions(within(drawer).getByLabelText('计划范围'),'HISTORY');expect(await within(drawer).findByText('已取消')).toBeInTheDocument();
 const pagination=within(drawer).getByRole('navigation',{name:'还款计划分页'});await user.click(within(pagination).getByRole('button',{name:'下一页'}));expect(await within(drawer).findByText('51')).toBeInTheDocument();
 await user.selectOptions(within(drawer).getByLabelText('计划范围'),'CURRENT');expect(await within(drawer).findByText('121')).toBeInTheDocument();
 expect(queries.some(q=>q.includes('page=1')&&q.includes('view=HISTORY'))).toBe(true);expect(queries.at(-1)).toContain('page=0');expect(queries.at(-1)).toContain('view=CURRENT');
});

it('defaults a closed loan to its paid and cancelled history',async()=>{
 const queries:string[]=[];const closed={...loan,status:'CLOSED',currentPrincipal:'0.00'};
 const request:RequestFn=async <T,>(path:string)=>{if(path.includes('/schedule?')){queries.push(path);return page([{id:1,installmentNo:1,dueOn:'2026-01-31',principal:'100.00',interest:'0.00',status:'PAID',paidOn:'2026-01-31',cashAmount:'100.00',confirmedTransactionId:8}]) as T;}return (path==='/api/loans/4'?closed:path.startsWith('/api/loans?')?page([closed]):other(path)) as T;};
 const user=userEvent.setup();render(<QueryClientProvider client={new QueryClient({defaultOptions:{queries:{retry:false}}})}><LoansPage request={request} role="OWNER" /></QueryClientProvider>);await user.click(await screen.findByRole('button',{name:'查看计划'}));
 expect(await screen.findByLabelText('计划范围')).toHaveValue('HISTORY');expect(queries.at(-1)).toContain('view=HISTORY');expect(await screen.findByText('已记录还款')).toBeInTheDocument();
});
