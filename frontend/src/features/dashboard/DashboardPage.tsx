import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { ArrowDownLeft, ArrowUpRight, ArrowRight, Wallet, Bell, TrendingUp } from 'lucide-react';
import type { Analysis, Dashboard, DebtAnalysis, HouseholdRole, NetWorth, NotificationPage, Portfolio, Transaction } from '../../api/contracts';
import { localYearMonth } from '../../shared/runtime';
import { DataPanel, PageScaffold, QueryState, StatusTag, dateText, money, type RequestFn } from '../common';
import { FlowChart, HistoryChart, historyBasisLabel, historyValuationLabel } from '../visuals';

export function DashboardPage({ request, role }: { request: RequestFn; role: HouseholdRole }) {
  const [month, setMonth] = useState(localYearMonth());
  const [detailsOpen, setDetailsOpen] = useState(false);
  const dashboard = useQuery({ queryKey: ['dashboard', month], queryFn: () => request<Dashboard>(`/api/dashboard?month=${month}&rollupCategories=true`) });
  const netWorth = useQuery({ queryKey: ['net-worth'], queryFn: () => request<NetWorth>('/api/net-worth') });
  const debt = useQuery({ queryKey: ['debt-analysis'], queryFn: () => request<DebtAnalysis>('/api/debt-analysis') });
  const portfolio = useQuery({ queryKey: ['portfolio'], queryFn: () => request<Portfolio>('/api/portfolio') });
  const notifications = useQuery({ queryKey: ['notifications'], queryFn: () => request<NotificationPage>('/api/notifications') });
  const recent = useQuery({ queryKey: ['transactions', 'recent', month], queryFn: () => request<Transaction[]>(`/api/transactions?month=${month}&page=0&size=5`) });
  const analysis = useQuery({ queryKey: ['analysis', month], queryFn: () => request<Analysis>(`/api/analysis?month=${month}&rollupCategories=true`), enabled: detailsOpen });
  return <PageScaffold title="家庭总览">
    <div className="overview-topline"><label className="date-control">收支月份<input aria-label="收支月份" type="month" value={month} onChange={e => { if(e.target.value) setMonth(e.target.value); }} /></label></div>
    <div className="overview-hero">
      <section className="wealth-panel" aria-label="当前家庭净资产">
        <div className="wealth-heading"><span className="muted"><Wallet size={17} aria-hidden="true"/>家庭净资产</span><span className="quiet-badge">当前</span></div>
        <QueryState loading={netWorth.isLoading} error={netWorth.error}>
          <strong className="wealth-value">{money(netWorth.data?.netWorth)}</strong>
          <div className="wealth-components"><span>总资产 <b>{money(netWorth.data?.asset)}</b></span><span>总负债 <b>{money(netWorth.data?.liability)}</b></span></div>
          <HistoryChart data={netWorth.data?.history ?? []} />{netWorth.data?.investment.missingPrice && <p className="source-note">含缺价持仓的成本估算；有效市值仍待补齐。</p>}<small>累计资产估值变动 {money(netWorth.data?.cumulativeAssetValuationChange)}，已包含于净资产，不重复加计。</small>
        </QueryState>
      </section>
      <div className="cash-summary">
        <div className="cash-summary-heading">{month} 收支</div>
        <CashMetric icon="income" label="收入" value={dashboard.data?.summary.income} loading={dashboard.isLoading} error={dashboard.error}/>
        <CashMetric icon="expense" label="费用" value={dashboard.data?.summary.expense} loading={dashboard.isLoading} error={dashboard.error}/>
        <div className="cash-balance"><span>本月收支差额</span><strong>{dashboard.error ? '暂不可用' : money(dashboard.data?.summary.balance)}</strong></div>
      </div>
    </div>
    <QueryState loading={dashboard.isLoading} error={dashboard.error}><div className="summary-strip overview-cash-summary"><div><span>现金流入</span><strong>{money(dashboard.data?.summary.cashIn)}</strong><small>不含期初及账户互转</small></div><div><span>现金流出</span><strong>{money(dashboard.data?.summary.cashOut)}</strong></div><div><span>偿还本金</span><strong>{money(dashboard.data?.summary.principalPaid)}</strong><small>不计入费用</small></div><div><span>新借入本金</span><strong>{money(dashboard.data?.summary.borrowed)}</strong><small>不计入收入</small></div></div></QueryState>
    <div className="overview-main-grid">
      <div className="overview-primary">
        <DataPanel title="收入与费用" meta={`${month} · 不含借入本金、还款本金、账户互转和资产购入`}><QueryState loading={dashboard.isLoading} error={dashboard.error} empty={!dashboard.data?.daily.length} emptyTitle="这个月还没有收支" emptyDetail="记下第一笔收支，开始了解家庭现金流。"><FlowChart points={dashboard.data?.daily.map(row=>({label:row.date.slice(8)+'日',income:row.income,expense:row.expense})) ?? []}/></QueryState></DataPanel>
        <DataPanel title="最近流水" meta="所选月份的最近 5 笔" action={<a className="panel-link" href="/workspace/transactions">全部流水<ArrowRight size={15} aria-hidden="true"/></a>}><QueryState loading={recent.isLoading} error={recent.error} empty={!recent.data?.length} emptyTitle="还没有流水" emptyDetail="从一笔日常开销开始。"><div className="recent-ledger">{recent.data?.map(item=><div key={item.id}><span className={`entry-icon ${item.kind}`}>{item.kind==='income'?<ArrowDownLeft size={19}/>:<ArrowUpRight size={19}/>}</span><div><strong>{item.categoryName}</strong><small>{item.memberName} · {item.accountName}</small></div><div><strong className={item.kind==='income'?'positive':''}>{item.kind==='income'?'+':'-'}{money(item.amount)}</strong><small>{dateText(item.occurredOn)}</small></div></div>)}</div></QueryState></DataPanel>
      </div>
      <div className="overview-secondary">
        <DataPanel title="费用预算执行" meta="当前月份" action={<a href="/workspace/budgets" className="panel-link">查看<ArrowRight size={15} aria-hidden="true"/></a>}>
          <QueryState loading={netWorth.isLoading} error={netWorth.error} empty={!netWorth.data?.budget.activeBudgetCount} emptyTitle="还没有设置预算" emptyDetail="设定额度，让每月花销有个参考。">
            <div className="budget-overview"><span>{netWorth.data?.budget.activeBudgetCount} 项活跃预算</span><strong>{netWorth.data?.budget.overLimitCount ? `${netWorth.data.budget.overLimitCount} 项已超支` : netWorth.data?.budget.nearLimitCount ? `${netWorth.data.budget.nearLimitCount} 项接近额度` : '预算状态正常'}</strong><p>已用 {money(netWorth.data?.budget.spent)} / 计划 {money(netWorth.data?.budget.planned)}</p><small>各范围预算可能重叠，详情按单项查看。</small></div>
          </QueryState>
        </DataPanel>
        <DataPanel title="近期提醒" action={<a href="/workspace/notifications" className="panel-link"><Bell size={15} aria-hidden="true"/>全部</a>}>
          <QueryState loading={notifications.isLoading} error={notifications.error}><div className="calm-reminders"><span>{notifications.data?.unreadCount ?? 0} 条未读</span>{notifications.data?.items.slice(0,3).map(item=><a href="/workspace/notifications" key={item.id}><i/><div><strong>{item.title}</strong><small>{dateText(item.dueAt)}</small></div></a>)}{!notifications.data?.items.length && <p>暂无提醒，今天也井然有序。</p>}</div></QueryState>
        </DataPanel>
        <div className="investment-glance"><TrendingUp size={18} aria-hidden="true"/><span>投资累计收益<strong>{portfolio.error?'暂不可用':money(portfolio.data?.totals.totalProfit)}</strong></span><a href="/workspace/investments" aria-label="查看投资持仓"><ArrowUpRight size={20}/></a><div>{netWorth.data?.investment.stalePrice && <StatusTag tone="warning">行情已过期</StatusTag>}{netWorth.data?.investment.missingPrice && <StatusTag tone="danger">存在缺失价格</StatusTag>}{netWorth.data?.investment.manualPrice && <StatusTag tone="blue">含手工价格</StatusTag>}</div></div>
      </div>
    </div>
    <details className="overview-details" onToggle={e=>setDetailsOpen(e.currentTarget.open)}><summary>资产配置与详细分析<span>展开查看负债、历史和家庭洞察</span></summary>
      <div className="analysis-grid">
        <DataPanel title="资产配置"><QueryState loading={netWorth.isLoading} error={netWorth.error} empty={!netWorth.data?.allocation.length}><div className="allocation-list">{netWorth.data?.allocation.map(item=><div key={item.type}><strong>{allocationLabel(item.type)}</strong><span>{money(item.amount)}</span><b>{item.sharePercent}%</b></div>)}</div></QueryState></DataPanel>
        <DataPanel title="贷款进度" meta={debt.data ? `负债率 ${debt.data.debtRatioPercent}%` : undefined}><QueryState loading={debt.isLoading} error={debt.error} empty={!debt.data?.loans.length} emptyTitle="没有活跃贷款"><div className="debt-list">{debt.data?.loans.map(item=><div key={item.loanId}><header><strong>{item.loanName}</strong><span>已还 {item.repaidPercent}%</span></header><div className="progress-track"><i style={{width:`${Math.max(0,Math.min(100,Number(item.repaidPercent)))}%`}}/></div><p>剩余 {money(item.currentPrincipal)} / {money(item.originalPrincipal)}</p></div>)}</div></QueryState></DataPanel>
        <DataPanel title="成员费用"><QueryState loading={dashboard.isLoading} error={dashboard.error} empty={!dashboard.data?.expenseByMember.length}><div className="member-spend-list">{dashboard.data?.expenseByMember.map(item=><div key={item.memberId}><span>{item.memberName}</span><strong>{money(item.amount)}</strong></div>)}</div></QueryState></DataPanel>
      </div>
      <DataPanel title="净资产历史"><div className="annual-months"><table><thead><tr><th>日期</th><th>资产</th><th>负债</th><th>净资产</th><th>估值说明</th><th>统计口径</th></tr></thead><tbody>{[...(netWorth.data?.history ?? [])].sort((a,b)=>a.snapshotOn.localeCompare(b.snapshotOn)).map(item=><tr key={item.snapshotOn}><td>{item.snapshotOn}</td><td>{money(item.asset)}</td><td>{money(item.liability)}</td><td>{money(item.netWorth)}</td><td>{historyValuationLabel(item)}</td><td>{historyBasisLabel(item)}</td></tr>)}</tbody></table></div></DataPanel>
      <DataPanel title="家庭洞察"><QueryState loading={analysis.isLoading} error={analysis.error} empty={!analysis.data?.insights.length} emptyTitle="数据仍在积累"><div className="insight-grid">{analysis.data?.insights.map((item,i)=><article key={i}><h3>{item.title}</h3><p>{item.message}</p><strong>{item.metric}</strong></article>)}</div></QueryState></DataPanel>
    </details>
    {role==='MEMBER' && <p className="page-footnote">你可以查看家庭共同财务；管理操作由所有者或管理员完成。</p>}
  </PageScaffold>;
}
function CashMetric({icon,label,value,loading,error}:{icon:'income'|'expense';label:string;value?:string;loading:boolean;error:unknown}) {
  return <div className="cash-metric"><span className={`entry-icon ${icon}`}>{icon==='income'?<ArrowDownLeft size={20}/>:<ArrowUpRight size={20}/>}</span><span>{label}<strong>{loading?'读取中':error?'暂不可用':money(value)}</strong></span></div>;
}
function allocationLabel(value:string) { return ({ACCOUNT:'现金账户',PROPERTY:'房产',VEHICLE:'车辆',OTHER:'其他资产',INVESTMENT:'投资'} as Record<string,string>)[value]??value; }
