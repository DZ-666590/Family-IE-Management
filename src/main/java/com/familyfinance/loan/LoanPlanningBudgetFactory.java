package com.familyfinance.loan;

import org.springframework.stereotype.Component;

/** A request gets one budget, shared by its selected plan and every term candidate. */
@Component
public class LoanPlanningBudgetFactory {
 public LoanPlanningBudget create(){return LoanPlanningBudget.standard();}
}
