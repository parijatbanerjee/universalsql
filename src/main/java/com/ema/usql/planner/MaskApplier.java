package com.ema.usql.planner;

import org.springframework.stereotype.Service;

/**
 * Applies column-level security (CLS) masks to plaintext field values.
 * Called after decryption, before returning data to the caller.
 */
@Service
public class MaskApplier {

    /**
     * Apply a mask to an email address.
     *
     * @param email    the plaintext email address
     * @param maskType the mask type (REDACT, PARTIAL, FULL)
     * @return the masked email string
     */
    public String maskEmail(String email, String maskType) {
        if (email == null) {
            return null;
        }
        return switch (maskType.toUpperCase()) {
            case "PARTIAL" -> partialMask(email);
            case "FULL", "REDACT" -> "***";
            default -> "***";
        };
    }

    /**
     * Apply a mask to any string value.
     *
     * @param value    the plaintext value
     * @param maskType the mask type
     * @return the masked value
     */
    public String maskValue(String value, String maskType) {
        if (value == null) {
            return null;
        }
        return switch (maskType.toUpperCase()) {
            case "PARTIAL" -> partialMaskGeneric(value);
            case "FULL", "REDACT" -> "***";
            default -> "***";
        };
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Partial mask for email: first char + *** + @domain.
     * e.g. "john@acme.com" → "j***@acme.com"
     */
    private String partialMask(String email) {
        int atIndex = email.indexOf('@');
        if (atIndex <= 0) {
            // Not a valid email — mask everything except first char
            if (email.length() <= 1) {
                return email;
            }
            return email.charAt(0) + "***";
        }
        return email.charAt(0) + "***" + email.substring(atIndex);
    }

    /**
     * Generic partial mask: first char + *** (no @ handling).
     */
    private String partialMaskGeneric(String value) {
        if (value.isEmpty()) {
            return value;
        }
        return value.charAt(0) + "***";
    }
}
