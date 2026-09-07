package com.familyfinance.accounting;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Length-prefixed canonical fields avoid ambiguous concatenation and locale-dependent formatting. */
final class LedgerRequestDigest {
    private LedgerRequestDigest() {}
    static String of(String operation,LedgerPostingCommand c) {
        try {
            var bytes=new ByteArrayOutputStream();
            var out=new DataOutputStream(bytes);
            out.writeUTF(operation); out.writeLong(c.householdId()); out.writeUTF(c.sourceType()); out.writeLong(c.sourceId());
            out.writeLong(c.actorId()); out.writeUTF(c.effectiveOn()==null ? "" : c.effectiveOn().toString());
            out.writeInt(c.entries().size());
            for(var e:c.entries()) {
                out.writeUTF(e.accountCode()==null ? "" : e.accountCode()); out.writeUTF(e.kind().name());
                out.writeLong(e.debitCents()); out.writeLong(e.creditCents());
                nullable(out,e.categoryId()); nullable(out,e.memberId());
            }
            out.flush();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch(IOException|NoSuchAlgorithmException ex) { throw new IllegalStateException("Cannot hash accounting request",ex); }
    }
    private static void nullable(DataOutputStream out,Long value) throws IOException {
        out.writeBoolean(value!=null); if(value!=null) out.writeLong(value);
    }
}
