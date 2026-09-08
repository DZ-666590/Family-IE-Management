package com.familyfinance.ai;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "user_ai_settings")
class AiSettings {
    @Id @Column(name = "user_id") Long userId;
    @Column(name = "base_url", nullable = false, length = 500) String baseUrl;
    @Column(nullable = false, length = 120) String model;
    @Column(name = "encrypted_key", nullable = false, length = 3000) String encryptedKey;
    @Column(name = "updated_at", nullable = false) Instant updatedAt;
    protected AiSettings() {}
    AiSettings(long userId) { this.userId = userId; }
}
