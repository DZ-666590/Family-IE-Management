package com.familyfinance.ai;

import org.springframework.security.core.Authentication;

/**
 * Business modules must obtain explicit user consent before passing text here.
 * No tools, automatic finance lookup, persistence, or unauthenticated HTTP proxy.
 */
public interface AiGateway {
    String complete(Authentication authentication, Prompt prompt);
    record Prompt(String text, boolean userApprovedExternalProcessing) {
        @Override public String toString() { return "AiPrompt[REDACTED]"; }
    }
}
