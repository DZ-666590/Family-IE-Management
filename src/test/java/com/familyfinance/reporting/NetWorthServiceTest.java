package com.familyfinance.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.familyfinance.asset.Asset;
import com.familyfinance.asset.AssetRepository;
import com.familyfinance.asset.AssetStatus;
import com.familyfinance.budget.Budget;
import com.familyfinance.budget.BudgetRepository;
import com.familyfinance.budget.BudgetScopeType;
import com.familyfinance.category.Category;
import com.familyfinance.investment.InvestmentTradeRepository;
import com.familyfinance.ledger.AccountBalance;
import com.familyfinance.ledger.FinancialAccountRepository;
import com.familyfinance.loan.Loan;
import com.familyfinance.loan.LoanRepository;
import com.familyfinance.loan.LoanStatus;
import com.familyfinance.accounting.LedgerReportingService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class NetWorthServiceTest {

    @Test
    void budgetAggregateIsAlreadyStoredInCents() {
        NetWorthResult result = calculateWithBudgetAggregate("159135");

        assertThat(result.budget().spentCents()).isEqualTo(159_135L);
    }

    @Test
    void budgetAggregatePreservesOneCent() {
        NetWorthResult result = calculateWithBudgetAggregate("1");

        assertThat(result.budget().spentCents()).isEqualTo(1L);
    }

    @Test
    void handCalculatedAssetsAndLiabilitiesAreCountedExactlyOnce() {
        FinancialAccountRepository accounts = mock(FinancialAccountRepository.class);
        AssetRepository assets = mock(AssetRepository.class);
        LoanRepository loans = mock(LoanRepository.class);
        PortfolioService portfolio = mock(PortfolioService.class);
        when(accounts.findActiveBalancesByHouseholdIdAndOccurredOnBefore(1L, LocalDate.of(2026, 9, 3)))
                .thenReturn(List.of(new AccountBalance(10L, 100_000L)));
        Asset property = mock(Asset.class);
        when(property.getCurrentValueCents()).thenReturn(900_000L);
        when(assets.findAllByHouseholdIdAndStatus(1L, AssetStatus.ACTIVE)).thenReturn(List.of(property));
        Loan loan = mock(Loan.class);
        when(loan.getPrincipalCents()).thenReturn(400_000L);
        when(loan.getCurrentPrincipalCents()).thenReturn(400_000L);
        when(loans.findAllByHouseholdIdAndStatus(1L, LoanStatus.ACTIVE)).thenReturn(List.of(loan));
        when(portfolio.portfolio(1L,LocalDate.of(2026,9,3))).thenReturn(new PortfolioResponse(List.of(),
                new PortfolioTotalsResponse("0.00", "2000.00", "0.00", "0.00", "0.00", 0)));

        LedgerReportingService reporting=mock(LedgerReportingService.class);
        when(reporting.balancesAsOf(1L,LocalDate.of(2026,9,3))).thenReturn(java.util.Map.of("CASH:10",100000L,"ASSET:1",900000L,"LOAN:1",400000L));
        NetWorthService service = new NetWorthService(accounts, assets, loans, portfolio,
                mock(BudgetRepository.class), reporting,
                Clock.fixed(Instant.parse("2026-09-03T12:00:00Z"), ZoneOffset.UTC));

        NetWorthResult result = service.calculate(1L, LocalDate.of(2026, 9, 3));

        assertThat(result.assetCents()).isEqualTo(1_200_000L);
        assertThat(result.liabilityCents()).isEqualTo(400_000L);
        assertThat(result.netWorthCents()).isEqualTo(800_000L);
        assertThat(result.allocation().stream().mapToInt(AllocationSlice::shareTenths).sum()).isEqualTo(1000);
    }

    private static NetWorthResult calculateWithBudgetAggregate(String aggregateCents) {
        FinancialAccountRepository accounts = mock(FinancialAccountRepository.class);
        AssetRepository assets = mock(AssetRepository.class);
        LoanRepository loans = mock(LoanRepository.class);
        PortfolioService portfolio = mock(PortfolioService.class);
        BudgetRepository budgets = mock(BudgetRepository.class);
        LedgerReportingService transactions = mock(LedgerReportingService.class);
        Budget budget = mock(Budget.class);
        Category category = mock(Category.class);
        LocalDate asOf = LocalDate.of(2026, 9, 3);

        when(accounts.findActiveBalancesByHouseholdIdAndOccurredOnBefore(1L, asOf)).thenReturn(List.of());
        when(assets.findAllByHouseholdIdAndStatus(1L, AssetStatus.ACTIVE)).thenReturn(List.of());
        when(loans.findAllByHouseholdIdAndStatus(1L, LoanStatus.ACTIVE)).thenReturn(List.of());
        when(portfolio.portfolio(1L,LocalDate.of(2026,9,3))).thenReturn(new PortfolioResponse(List.of(),
                new PortfolioTotalsResponse("0.00", "0.00", "0.00", "0.00", "0.00", 0)));
        when(budgets.findAllByHouseholdIdAndPeriodMonthAndActiveTrue(1L, "2026-09"))
                .thenReturn(List.of(budget));
        when(budget.getAmountCents()).thenReturn(500_000L);
        when(budget.getScopeType()).thenReturn(BudgetScopeType.CATEGORY);
        when(budget.getCategory()).thenReturn(category);
        when(category.getId()).thenReturn(11L);
        when(transactions.sumBudgetExpenseCents(
                1L, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 4),
                "CATEGORY", 11L, null, true)).thenReturn(aggregateCents);

        return new NetWorthService(accounts, assets, loans, portfolio, budgets, transactions,
                Clock.fixed(Instant.parse("2026-09-03T12:00:00Z"), ZoneOffset.UTC)).calculate(1L, asOf);
    }
}
