package com.familyfinance.ai;

import com.familyfinance.family.CurrentMembership;
import com.familyfinance.household.AppUser;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AiSettingsService {
    private final AiSettingsRepository repository;
    private final CurrentMembership membership;
    private final AiSecretCipher cipher;
    private final AiEndpointPolicy policy;
    private final EntityManager entityManager;
    private final Clock clock;
    AiSettingsService(AiSettingsRepository repository, CurrentMembership membership, AiSecretCipher cipher,
                      AiEndpointPolicy policy, EntityManager entityManager, Clock clock) {
        this.repository = repository; this.membership = membership; this.cipher = cipher;
        this.policy = policy; this.entityManager = entityManager; this.clock = clock;
    }
    public record View(String baseUrl, String model, boolean keyConfigured, String maskedKey,
                       boolean storageReady, List<String> allowedHosts, Instant updatedAt) {}
    public record Update(String baseUrl, String model, @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.WRITE_ONLY) String apiKey) {
        @Override public String toString() { return "AiSettings.Update[REDACTED]"; }
    }
    @Transactional(readOnly = true)
    public View read(Authentication auth) { return view(repository.findById(userId(auth)).orElse(null)); }
    @Transactional
    public View save(Authentication auth, Update input) {
        if (!cipher.ready()) throw AiFailure.unavailable();
        long id = userId(auth);
        lockUser(id);
        if (input == null || input.model() == null || !input.model().matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,119}")) throw AiFailure.invalid();
        String url = policy.validate(input.baseUrl()).toString();
        var existing = repository.findById(id).orElse(null);
        String key = input.apiKey();
        if (key != null && !key.isEmpty() && (!key.matches("[\\x21-\\x7E]{1,2000}"))) throw AiFailure.invalid();
        boolean replacement = key != null && !key.isEmpty();
        if (!replacement && (existing == null || !existing.baseUrl.equals(url)))
            throw new AiFailure(400, "AI_KEY_REQUIRED", "首次保存或更换 API 地址时，请重新输入密钥");
        AiSettings settings = existing == null ? new AiSettings(id) : existing;
        settings.baseUrl = url;
        settings.model = input.model();
        if (replacement) settings.encryptedKey = cipher.encrypt(id, key);
        settings.updatedAt = clock.instant();
        repository.saveAndFlush(settings);
        return view(settings);
    }
    @Transactional
    public void delete(Authentication auth) {
        long id = userId(auth); lockUser(id);
        repository.findById(id).ifPresent(repository::delete);
    }
    @Transactional(readOnly = true)
    Credential credential(Authentication auth) {
        long id = userId(auth);
        AiSettings value = repository.findById(id).orElseThrow(() -> new AiFailure(409, "AI_NOT_CONFIGURED", "请先保存个人 AI 配置"));
        return new Credential(policy.validate(value.baseUrl).toString(), value.model, cipher.decrypt(id, value.encryptedKey));
    }
    record Credential(String baseUrl, String model, String key) {
        @Override public String toString() { return "AiCredential[REDACTED]"; }
    }
    private long userId(Authentication auth) { return membership.require(auth).userId(); }
    private void lockUser(long id) { entityManager.find(AppUser.class, id, LockModeType.PESSIMISTIC_WRITE); }
    private View view(AiSettings s) { return new View(s == null ? "" : s.baseUrl, s == null ? "" : s.model,
            s != null, s == null ? "" : "••••••••", cipher.ready(), policy.allowedHosts(), s == null ? null : s.updatedAt); }
}
