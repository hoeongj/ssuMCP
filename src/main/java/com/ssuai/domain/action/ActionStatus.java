package com.ssuai.domain.action;

/**
 * Lifecycle of a library write action (ADR 0015).
 *
 * <pre>
 * PENDING --claim--> EXECUTING --complete--> SUCCESS | FAILED
 * PENDING --(no confirm in TTL)--> EXPIRED
 * </pre>
 *
 * The terminal outcome detail (race / auth / upstream / timeout) lives in
 * {@code action_audit.outcome_code}; this enum is the coarse lifecycle only.
 */
public enum ActionStatus {
    PENDING,
    EXECUTING,
    SUCCESS,
    FAILED,
    EXPIRED
}
