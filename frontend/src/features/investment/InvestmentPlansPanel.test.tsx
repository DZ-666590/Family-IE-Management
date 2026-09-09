import {QueryClient,QueryClientProvider} from '@tanstack/react-query';
import {render,screen,within,waitFor} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {InvestmentPlansPanel} from './InvestmentPlansPanel';
import type {RequestFn} from '../common';

vi.mock('./TradeStockPicker',()=>({TradeStockPicker:({onChange,disabled}:any)=><button type="button" disabled={disabled} onClick={()=>onChange({id:8,name:'阿里巴巴',tsCode:'BABA.US',market:'US',symbol:'BABA',currency:'USD'})}>选择阿里巴巴</button>,securityCurrency:(s:any)=>s?.currency??'CNY'}));
const plan={id:1,name:'长期积累',accountId:3,accountName:'美股账户',fundingAccountId:4,securityId:8,securityName:'阿里巴巴',symbol:'BABA',currency:'USD',amount:'100.00',frequency:'MONTHLY',firstDueOn:'2026-09-01',nextDueOn:'2026-10-01',assignedUserId:7,state:'ACTIVE'};
const occurrence={...plan,id:11,planId:1,planName:'长期积累',dueOn:'2026-09-01',state:'PENDING',remindAt:null,tradeId:null,actualAmount:null,reason:null};
const cash={id:4,name:'美元现金',currency:'USD',type:'BANK',balance:'500.00',availableBalance:'500.00',openingConfirmed:true,openingOn:'2026-01-01',archivedAt:null};
function setup(options:{pending?:boolean;manager?:boolean;fail?:boolean;reversed?:boolean}={}){
 const calls:Array<{path:string;options:any}>=[];
 const request=(async(path:string,opts:any)=>{
  calls.push({path,options:opts});
  if(opts?.method){if(options.fail)throw new Error('余额不足，未记账');return {};}
  if(path==='/api/investment-plans')return {plans:[plan],occurrences:options.pending===false?[]:[options.reversed?{...occurrence,state:'CONFIRMED',tradeId:44,actualAmount:'100.00',tradeReversed:true}:occurrence]};
  if(path.startsWith('/api/family/memberships'))return {items:[{id:1,userId:7,displayName:'叶凯文',status:'ACTIVE'}],page:0,size:50,totalElements:1,totalPages:1,hasNext:false};
  throw new Error('Unexpected '+path);
 }) as RequestFn;
 render(<QueryClientProvider client={new QueryClient({defaultOptions:{queries:{retry:false}}})}><InvestmentPlansPanel request={request} manager={options.manager??true} accounts={[{id:3,name:'美股账户',brokerName:'券商',currency:'USD',fundingAccountId:4,status:'ACTIVE',createdBy:7,archivedAt:null}]} cashAccounts={[cash as any]}/></QueryClientProvider>);
 return {calls,user:userEvent.setup()};
}
it('opening a pending occurrence never posts and requires actual execution acknowledgement',async()=>{
 const {calls,user}=setup();
 await user.click(await screen.findByRole('button',{name:'确认已成交'}));
 const dialog=screen.getByRole('dialog');
 expect(within(dialog).getByLabelText('实际成交单价')).toHaveValue('');
 expect(within(dialog).getByRole('button',{name:'确认已成交并记账'})).toBeDisabled();
 expect(calls.filter(c=>c.options?.method)).toHaveLength(0);
 await user.type(within(dialog).getByLabelText('实际成交数量'),'2');
 await user.type(within(dialog).getByLabelText('实际成交单价'),'50');
 await user.click(within(dialog).getByLabelText('我已在券商完成实际买入，以上是成交记录'));
 await user.click(within(dialog).getByRole('button',{name:'确认已成交并记账'}));
 await waitFor(()=>expect(calls.some(c=>c.path==='/api/investment-plans/occurrences/11/confirm')).toBe(true));
 expect(calls.find(c=>c.path.endsWith('/confirm'))!.options.body).toMatchObject({quantity:'2',price:'50',fee:'0'});
});
it('shows a failure without dismissing the confirmation or pretending it posted',async()=>{
 const {user}=setup({fail:true});
 await user.click(await screen.findByRole('button',{name:'确认已成交'}));
 const dialog=screen.getByRole('dialog');
 await user.type(within(dialog).getByLabelText('实际成交数量'),'2');
 await user.type(within(dialog).getByLabelText('实际成交单价'),'50');
 await user.click(within(dialog).getByLabelText('我已在券商完成实际买入，以上是成交记录'));
 await user.click(within(dialog).getByRole('button',{name:'确认已成交并记账'}));
 expect(await screen.findByText('余额不足，未记账')).toBeInTheDocument();
 expect(screen.getByRole('dialog')).toBeInTheDocument();
});
it('creates an amount and cycle plan only after showing its linked cash account',async()=>{
 const {calls,user}=setup({pending:false});
 await user.click(screen.getByRole('button',{name:'新建定投计划'}));
 const dialog=screen.getByRole('dialog');
 await user.click(within(dialog).getByRole('button',{name:'选择阿里巴巴'}));
 await user.type(within(dialog).getByLabelText('每期计划金额'),'100');
 await user.selectOptions(within(dialog).getByLabelText('投资账户'),'3');
 await user.selectOptions(within(dialog).getByLabelText('提醒负责人'),'7');
 expect(within(dialog).getByText('美元现金')).toBeInTheDocument();
 expect(within(dialog).getByText('仅创建提醒，不会自动买入或扣款')).toBeInTheDocument();
 await user.click(within(dialog).getByRole('button',{name:'创建提醒计划'}));
 await waitFor(()=>expect(calls.some(c=>c.path==='/api/investment-plans'&&c.options?.method==='POST')).toBe(true));
 expect(calls.find(c=>c.options?.method==='POST')!.options.body).toMatchObject({accountId:3,securityId:8,amount:'100',frequency:'MONTHLY',assignedUserId:7});
});
it('does not expose accounting or lifecycle mutations to read-only members',async()=>{
 setup({manager:false});
 expect(await screen.findByText('长期积累',{selector:'h3'})).toBeInTheDocument();
 expect(screen.queryByRole('button',{name:'新建定投计划'})).not.toBeInTheDocument();
 expect(screen.queryByRole('button',{name:'确认已成交'})).not.toBeInTheDocument();
});
it('keeps the date popup within the plan dialog and closes it before the dialog',async()=>{
 const {user}=setup();
 await user.click(await screen.findByRole('button',{name:'确认已成交'}));
 const dialog=screen.getByRole('dialog');
 await user.click(within(dialog).getByLabelText('成交日期'));
 expect((await within(dialog).findAllByRole('gridcell')).length).toBeGreaterThan(0);
 await user.keyboard('{Escape}');
 expect(within(dialog).queryAllByRole('gridcell')).toHaveLength(0);
 expect(screen.getByRole('dialog')).toBeInTheDocument();
});
it('shows reversed trades honestly without offering to confirm the same occurrence again',async()=>{
 setup({reversed:true});
 expect(await screen.findByText(/原成交已撤销/)).toBeInTheDocument();
 expect(screen.queryByRole('button',{name:'确认已成交'})).not.toBeInTheDocument();
});
it('skips only the selected occurrence without sending a trade confirmation',async()=>{
 const {user,calls}=setup();
 await user.click(await screen.findByText('更多操作'));
 await user.click(screen.getByRole('button',{name:'跳过本期'}));
 const dialog=screen.getByRole('dialog');
 await user.type(within(dialog).getByLabelText('原因（可选）'),'本期暂缓');
 await user.click(within(dialog).getByRole('button',{name:'确认跳过'}));
 await waitFor(()=>expect(calls.some(c=>c.path.endsWith('/11/skip'))).toBe(true));
 expect(calls.filter(c=>c.options?.method).map(c=>c.path)).toEqual(['/api/investment-plans/occurrences/11/skip']);
});
