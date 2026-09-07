package com.familyfinance.reporting;

import com.familyfinance.shared.ApiEnvelope;
import com.familyfinance.shared.CurrentHousehold;
import com.familyfinance.shared.RequestValidationException;
import java.time.YearMonth;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReportingController {

    private final DashboardService dashboardService;
    private final AnalysisService analysisService;
    private final PortfolioService portfolioService;
    private final NetWorthService netWorthService;
    private final NetWorthSnapshotService snapshots;
    private final CurrentHousehold currentHousehold;
    private final java.time.Clock clock;

    public ReportingController(
            DashboardService dashboardService,
            AnalysisService analysisService,
            PortfolioService portfolioService,
            NetWorthService netWorthService,
            NetWorthSnapshotService snapshots,
            CurrentHousehold currentHousehold,java.time.Clock clock) {
        this.dashboardService = dashboardService;
        this.analysisService = analysisService;
        this.portfolioService = portfolioService;
        this.netWorthService = netWorthService;
        this.snapshots = snapshots;
        this.currentHousehold = currentHousehold;
        this.clock=clock;
    }

    @GetMapping("/api/dashboard")
    ApiEnvelope<DashboardResponse> dashboard(
            Authentication authentication,
            @RequestParam(required = false) String month,
            @RequestParam(defaultValue = "false") boolean rollupCategories) {
        return ApiEnvelope.data(dashboardService.dashboard(
                currentHousehold.id(authentication), parseMonth(month), rollupCategories));
    }

    @GetMapping("/api/analysis")
    ApiEnvelope<AnalysisResponse> analysis(
            Authentication authentication,
            @RequestParam(required = false) String month,
            @RequestParam(defaultValue = "false") boolean rollupCategories) {
        return ApiEnvelope.data(analysisService.analysis(
                currentHousehold.id(authentication), parseMonth(month), rollupCategories));
    }

    @GetMapping("/api/portfolio")
    ApiEnvelope<PortfolioResponse> portfolio(Authentication authentication,@RequestParam(required=false) LocalDate asOf) {
        return ApiEnvelope.data(asOf==null?portfolioService.portfolio(currentHousehold.id(authentication)):portfolioService.portfolio(currentHousehold.id(authentication),asOf));
    }

    @GetMapping("/api/net-worth")
    ApiEnvelope<NetWorthResponse> netWorth(Authentication authentication,@RequestParam(required=false) LocalDate asOf) {
        long householdId = currentHousehold.id(authentication);
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of("Asia/Shanghai")));
        LocalDate day=asOf==null?today:asOf;
        NetWorthResult result = netWorthService.calculate(householdId, day);
        return ApiEnvelope.data(NetWorthResponse.from(result, snapshots.history(householdId),day));
    }

    @GetMapping("/api/debt-analysis")
    ApiEnvelope<DebtAnalysisResponse> debtAnalysis(Authentication authentication,@RequestParam(required=false) LocalDate asOf) {
        long householdId = currentHousehold.id(authentication);
        return ApiEnvelope.data(DebtAnalysisResponse.from(netWorthService.calculate(
                householdId, asOf==null?LocalDate.now(clock.withZone(ZoneId.of("Asia/Shanghai"))):asOf)));
    }

    private YearMonth parseMonth(String rawMonth) {
        if (rawMonth == null || rawMonth.isBlank()) {
            return YearMonth.now(clock.withZone(ZoneId.of("Asia/Shanghai")));
        }
        try {
            return YearMonth.parse(rawMonth.trim());
        } catch (DateTimeParseException exception) {
            throw new RequestValidationException(Map.of("month", "月份格式必须是 YYYY-MM"));
        }
    }
}
