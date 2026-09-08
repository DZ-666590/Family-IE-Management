package com.familyfinance.loan;

import java.math.*;
import java.time.LocalDate;
import java.util.*;
import com.familyfinance.shared.DecimalMoney;

/** DECIMAL128 evolution; authoritative scale-12 metadata is allocated cumulatively to cents. */
public final class PreciseLoanScheduleCalculator {
    private static final MathContext MC=MathContext.DECIMAL128;
    private static final BigDecimal TWELVE=BigDecimal.valueOf(12);
    public List<PreciseInstallmentDraft> calculate(BigDecimal principal,BigDecimal annualRate,List<LocalDate> dates,
            RepaymentMethod method,LoanRoundingContext context) {
        principal=DecimalMoney.settled(principal);
        if(principal.signum()<=0||principal.compareTo(new BigDecimal("999999999.99"))>0)throw new IllegalArgumentException("invalid principal");
        if(annualRate==null||annualRate.scale()>6||annualRate.signum()<0||annualRate.compareTo(BigDecimal.ONE)>0)throw new IllegalArgumentException("invalid annual rate");
        if(dates==null||dates.isEmpty()||dates.size()>360)throw new IllegalArgumentException("term must be 1..360");
        LocalDate previous=null;for(var date:dates){if(date==null||(previous!=null&&!date.isAfter(previous)))throw new IllegalArgumentException("dates must increase");previous=date;}
        if(method==null||method==RepaymentMethod.CUSTOM)throw new IllegalArgumentException("custom schedule requires its contractual rows");
        Objects.requireNonNull(context,"roundingContext");
        BigDecimal rate=annualRate.divide(TWELVE,MC);
        BigDecimal payment=principal.divide(BigDecimal.valueOf(dates.size()),MC);
        if(method==RepaymentMethod.EQUAL_PAYMENT&&rate.signum()>0){
            BigDecimal growth=BigDecimal.ONE.add(rate,MC).pow(dates.size(),MC);
            payment=principal.multiply(rate,MC).multiply(growth,MC).divide(growth.subtract(BigDecimal.ONE,MC),MC);
        }
        if(method==RepaymentMethod.EQUAL_PAYMENT){
            var fixed=allocate(principal,rate,dates,method,context,payment.setScale(2,RoundingMode.HALF_UP),true);
            if(fixed!=null)return fixed;
        }
        var cumulative=allocate(principal,rate,dates,method,context,payment,false);
        if(cumulative==null)throw new IllegalArgumentException("selected term cannot produce positive cash installments");
        return cumulative;
    }
    private List<PreciseInstallmentDraft> allocate(BigDecimal principal,BigDecimal rate,List<LocalDate> dates,
            RepaymentMethod method,LoanRoundingContext context,BigDecimal payment,boolean fixed) {
        BigDecimal balance=principal,precisePrincipal=BigDecimal.ZERO,preciseInterest=BigDecimal.ZERO,
                actualPrincipal=BigDecimal.ZERO,actualInterest=BigDecimal.ZERO;
        List<PreciseInstallmentDraft> result=new ArrayList<>();
        for(int i=0;i<dates.size();i++){
            boolean terminal=i==dates.size()-1;
            BigDecimal rawInterest=balance.multiply(rate,MC);
            BigDecimal rawPrincipal=method==RepaymentMethod.EQUAL_PRINCIPAL?payment:payment.subtract(rawInterest,MC);
            if(balance.signum()<=0||rawPrincipal.signum()<0||(!terminal&&rawPrincipal.compareTo(balance)>=0))return null;
            BigDecimal pi=stored(rawInterest);
            BigDecimal pp=terminal?principal.subtract(precisePrincipal):stored(rawPrincipal);
            precisePrincipal=precisePrincipal.add(pp);preciseInterest=preciseInterest.add(pi);
            BigDecimal ai=context.preciseInterestPaid().add(preciseInterest).setScale(2,RoundingMode.HALF_UP)
                    .subtract(context.actualInterestPaid()).subtract(actualInterest);
            BigDecimal ap=terminal?principal.subtract(actualPrincipal):fixed?payment.subtract(ai):
                    precisePrincipal.setScale(2,RoundingMode.FLOOR).subtract(actualPrincipal);
            actualPrincipal=actualPrincipal.add(ap);actualInterest=actualInterest.add(ai);
            BigDecimal remaining=principal.subtract(actualPrincipal);
            if(pp.signum()<0||ap.signum()<0||ai.signum()<0||ap.add(ai).signum()<=0||remaining.signum()<0||(!terminal&&remaining.signum()==0))return null;
            String policy=fixed?"FIXED_CASH_V1":method==RepaymentMethod.EQUAL_PAYMENT?"CUMULATIVE_CENTS_V1":"EQUAL_PRINCIPAL_CUMULATIVE_V1";
            result.add(new PreciseInstallmentDraft(i+1,dates.get(i),ap.setScale(2),ai.setScale(2),remaining.setScale(2),pp.setScale(12),pi,
                    context.preciseInterestPaid().add(preciseInterest).subtract(context.actualInterestPaid()).subtract(actualInterest).setScale(12),policy));
            balance=balance.subtract(rawPrincipal,MC);
        }
        return List.copyOf(result);
    }
    static BigDecimal stored(BigDecimal value){return value.setScale(12,RoundingMode.HALF_UP);}
}
