export type ChartPeriod = 'day' | 'week' | 'month';
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
