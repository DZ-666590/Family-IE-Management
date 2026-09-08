package com.familyfinance.accounting;

import com.familyfinance.category.TransactionKind;
import java.time.LocalDate;
import java.math.BigDecimal;
import com.familyfinance.shared.DecimalMoney;

/** A current effective income/expense journal leg with household display dimensions. */
public record LedgerActivity(long id,LocalDate occurredOn,TransactionKind kind,BigDecimal amount,
        Dimension category,Dimension member,String note,String sourceType,long sourceId) {
    public LedgerActivity { amount=DecimalMoney.settled(amount); }
    /** Compatibility for business reporting callers still expressing integer cents. */
    public LedgerActivity(long id,LocalDate occurredOn,TransactionKind kind,long amountCents,
            Dimension category,Dimension member,String note,String sourceType,long sourceId) {
        this(id,occurredOn,kind,DecimalMoney.fromCents(amountCents),category,member,note,sourceType,sourceId);
    }
    public long amountCents(){return DecimalMoney.toCents(amount);}
    public record Dimension(long id,String name,Dimension parent) {
        public long getId(){return id;}
        public String getName(){return name;}
        public Dimension getParent(){return parent;}
    }
    public long getId(){return id;}
    public LocalDate getOccurredOn(){return occurredOn;}
    public TransactionKind getKind(){return kind;}
    public long getAmountCents(){return amountCents();}
    public Dimension getCategory(){return category;}
    public Dimension getMember(){return member;}
    public String getNote(){return note;}
}
