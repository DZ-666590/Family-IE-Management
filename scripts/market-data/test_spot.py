import unittest
from datetime import datetime, timedelta, timezone
import overseas_sources as sources


def raw_quote(key, code, price, stamp, currency):
    fields = [''] * 90
    fields[0] = '100' if key.startswith('hk') else '200' if key.startswith('us') else '1'
    fields[1], fields[2], fields[3], fields[30], fields[35] = 'synthetic', code, price, stamp, currency
    return 'v_' + key + '="' + '~'.join(fields) + '";'


class SpotTests(unittest.TestCase):
    def test_shenzhen_and_beijing_use_their_actual_provider_market_markers(self):
        now=datetime(2026,9,9,2,tzinfo=timezone.utc)
        for symbol,key,marker in [('000001.SZ','sz000001','51'),('920002.BJ','bj920002','62')]:
            raw=raw_quote(key,symbol[:6],'10.12','20260909100000','CNY').replace('="1~','="'+marker+'~')
            self.assertIn(symbol,sources.parse_spot(raw,'CN',[symbol],now))

    def test_after_close_an_old_intraday_quote_is_stale_and_retried_not_frozen_overnight(self):
        now=datetime(2026,9,9,9,40,tzinfo=timezone.utc)
        service=sources.SpotQuoteService(lambda *args:raw_quote('hk01810','01810','26.5','2026/09/09 15:50:00','HKD'),lambda:now)
        response=service.quotes('HK',['01810'])
        self.assertEqual('STALE',response['quotes'][0]['status'])
        self.assertLessEqual(response['nextRefreshSeconds'],900)
    def test_quote_uses_provider_time_currency_and_decimal_price(self):
        now = datetime(2026, 9, 9, 1, 40, tzinfo=timezone.utc)
        result = sources.parse_spot(raw_quote('hk01810', '01810', '26.681234', '2026/09/09 09:25:00', 'HKD'), 'HK', ['01810'], now)
        self.assertEqual('26.681234', result['01810']['price'])
        self.assertEqual('2026-09-09T01:25:00Z', result['01810']['quotedAt'])
        self.assertEqual('HKD', result['01810']['currency'])
        self.assertNotEqual(result['01810']['quotedAt'], result['01810']['fetchedAt'])

    def test_wrong_identity_currency_and_future_price_are_rejected(self):
        now = datetime(2026, 9, 9, 1, 40, tzinfo=timezone.utc)
        for code, price, stamp, currency in [('00700','26','2026/09/09 09:25:00','HKD'), ('01810','0','2026/09/09 09:25:00','HKD'), ('01810','26','2026/09/10 09:25:00','HKD'), ('01810','26','2026/09/09 09:25:00','USD')]:
            self.assertNotIn('01810', sources.parse_spot(raw_quote('hk01810', code, price, stamp, currency), 'HK', ['01810'], now))

    def test_shared_cache_and_failed_refresh_preserve_last_valid_quote(self):
        now = [datetime(2026, 9, 9, 2, tzinfo=timezone.utc)]
        calls = []
        def load(market, symbols):
            calls.append(symbols)
            if len(calls) > 1: raise RuntimeError('offline')
            return raw_quote('sh600519', '600519', '1300.12', '20260909100000', 'CNY')
        service = sources.SpotQuoteService(load, lambda: now[0])
        first = service.quotes('CN',['600519.SH'])
        service.quotes('CN',['600519.SH'])
        self.assertEqual(1,len(calls))
        now[0] += timedelta(seconds=61)
        failed = service.quotes('CN',['600519.SH'])
        self.assertEqual(first['quotes'][0]['price'],failed['quotes'][0]['price'])
        self.assertEqual('STALE',failed['quotes'][0]['status'])

    def test_us_timestamp_observes_dst_and_daily_cutoff_waits_until_after_close(self):
        summer=datetime(2026,9,9,0,tzinfo=timezone.utc)
        data=sources.parse_spot(raw_quote('usAAPL','AAPL.OQ','316.22','2026-09-08 16:00:02','USD'),'US',['AAPL'],summer)
        self.assertEqual('2026-09-08T20:00:02Z',data['AAPL']['quotedAt'])
        self.assertEqual('2026-09-08',sources.completed_market_day(datetime(2026,9,9,8,tzinfo=timezone.utc),'CN').isoformat())
        self.assertEqual('2026-09-09',sources.completed_market_day(datetime(2026,9,9,10,tzinfo=timezone.utc),'CN').isoformat())
        self.assertEqual('2026-09-08',sources.completed_market_day(summer,'US').isoformat())

    def test_invalid_symbols_never_reach_network(self):
        service=sources.SpotQuoteService(lambda *args:self.fail('must not fetch'))
        for market, symbols in [('US',['AAPL&evil=1']),('HK',['../env']),('CN',['01810']),('XX',['AAPL'])]:
            with self.assertRaises(ValueError): service.quotes(market,symbols)

if __name__=='__main__': unittest.main()
