import {useState,type ComponentProps} from 'react';
import Button from '@douyinfe/semi-ui/lib/es/button';
import Dropdown from '@douyinfe/semi-ui/lib/es/dropdown';
import {MoreHorizontal,CircleAlert} from 'lucide-react';
import './investment-ui.scss';

type ButtonProps=ComponentProps<typeof Button>&{variant?:'primary'|'secondary'|'quiet'|'danger'};
export function InvestmentButton({variant,theme,type,className='',...props}:ButtonProps){
 const intent=variant??(type==='danger'?'danger':theme==='solid'?'primary':'secondary');
 return <Button {...props} theme={intent==='primary'?'solid':intent==='quiet'?'borderless':'outline'} type={intent==='danger'?'danger':intent==='primary'?'primary':'tertiary'} className={`inv-button inv-button--${intent} ${className}`}/>;
}
export function InvestmentActions({label,triggerLabel,actions,disabled=false}:{disabled?:boolean;label:string;triggerLabel?:string;actions:Array<{label:string;onClick:()=>void;danger?:boolean}>}){
 const [open,setOpen]=useState(false);
 if(!actions.length)return null;
 return <Dropdown trigger="click" visible={open} onVisibleChange={setOpen} motion={false} position="bottomRight" contentClassName="investment-menu" menu={actions.map(action=>({node:'item',disabled,name:action.label,type:action.danger?'danger':undefined,onClick:()=>{setOpen(false);action.onClick();}}))}>
  <InvestmentButton disabled={disabled} variant="quiet" aria-label={label} aria-haspopup="menu" aria-expanded={open}>{triggerLabel??<MoreHorizontal size={18}/>}</InvestmentButton>
 </Dropdown>;
}
export function InvestmentValuationStatus({live,failed,partial,busy,onRefresh}:{live:boolean;failed:boolean;partial:boolean;busy:boolean;onRefresh:()=>void}){
 return <div className="investment-valuation">
  <div className="investment-valuation-line"><span>{live?'参考估值':'收盘估值'}</span><details><summary>计算口径</summary><p>常规交易时段约60秒刷新，公开行情可能延迟。总览和历史快照使用收盘口径，实际成交成本不随报价变化。</p></details>{!failed&&<InvestmentButton variant="quiet" size="small" disabled={busy} onClick={onRefresh}>{busy?'更新中…':'刷新报价'}</InvestmentButton>}</div>
  {(failed||partial)&&<div className="investment-data-issue" role="status"><CircleAlert size={17} aria-hidden="true"/><div><strong>{failed?'报价更新失败':'部分持仓不是即时价格'}</strong><p>{failed?'保留最近可用数据，请勿将其当作当前成交价。':'包含延迟、收盘或手工报价，详见持仓明细。'}</p></div>{failed&&<InvestmentButton variant="quiet" size="small" disabled={busy} onClick={onRefresh}>重试报价</InvestmentButton>}</div>}
 </div>;
}
