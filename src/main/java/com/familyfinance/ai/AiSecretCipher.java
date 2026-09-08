package com.familyfinance.ai;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
final class AiSecretCipher {
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();
    AiSecretCipher(@Value("${app.ai.encryption-key:}") String encoded) {
        SecretKeySpec parsed = null;
        try {
            byte[] bytes = Base64.getDecoder().decode(encoded);
            if (bytes.length == 32) parsed = new SecretKeySpec(bytes, "AES");
            Arrays.fill(bytes, (byte) 0);
        } catch (IllegalArgumentException ignored) { /* Fail closed for AI only. */ }
        key = parsed;
    }
    boolean ready() { return key != null; }
    String encrypt(long userId, String secret) {
        if (!ready()) throw AiFailure.unavailable();
        byte[] nonce = new byte[12];
        random.nextBytes(nonce);
        byte[] plain = secret.getBytes(StandardCharsets.UTF_8);
        try {
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, userId, nonce);
            return "v1." + Base64.getEncoder().encodeToString(nonce) + "."
                    + Base64.getEncoder().encodeToString(cipher.doFinal(plain));
        } catch (GeneralSecurityException e) { throw AiFailure.unavailable(); }
        finally { Arrays.fill(plain, (byte) 0); }
    }
    String decrypt(long userId, String encrypted) {
        if (!ready()) throw AiFailure.unavailable();
        byte[] plain = null;
        try {
            String[] parts = encrypted.split("\\.");
            if (parts.length != 3 || !parts[0].equals("v1")) throw AiFailure.unavailable();
            byte[] nonce = Base64.getDecoder().decode(parts[1]);
            if (nonce.length != 12) throw AiFailure.unavailable();
            plain = cipher(Cipher.DECRYPT_MODE, userId, nonce).doFinal(Base64.getDecoder().decode(parts[2]));
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) { throw AiFailure.unavailable(); }
        finally { if (plain != null) Arrays.fill(plain, (byte) 0); }
    }
    private Cipher cipher(int mode, long userId, byte[] nonce) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(128, nonce));
        cipher.updateAAD(("family-finance:ai:v1:" + userId).getBytes(StandardCharsets.UTF_8));
        return cipher;
    }
}
