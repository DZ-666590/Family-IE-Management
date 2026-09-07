package com.familyfinance.loan;

import java.util.Optional;
import java.time.LocalDate;
import java.util.List;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;

public interface LoanInstallmentRepository extends JpaRepository<LoanInstallment, Long> {
 @Query(value="select loan_id from loan_installments where id=:id and household_id=:householdId for update",nativeQuery=true)
 Optional<Long> findCurrentLoanId(@org.springframework.data.repository.query.Param("id") long id,@org.springframework.data.repository.query.Param("householdId") long householdId);
 @Lock(LockModeType.PESSIMISTIC_WRITE)
 Optional<LoanInstallment> findLockedByIdAndHouseholdId(Long id, Long householdId);
 @Lock(LockModeType.PESSIMISTIC_WRITE)
 List<LoanInstallment> findAllLockedByLoanIdAndHouseholdIdOrderByInstallmentNo(Long loanId,Long householdId);
 List<LoanInstallment> findByHouseholdIdAndStatusAndDueOnLessThanEqualOrderByDueOnAscIdAsc(Long householdId, LoanInstallmentStatus status, LocalDate dueOn);
}
