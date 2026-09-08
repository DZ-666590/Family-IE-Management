import type { CandleResponse } from './chart-data';
export type OverseasMarket = 'HK' | 'US';
export interface OverseasInstrument { symbol: string; name: string; market: OverseasMarket; currency: string; exchange: string; timezone: string }
export interface OverseasSearch { items: OverseasInstrument[]; hasNext: boolean; updatedAt: string | null; stale: boolean; state: 'SYNCING' | 'READY' | 'ERROR'; error: string | null }
export interface OverseasCandles extends CandleResponse { instrument: OverseasInstrument }
export function isOverseasInstrument(value: OverseasInstrument, market: OverseasMarket) {
  return value && value.market === market && typeof value.symbol === 'string' && typeof value.name === 'string' && value.name.length > 0 && value.name.length <= 200
    && (market === 'US' ? /^[A-Z][A-Z0-9.-]{0,9}$/.test(value.symbol) && value.currency === 'USD' && ['NASDAQ','NYSE','NYSE AMERICAN','NYSE ARCA','CBOE BZX','IEX'].includes(value.exchange)
      : /^[0-9]{5}$/.test(value.symbol) && ['HKD','CNY','USD'].includes(value.currency) && value.exchange === 'HKEX')
    && value.timezone === (market === 'US' ? 'America/New_York' : 'Asia/Hong_Kong');
}
export function marketMoney(value: number | string | null | undefined, currency: string) {
  if (value == null || value === '' || !Number.isFinite(Number(value))) return '—';
  return new Intl.NumberFormat('zh-CN', { style: 'currency', currency, currencyDisplay: 'code', minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(Number(value));
}
