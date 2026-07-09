package com.ssuai.domain.action;

/**
 * Lifecycle of a library write action (ADR 0015, supersede added in ADR 0055, supersede scope
 * narrowed to {@code (studentId, actionType, targetKey)} in ADR 0086).
 *
 * <pre>
 * PENDING --claim--> EXECUTING --complete--> SUCCESS | FAILED
 * PENDING --(no confirm in TTL)--> EXPIRED
 * PENDING --(re-prepare of the SAME action by the same owner)--> SUPERSEDED
 * </pre>
 *
 * <p>{@code SUPERSEDED} is a terminal, never-executable state: when an owner re-prepares the
 * <em>same</em> action (same {@code actionType} + {@code targetKey} — e.g. the same seat, the
 * same charge id), the prior still-PENDING row for that action is atomically moved to
 * SUPERSEDED so a later {@code confirm_action} can never execute a stale request the user did
 * not re-approve. A DIFFERENT target (e.g. a second, different seat reservation) is left
 * untouched and can coexist as its own PENDING row — {@code confirm_action}'s {@code action_id}
 * targeting and 0/1/N disambiguation (ADR 0055) is what keeps concurrently-pending actions safe
 * to confirm without guessing which one the caller means.
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
