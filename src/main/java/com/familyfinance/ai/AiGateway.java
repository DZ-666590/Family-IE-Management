package com.familyfinance.ai;

import org.springframework.security.core.Authentication;

/**
 * Business modules must obtain explicit user consent before passing text here.
 * No tools, automatic finance lookup, persistence, or unauthenticated HTTP proxy.
 */
public interface AiGateway {
    String complete(Authentication authentication, Prompt prompt);
    record Prompt(String text, boolean userApprovedExternalProcessing, java.util.List<DocumentImage> images) {
        public Prompt(String text, boolean approved) { this(text, approved, java.util.List.of()); }
        public Prompt { images = images == null ? java.util.List.of() : java.util.List.copyOf(images); }
        @Override public String toString() { return "AiPrompt[REDACTED]"; }
    }
    record DocumentImage(String mimeType, byte[] bytes) {
        public DocumentImage { bytes = bytes == null ? new byte[0] : bytes.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
        @Override public String toString() { return "DocumentImage[REDACTED]"; }
    }
}
