package com.familyfinance.accounting;

import com.familyfinance.shared.RequestValidationException;
import com.familyfinance.shared.ResourceConflictException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Whole-business request receipts, including zero openings and metadata-only commands.
 * The caller must hold the household lock in the same writable transaction. */
@Component
public class AccountingRequests {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final LedgerReadService ledger;
    public AccountingRequests(JdbcTemplate jdbc, ObjectMapper mapper,LedgerReadService ledger) { this.jdbc=jdbc; this.mapper=mapper; this.ledger=ledger; }

    public static String key(String supplied) {
        if (supplied == null) return UUID.randomUUID().toString();
        if (!supplied.matches("[A-Za-z0-9_.:-]{1,100}"))
            throw new RequestValidationException(Map.of("idempotencyKey", "请求键格式无效"));
        return supplied;
    }
    public String digest(String operation, long actor, Object request) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                (operation+":"+actor+":"+mapper.writeValueAsString(request)).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public Long replay(long household, String key, String digest) {
        return replay(household,key,digest,new String[0]);
    }
    /** Only callers with an explicit historical full-request contract may supply compatibility digests. */
    public Long replay(long household, String key, String digest, String... historicalDigests) {
        var rows=jdbc.query("select request_digest, source_id from accounting_commands where household_id=? and request_key=? for update",
            (rs,n)->new Receipt(rs.getString(1),rs.getLong(2)),household,key);
        if(rows.isEmpty()) {
            if(ledger.requestReceipt(household,key).isPresent())
                throw new ResourceConflictException("IDEMPOTENCY_KEY_REUSED", "请求键已用于其他账务操作");
            return null;
        }
        if(!rows.get(0).digest().equals(digest) && java.util.Arrays.stream(historicalDigests).noneMatch(rows.get(0).digest()::equals)) throw new ResourceConflictException("IDEMPOTENCY_KEY_REUSED", "请求键已用于不同内容");
        return rows.get(0).id();
    }
    public void record(long household,String key,String digest,long id) {
        jdbc.update("insert into accounting_commands(household_id,request_key,request_digest,source_id) values(?,?,?,?)",household,key,digest,id);
    }
    private record Receipt(String digest,long id) {}
}
