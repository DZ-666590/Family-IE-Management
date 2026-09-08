export type ChartPeriod = 'day' | 'week' | 'month';
export type ChartRange = '1m' | '3m' | '1y' | 'all';
export interface CandleBar { timestamp: number; open: number; high: number; low: number; close: number; volume: number; turnover: number | null }
export interface CandleResponse { symbol: string; source: string; adjustment: 'none' | 'qfq'; asOf: string | null; fetchedAt: string | null; stale: boolean; supported: boolean; bars: CandleBar[] }

function marketCalendar(timezone: string) {
  const formatter = new Intl.DateTimeFormat('en-CA', { timeZone: timezone, year: 'numeric', month: '2-digit', day: '2-digit' });
  return (timestamp: number) => {
    const parts = formatter.formatToParts(timestamp);
    const value = (type: string) => Number(parts.find(part => part.type === type)?.value);
    return new Date(Date.UTC(value('year'), value('month') - 1, value('day')));
  };
}

// Calendar dates, not fixed UTC offsets: US month boundaries cross DST.
export function aggregateBars(bars: CandleBar[], period: ChartPeriod, timezone = 'Asia/Shanghai'): CandleBar[] {
  if (period === 'day') return bars.map(bar => ({ ...bar }));
  const calendar = marketCalendar(timezone);
  const groups = new Map<string, CandleBar>();
  for (const bar of bars) {
    const day = calendar(bar.timestamp);
    if (period === 'week') day.setUTCDate(day.getUTCDate() - (day.getUTCDay() + 6) % 7);
    const key = day.toISOString().slice(0, period === 'month' ? 7 : 10);
    const current = groups.get(key);
    if (!current) groups.set(key, { ...bar });
    else {
      current.high = Math.max(current.high, bar.high);
      current.low = Math.min(current.low, bar.low);
      current.close = bar.close;
      current.volume += bar.volume;
      current.turnover = current.turnover == null || bar.turnover == null ? null : current.turnover + bar.turnover;
    }
  }
  return [...groups.values()];
}

function subtractCalendar(date: Date, months: number): number {
  const day = date.getUTCDate();
  date.setUTCDate(1);
  date.setUTCMonth(date.getUTCMonth() - months);
  const daysInTargetMonth = new Date(Date.UTC(date.getUTCFullYear(), date.getUTCMonth() + 1, 0)).getUTCDate();
  date.setUTCDate(Math.min(day, daysInTargetMonth));
  return date.getTime();
}

export function selectBarsForRange(bars: CandleBar[], period: ChartPeriod, range: ChartRange, timezone = 'Asia/Shanghai'): CandleBar[] {
  const aggregated = aggregateBars(bars, period, timezone);
  if (range === 'all' || aggregated.length === 0) return aggregated;
  const latest = bars.at(-1)?.timestamp ?? aggregated.at(-1)!.timestamp;
  const calendar = marketCalendar(timezone);
  const cutoff = subtractCalendar(calendar(latest), range === '1y' ? 12 : range === '3m' ? 3 : 1);
  return aggregated.filter(bar => calendar(bar.timestamp).getTime() >= cutoff);
}
