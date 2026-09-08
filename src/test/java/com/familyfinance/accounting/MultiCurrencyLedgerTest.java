package com.familyfinance.accounting;

import static org.assertj.core.api.Assertions.*;
import static com.familyfinance.accounting.LedgerAccountKind.*;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest @ActiveProfiles("test")
class MultiCurrencyLedgerTest {
    @Autowired LedgerPostingService posting;
    @Autowired LedgerReadService read;
    @Autowired JdbcTemplate jdbc;
    long household,actor,cash;
    @BeforeEach void fixture(){
        String name=UUID.randomUUID().toString();
        jdbc.update("insert into households(name,created_at) values (?,current_timestamp)",name);
        household=jdbc.queryForObject("select id from households where name=?",Long.class,name);
        jdbc.update("insert into app_users(household_id,username,password_hash,email,display_name,created_at) values (?,?,?,?,?,current_timestamp)",household,name,"unused",name+"@test.invalid","test");
        actor=jdbc.queryForObject("select id from app_users where username=?",Long.class,name);
        jdbc.update("insert into financial_accounts(household_id,name,type,currency) values (?,'cash','CASH','CNY')",household);
        cash=jdbc.queryForObject("select id from financial_accounts where household_id=?",Long.class,household);
    }
    LedgerPostingCommand command(String key,List<LedgerEntryInput> entries){return new LedgerPostingCommand(household,"CURRENCY_TEST",1,key,LocalDate.of(2026,1,1),actor,entries);}
    @Test void foreignPostingAndReversalPreserveCurrencyAndBalance(){
        var command=command("post",List.of(new LedgerEntryInput("EQUITY:OPENING:USD",EQUITY,100,0,null,null,"USD"),
                new LedgerEntryInput("INCOME:INVESTMENT_GAIN:USD",INCOME,0,100,null,null,"USD")));
        var receipt=posting.post(command);
        assertThat(read.entries(household,receipt.journalId())).extracting(LedgerEntryInput::currency).containsExactly("USD","USD");
        assertThat(posting.post(command)).isEqualTo(receipt);
        var reverse=posting.reverse(household,"CURRENCY_TEST",1,"reverse",actor);
        assertThat(read.entries(household,reverse.journalId())).extracting(LedgerEntryInput::currency).containsExactly("USD","USD");
        assertThat(read.balance(household,"INCOME:INVESTMENT_GAIN:USD")).isZero();
    }
    @Test void cannotPretendACnyCashAccountIsUsd(){
        assertThatThrownBy(()->posting.post(command("bad",List.of(
                new LedgerEntryInput("CASH:"+cash,CASH,100,0,null,null,"USD"),
                new LedgerEntryInput("EQUITY:OPENING:USD",EQUITY,0,100,null,null,"USD")))))
                .isInstanceOf(com.familyfinance.shared.RequestValidationException.class);
        assertThat(jdbc.queryForObject("select count(*) from ledger_journals where household_id=?",Integer.class,household)).isZero();
    }
    @Test void foreignSystemAccountsMustNotReuseCnyCodes(){
        assertThatThrownBy(()->posting.post(command("bad",List.of(
                new LedgerEntryInput("EQUITY:OPENING",EQUITY,100,0,null,null,"USD"),
                new LedgerEntryInput("INCOME:INVESTMENT_GAIN:USD",INCOME,0,100,null,null,"USD")))))
                .isInstanceOf(com.familyfinance.shared.RequestValidationException.class);
    }
    @Test void databaseRejectsMismatchedEntryCurrency(){
        var receipt=posting.post(command("cny",List.of(new LedgerEntryInput("CASH:"+cash,CASH,100,0,null,null),
                new LedgerEntryInput("EQUITY:OPENING",EQUITY,0,100,null,null))));
        assertThatThrownBy(()->jdbc.update("update ledger_entries set currency='USD' where journal_id=?",receipt.journalId()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
