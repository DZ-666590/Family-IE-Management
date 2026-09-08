package com.familyfinance.loan;

import com.familyfinance.shared.ResourceConflictException;

/** One shared request budget: each entered custom search state consumes one unit. */
public final class LoanPlanningBudget {
 public static final int DEFAULT_MAX_STATES=100_000;
 private final int maximum;private int used;
 public LoanPlanningBudget(int maximum){if(maximum<0)throw new IllegalArgumentException("negative search budget");this.maximum=maximum;}
 public static LoanPlanningBudget standard(){return new LoanPlanningBudget(DEFAULT_MAX_STATES);}
 public int used(){return used;}
 void enter(){
  if(used>=maximum)throw new ResourceConflictException("LOAN_PLAN_SEARCH_LIMIT","本次计算尚未确定可行计划，未记录付款；请调整额外本金或选择较短期数后重新预览");
  used++;
 }
}
