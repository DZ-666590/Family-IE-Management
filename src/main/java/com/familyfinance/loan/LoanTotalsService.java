package com.familyfinance.loan;

import com.familyfinance.shared.Money;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Whole history, not a schedule page or a possibly cached JPA collection. */
@Service
public class LoanTotalsService {
 private final JdbcTemplate jdbc; private final EntityManager em;
 public LoanTotalsService(JdbcTemplate jdbc,EntityManager em){this.jdbc=jdbc;this.em=em;}
 public record Totals(String scheduledRepaymentTotal,String remainingRepaymentTotal,String paidRepaymentTotal,PrepaymentStrategy latestStrategy,int remainingTerm,LocalDate maturityOn,LocalDate nextPaymentOn,String nextPaymentAmount){}
 public Totals read(Loan loan,boolean current){
  if(current)em.flush();
  String lock=current?" for update":"";long h=loan.getHousehold().getId(),id=loan.getId();long paid=0,remaining=0;
  var rows=jdbc.query("select i.status,i.principal_cents,i.interest_cents,t.amount_cents,i.due_on from loan_installments i left join financial_transactions t on t.id=i.confirmed_transaction_id and t.household_id=i.household_id where i.household_id=? and i.loan_id=? order by i.installment_no"+lock,(rs,n)->new Row(rs.getString(1),rs.getLong(2),rs.getLong(3),rs.getObject(4)==null?null:rs.getLong(4),rs.getObject(5,LocalDate.class)),h,id);
  for(var row:rows){if("PENDING".equals(row.status()))remaining=Math.addExact(remaining,Math.addExact(row.principal(),row.interest()));else if("PAID".equals(row.status()))paid=Math.addExact(paid,row.cash()==null?Math.addExact(row.principal(),row.interest()):row.cash());}
  // One authoritative cash value per prepayment; never add both event and transaction.
  for(long cash:jdbc.query("select coalesce(t.amount_cents,p.amount_cents+p.interest_cents) from loan_prepayments p left join financial_transactions t on t.id=p.transaction_id and t.household_id=p.household_id where p.household_id=? and p.loan_id=? order by p.id"+lock,(rs,n)->rs.getLong(1),h,id))paid=Math.addExact(paid,cash);
  var pending=rows.stream().filter(row->"PENDING".equals(row.status())).toList();
  var strategies=jdbc.queryForList("select strategy from loan_prepayments where household_id=? and loan_id=? and strategy is not null order by id desc limit 1"+lock,String.class,h,id);
  return new Totals(Money.formatCents(Math.addExact(paid,remaining)),Money.formatCents(remaining),Money.formatCents(paid),strategies.isEmpty()?null:PrepaymentStrategy.valueOf(strategies.get(0)),pending.size(),pending.isEmpty()?null:pending.get(pending.size()-1).dueOn(),pending.isEmpty()?null:pending.get(0).dueOn(),pending.isEmpty()?null:Money.formatCents(Math.addExact(pending.get(0).principal(),pending.get(0).interest())));
 }
 private record Row(String status,long principal,long interest,Long cash,LocalDate dueOn){}
}
