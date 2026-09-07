package com.familyfinance.accounting;

import com.familyfinance.category.TransactionKind;
import java.time.LocalDate;

/** A current effective income/expense journal leg with household display dimensions. */
public record LedgerActivity(long id,LocalDate occurredOn,TransactionKind kind,long amountCents,
        Dimension category,Dimension member,String note,String sourceType,long sourceId) {
    public record Dimension(long id,String name,Dimension parent) {
        public long getId(){return id;}
        public String getName(){return name;}
        public Dimension getParent(){return parent;}
    }
    public long getId(){return id;}
    public LocalDate getOccurredOn(){return occurredOn;}
    public TransactionKind getKind(){return kind;}
    public long getAmountCents(){return amountCents;}
    public Dimension getCategory(){return category;}
    public Dimension getMember(){return member;}
    public String getNote(){return note;}
}
