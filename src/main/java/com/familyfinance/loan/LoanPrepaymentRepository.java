package com.familyfinance.loan;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
public interface LoanPrepaymentRepository extends JpaRepository<LoanPrepayment,Long> {
 Optional<LoanPrepayment> findByHouseholdIdAndLoanIdAndRequestKey(Long householdId,Long loanId,String requestKey);
 @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
 Optional<LoanPrepayment> findLockedByIdAndHouseholdId(Long id,Long householdId);
 @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
 java.util.List<LoanPrepayment> findAllLockedByLoanIdAndHouseholdId(Long loanId,Long householdId);
}
