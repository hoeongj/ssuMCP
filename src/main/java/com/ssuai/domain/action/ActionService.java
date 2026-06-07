package com.ssuai.domain.action;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ActionService {

    public static final Duration ACTION_TTL = Duration.ofMinutes(5);

    private final ActionAuditRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final MeterRegistry meterRegistry;

    @Autowired
    public ActionService(ActionAuditRepository repository, Clock clock, MeterRegistry meterRegistry) {
        this(repository, new ObjectMapper(), clock, meterRegistry);
    }

    ActionService(ActionAuditRepository repository, ObjectMapper objectMapper, Clock clock) {
        this(repository, objectMapper, clock, new SimpleMeterRegistry());
    }

    ActionService(ActionAuditRepository repository, ObjectMapper objectMapper, Clock clock, MeterRegistry meterRegistry) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.meterRegistry = meterRegistry;
    }

    @Transactional
    public ActionAudit createPendingAction(String studentId, String actionType, Object payload) {
        String serialized = serialize(payload);
        ActionAudit action = ActionAudit.pending(studentId, actionType, serialized, clock.instant());
        ActionAudit saved = repository.save(action);
        meterRegistry.counter("library.action", "action_type", actionType, "status", "prepared").increment();
        return saved;
    }

    @Transactional(readOnly = true)
    public Optional<ActionAudit> findPendingAction(String studentId) {
        return repository.findTopByStudentIdAndStatusOrderByCreatedAtDesc(studentId, ActionStatus.PENDING);
    }

    @Transactional
    public ActionAudit confirmAction(String studentId) {
        ActionAudit action = findPendingAction(studentId).orElseThrow(NoPendingActionException::new);
        Instant now = clock.instant();
        if (isExpired(action, now)) {
            action.expire(now);
            repository.save(action);
            meterRegistry.counter("library.action", "action_type", action.getActionType(), "status", "expired").increment();
            throw new ActionExpiredException();
        }
        action.confirm(now);
        ActionAudit saved = repository.save(action);
        meterRegistry.counter("library.action", "action_type", action.getActionType(), "status", "confirmed").increment();
        return saved;
    }

    @Transactional
    @Scheduled(fixedDelay = 60_000)
    public void expireStaleActions() {
        Instant now = clock.instant();
        Instant cutoff = now.minus(ACTION_TTL);
        List<ActionAudit> staleActions =
                repository.findAllByStatusAndCreatedAtBefore(ActionStatus.PENDING, cutoff);
        if (staleActions.isEmpty()) {
            return;
        }
        staleActions.forEach(action -> action.expire(now));
        repository.saveAll(staleActions);
        meterRegistry.counter("library.action", "action_type", "mixed", "status", "expired")
                .increment(staleActions.size());
    }

    public boolean isExpired(ActionAudit action) {
        return isExpired(action, clock.instant());
    }

    public <T> T payload(ActionAudit action, Class<T> payloadType) {
        try {
            return objectMapper.readValue(action.getPayload(), payloadType);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Action payload cannot be parsed.", exception);
        }
    }

    private boolean isExpired(ActionAudit action, Instant now) {
        return action.getCreatedAt().plus(ACTION_TTL).isBefore(now);
    }

    private String serialize(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Action payload cannot be serialized.", exception);
        }
    }

    public static class NoPendingActionException extends RuntimeException {
    }

    public static class ActionExpiredException extends RuntimeException {
    }
}
