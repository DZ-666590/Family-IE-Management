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
        <div className="investment-summary__label">组合市值<MetricHelp label="市值口径">按当前可用价格计算；缺价持仓不计入市值。</MetricHelp></div>
        <strong>{money(totals?.marketValue)}</strong>
      </div>
      <div>
        <div className="investment-summary__label">累计收益<MetricHelp label="收益口径">累计收益包括已实现与浮动收益。</MetricHelp></div>
        <strong className={totals && Number(totals.totalProfit) >= 0 ? 'positive' : 'negative'}>{money(totals?.totalProfit)}</strong>
      </div>
    </div>
    {unpriced > 0 && <div className="portfolio-warning" role="status">
      <CircleAlert size={17} aria-hidden="true"/>
      <span>{unpriced} 项持仓缺少价格，暂未计入市值。</span>
      <button type="button" onClick={onViewQuotes}>查看行情</button>
    </div>}
  </>;
}
