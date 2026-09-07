package com.familyfinance.reporting;

public record DashboardSummaryResponse(String income, String expense, String balance,
        String cashIn,String cashOut,String principalPaid,String borrowed,String noncashValuationChange) {
    public DashboardSummaryResponse(String income,String expense,String balance){this(income,expense,balance,null,null,null,null,null);}
}
