package com.ssuai.global.retention;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Retention windows for the daily {@link DataRetentionJob} sweep (ADR 0072, security follow-up
 * #3). Ages are measured against {@code created_at}; only terminal rows are ever eligible —
 * the windows here bound how long terminal rows are kept, never whether active rows survive.
 */
@Component
@ConfigurationProperties(prefix = "ssuai.retention")
public class DataRetentionProperties {

    private boolean enabled = true;

    /**
     * action_audit keeps 180 days: it is the single source of truth for what write actions the
     * assistant executed on a real student account (ADR 0059), so the window is deliberately
     * generous — audit value outweighs the storage cost at this scale.
     */
    private int actionAuditDays = 180;

    /** library_reservation_outbox keeps 30 days: published events are operational-only. */
    private int reservationOutboxDays = 30;

    /** library_reservation_intents keeps 30 days: terminal intents are operational-only. */
    private int reservationIntentDays = 30;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getActionAuditDays() {
        return actionAuditDays;
    }

    public void setActionAuditDays(int actionAuditDays) {
        this.actionAuditDays = positive(actionAuditDays, "actionAuditDays");
    }

    public int getReservationOutboxDays() {
        return reservationOutboxDays;
    }

    public void setReservationOutboxDays(int reservationOutboxDays) {
        this.reservationOutboxDays = positive(reservationOutboxDays, "reservationOutboxDays");
    }

    public int getReservationIntentDays() {
        return reservationIntentDays;
    }

    public void setReservationIntentDays(int reservationIntentDays) {
        this.reservationIntentDays = positive(reservationIntentDays, "reservationIntentDays");
    }

    private static int positive(int value, String field) {
        if (value <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }
}
