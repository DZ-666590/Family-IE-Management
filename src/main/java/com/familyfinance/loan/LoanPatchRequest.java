package com.familyfinance.loan;
import java.math.BigDecimal; import java.time.LocalDate; import java.util.List;
public record LoanPatchRequest(String name, Long linkedAssetId, Long memberId, Long assignedUserId, Long paymentAccountId, Long paymentCategoryId, String principal, BigDecimal annualRate, Integer termMonths, RepaymentMethod repaymentMethod, LocalDate startOn, List<CustomInstallmentRequest> customSchedule, LocalDate accountingOn, Long disbursementAccountId) {
 public LoanPatchRequest(String name, Long linkedAssetId, Long memberId, Long assignedUserId, Long paymentAccountId, Long paymentCategoryId, String principal, BigDecimal annualRate, Integer termMonths, RepaymentMethod repaymentMethod, LocalDate startOn, List<CustomInstallmentRequest> customSchedule) {
  this(name,linkedAssetId,memberId,assignedUserId,paymentAccountId,paymentCategoryId,principal,annualRate,termMonths,repaymentMethod,startOn,customSchedule,null,null);
 }
}
