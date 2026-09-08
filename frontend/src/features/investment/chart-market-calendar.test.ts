import { aggregateBars, selectBarsForRange, type CandleBar } from './chart-data';
const bar = (at: string, turnover: number | null = null): CandleBar => ({ timestamp: Date.parse(at), open: 10, high: 12, low: 9, close: 11, volume: 100, turnover });
it('retains the exact market-calendar month boundary across the New York DST change', () => {
  const values = [bar('2026-10-08T00:00:00-04:00'), bar('2026-10-09T00:00:00-04:00'), bar('2026-11-09T00:00:00-05:00')];
  expect(selectBarsForRange(values, 'day', '1m', 'America/New_York').map(x => x.timestamp)).toEqual([1791518400000, 1794200400000]);
});
it('does not fabricate aggregate turnover when even one source day has no turnover', () => {
  const values = [bar('2026-09-01T00:00:00-04:00', 1000), bar('2026-09-02T00:00:00-04:00')];
  expect(aggregateBars(values, 'month', 'America/New_York')[0]).toMatchObject({ volume: 200, turnover: null });
});
