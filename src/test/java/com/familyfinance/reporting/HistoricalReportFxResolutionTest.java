package com.familyfinance.reporting;

import com.familyfinance.fx.ExchangeRateHistory;
import com.familyfinance.shared.CurrentHousehold;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class HistoricalReportFxResolutionTest {
    @Test void reportOnlySundayStartsAuthoritativeAcquisitionAndProvidesRetrySignal(){
        var portfolio=mock(PortfolioService.class);var household=mock(CurrentHousehold.class);var history=mock(ExchangeRateHistory.class);
        var auth=mock(Authentication.class);when(household.id(auth)).thenReturn(1L);
        var day=LocalDate.of(2026,9,6);
        when(history.requestValuationDate(day)).thenReturn(new ExchangeRateHistory.Progress("UPDATING",null,null));
        var controller=new ReportingController(mock(DashboardService.class),mock(AnalysisService.class),portfolio,mock(NetWorthService.class),mock(NetWorthSnapshotService.class),household,Clock.fixed(Instant.parse("2026-09-09T00:00:00Z"),ZoneOffset.UTC));
        ReflectionTestUtils.setField(controller,"fxHistory",history);ReflectionTestUtils.setField(controller,"automaticFx",true);
        var response=new MockHttpServletResponse();controller.portfolio(auth,day,response);
        verify(history).requestValuationDate(day);verify(portfolio).portfolio(1L,day);
        assertThat(response.getHeader("X-FX-Resolution")).isEqualTo("UPDATING");
        assertThat(response.getHeader("Retry-After")).isEqualTo("5");
    }
}
