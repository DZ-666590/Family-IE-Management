import { useEffect, useMemo, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { aggregateBars, type CandleBar, type CandleResponse, type ChartPeriod } from './chart-data';
import { dateText, money, type RequestFn } from '../common';

export type ChartSecurity = { id: number; tsCode: string; name: string };
export function StockChart({ request, security }: { request: RequestFn; security: ChartSecurity }) {
  const [adjustment, setAdjustment] = useState<'none' | 'qfq'>('qfq');
  const [period, setPeriod] = useState<ChartPeriod>('day');
  const [macd, setMacd] = useState(false);
  const query = useQuery({ queryKey: ['security-candles', security.id, adjustment], queryFn: () => request<CandleResponse>(`/api/securities/${security.id}/candles?adjust=${adjustment}`), staleTime: 300_000, retry: false });
  const bars = useMemo(() => aggregateBars(query.data?.bars ?? [], period), [query.data, period]);
  const last = bars.at(-1);
  return <section className="stock-chart">
    <header className="stock-chart-heading"><div><span>{security.tsCode}</span><h3>{security.name}</h3></div><strong>{money(last?.close)}</strong></header>
    <div className="stock-chart-controls"><div className="segmented-tabs" aria-label="K 线周期">{(['day', 'week', 'month'] as const).map(value => <button key={value} type="button" aria-pressed={period === value} className={period === value ? 'active' : ''} onClick={() => setPeriod(value)}>{({ day: '日 K', week: '周 K', month: '月 K' })[value]}</button>)}</div>
      <label>复权方式<select value={adjustment} onChange={event => setAdjustment(event.target.value as 'none' | 'qfq')}><option value="qfq">前复权</option><option value="none">不复权</option></select></label>
      <label className="stock-chart-indicator"><input type="checkbox" checked={macd} onChange={event => setMacd(event.target.checked)}/>MACD</label>
    </div>
    {query.isLoading ? <div role="status" className="stock-chart-message">正在加载历史行情…</div> : query.error ? <div role="alert" className="stock-chart-message"><p>{query.error instanceof Error ? query.error.message : '行情暂时不可用'}</p><button type="button" onClick={() => { void query.refetch(); }}>重新加载行情</button></div> : !query.data?.supported ? <div role="status" className="stock-chart-message">行情源暂未覆盖这只股票。已有持仓和交易记录仍会保留。</div> : !bars.length ? <div role="status" className="stock-chart-message">所选范围内没有可用的历史行情。</div> : <>
      <div className="stock-chart-provenance" role="status"><span>{query.data.source === 'BAOSTOCK' ? 'BaoStock' : query.data.source} · 截至 {dateText(query.data.asOf)}</span>{query.data.stale && <strong>缓存行情，尚未更新到最近交易日</strong>}</div>
      <CandleCanvas bars={bars} security={security} period={period} macd={macd}/>
      <div className="stock-chart-footnote"><span>红涨绿跌 · 成交量：股 · 仅收盘数据</span><span>{adjustment === 'qfq' ? '前复权用于走势展示，不改变实际成本或估值。' : '不复权为实际历史价格。'}{period !== 'day' && ' 最近一根可能是尚未结束的周期。'}</span></div>
      <details className="stock-chart-details"><summary>查看最近交易日数据</summary><dl><div><dt>开盘</dt><dd>{money(last?.open)}</dd></div><div><dt>最高</dt><dd>{money(last?.high)}</dd></div><div><dt>最低</dt><dd>{money(last?.low)}</dd></div><div><dt>成交量</dt><dd>{last?.volume.toLocaleString('zh-CN')} 股</dd></div></dl></details>
    </>}
  </section>;
}

function CandleCanvas({ bars, security, period, macd }: { bars: CandleBar[]; security: ChartSecurity; period: ChartPeriod; macd: boolean }) {
  const host = useRef<HTMLDivElement>(null);
  const [failed, setFailed] = useState(false);
  useEffect(() => {
    const element = host.current;
    if (!element) return;
    let cancelled = false;
    let cleanup: (() => void) | undefined;
    setFailed(false);
    void import('klinecharts').then(({ init, dispose }) => {
      if (cancelled) return;
      const chart = init(element, { locale: 'zh-CN', timezone: 'Asia/Shanghai', styles: { candle: { bar: { upColor: '#c74b50', downColor: '#31846a', noChangeColor: '#67727e', upBorderColor: '#c74b50', downBorderColor: '#31846a', upWickColor: '#c74b50', downWickColor: '#31846a' } } } });
      if (!chart) { setFailed(true); return; }
      cleanup = () => dispose(element);
      chart.setSymbol({ ticker: security.tsCode, pricePrecision: 2, volumePrecision: 0 });
      chart.setPeriod({ span: 1, type: period });
      chart.setDataLoader({ getBars: ({ type, callback }) => callback(type === 'init' ? bars.map(bar => ({ ...bar })) : [], false) });
      chart.createIndicator({ name: 'MA', paneId: 'candle_pane' });
      chart.createIndicator('VOL');
      if (macd) chart.createIndicator('MACD');
      const observer = typeof ResizeObserver !== 'undefined' ? new ResizeObserver(() => chart.resize()) : null;
      observer?.observe(element);
      chart.resize();
      cleanup = () => { observer?.disconnect(); dispose(element); };
    }).catch(() => { cleanup?.(); cleanup = undefined; if (!cancelled) setFailed(true); });
    return () => { cancelled = true; cleanup?.(); };
  }, [bars, security.tsCode, period, macd]);
  return <>{failed && <p role="alert">图表暂时无法绘制，可展开下方查看价格数据。</p>}<div ref={host} className="stock-chart-canvas" role="img" aria-label={`${security.name} K 线图，可缩放和拖动`}/></>;
}
