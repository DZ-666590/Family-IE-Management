export type ChartPeriod = 'day' | 'week' | 'month';
export type ChartRange = '1m' | '3m' | '1y' | 'all';
export interface CandleBar { timestamp: number; open: number; high: number; low: number; close: number; volume: number; turnover: number }
export interface CandleResponse { symbol: string; source: string; adjustment: 'none' | 'qfq'; asOf: string | null; fetchedAt: string | null; stale: boolean; supported: boolean; bars: CandleBar[] }

// Group by Shanghai calendar, never by the viewer's local timezone.
export function aggregateBars(bars: CandleBar[], period: ChartPeriod): CandleBar[] {
  if (period === 'day') return bars.map(bar => ({ ...bar }));
  const groups = new Map<string, CandleBar>();
  for (const bar of bars) {
    const day = new Date(bar.timestamp + 8 * 3600_000);
    if (period === 'week') day.setUTCDate(day.getUTCDate() - (day.getUTCDay() + 6) % 7);
    const key = day.toISOString().slice(0, period === 'month' ? 7 : 10);
    const current = groups.get(key);
    if (!current) groups.set(key, { ...bar });
    else {
      current.high = Math.max(current.high, bar.high);
      current.low = Math.min(current.low, bar.low);
      current.close = bar.close;
      current.volume += bar.volume;
      current.turnover += bar.turnover;
    }
  }
  return [...groups.values()];
}

function subtractShanghaiCalendar(timestamp: number, months: number): number {
  const shanghai = new Date(timestamp + 8 * 3600_000);
  const day = shanghai.getUTCDate();
  shanghai.setUTCDate(1);
  shanghai.setUTCMonth(shanghai.getUTCMonth() - months);
  const daysInTargetMonth = new Date(Date.UTC(shanghai.getUTCFullYear(), shanghai.getUTCMonth() + 1, 0)).getUTCDate();
  shanghai.setUTCDate(Math.min(day, daysInTargetMonth));
  return shanghai.getTime() - 8 * 3600_000;
}

export function selectBarsForRange(bars: CandleBar[], period: ChartPeriod, range: ChartRange): CandleBar[] {
  const aggregated = aggregateBars(bars, period);
  if (range === 'all' || aggregated.length === 0) return aggregated;
  const latest = bars.at(-1)?.timestamp ?? aggregated.at(-1)!.timestamp;
  const cutoff = range === '1y' ? subtractShanghaiCalendar(latest, 12) : subtractShanghaiCalendar(latest, range === '3m' ? 3 : 1);
  return aggregated.filter(bar => bar.timestamp >= cutoff);
}
