import type { ReactNode } from 'react';
import { CircleAlert, CircleHelp } from 'lucide-react';
import type { Portfolio } from '../../api/contracts';
import { money } from '../common';

function MetricHelp({ label, children }: { label: string; children: ReactNode }) {
  return <details className="metric-help" onKeyDown={event => {
    if (event.key === 'Escape') {
      event.preventDefault();
      event.currentTarget.open = false;
      event.currentTarget.querySelector('summary')?.focus();
    }
  }}>
    <summary aria-label={label}><CircleHelp size={15} aria-hidden="true"/></summary>
    <div className="metric-help__content">{children}</div>
  </details>;
}

export function PortfolioSummary({ portfolio, onViewQuotes }: { portfolio?: Portfolio; onViewQuotes: () => void }) {
  const totals = portfolio?.totals;
  const unpriced = totals?.unpricedPositions ?? 0;
  return <>
    <div className="summary-strip investment-summary">
      <div>
        <div className="investment-summary__label">组合市值<MetricHelp label="市值口径">按有效行情计算。缺价持仓保留成本估算，组合市值与浮动收益暂未知。</MetricHelp></div>
        <strong>{money(totals?.marketValue)}</strong>{unpriced > 0 && <small>含成本估算的组合价值 {money(totals?.estimatedValue)}</small>}
      </div>
      <div>
        <div className="investment-summary__label">累计收益<MetricHelp label="收益口径">按人民币展示，使用历史买入成本；包含价格和汇率变化的影响。</MetricHelp></div>
        <strong className={totals && Number(totals.totalProfit) >= 0 ? 'positive' : 'negative'}>{money(totals?.totalProfit)}</strong>
      </div>
    </div>
    {portfolio?.positions.some(item=>item.currency!=='CNY'&&item.base&&(item.base.cost==null||item.base.realizedProfit==null))&&<div className="portfolio-warning" role="status"><span>缺少交易发生日汇率，人民币成本或收益待补充。原币记录已保留。</span><a href="/workspace/investments?tab=rates">补充历史汇率</a></div>}
    {Boolean(totals?.missingFxRates)&&<div className="portfolio-warning" role="status"><span>{totals?.missingFxRates} 项持仓缺少汇率，已折算小计 {money(totals?.knownEstimatedValue)}。</span><a href="/workspace/investments?tab=rates">补充汇率</a></div>}
    {unpriced > 0 && <div className="portfolio-warning" role="status">
      <CircleAlert size={17} aria-hidden="true"/>
      <span>{unpriced} 项持仓缺少价格，市值与浮动收益尚不完整。</span>
      <button type="button" onClick={onViewQuotes}>查看行情</button>
    </div>}
  </>;
}
