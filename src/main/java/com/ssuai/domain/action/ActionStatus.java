package com.ssuai.domain.action;

/**
 * Lifecycle of a library write action (ADR 0015, supersede added in ADR 0055).
 *
 * <pre>
 * PENDING --claim--> EXECUTING --complete--> SUCCESS | FAILED
 * PENDING --(no confirm in TTL)--> EXPIRED
 * PENDING --(new prepare by same owner)--> SUPERSEDED
 * </pre>
 *
 * <p>{@code SUPERSEDED} is a terminal, never-executable state: when an owner prepares a
 * new action, every prior still-PENDING action of that owner is atomically moved to
 * SUPERSEDED so a later {@code confirm_action} can never execute a stale request the user
 * did not re-approve. After a prepare an owner therefore has at most one active PENDING
 * action.
 *
 * <p>The terminal outcome detail (race / auth / upstream / timeout) lives in
 * {@code action_audit.outcome_code}; this enum is the coarse lifecycle only.
 */
public enum ActionStatus {
    PENDING,
    EXECUTING,
    SUCCESS,
    FAILED,
    EXPIRED,
    SUPERSEDED
}
