package com.ssuai.domain.action;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    @Autowired
    public ActionService(ActionAuditRepository repository, Clock clock) {
        this(repository, new ObjectMapper(), clock);
    }

    ActionService(ActionAuditRepository repository, ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public ActionAudit createPendingAction(String studentId, String actionType, Object payload) {
        String serialized = serialize(payload);
        ActionAudit action = ActionAudit.pending(studentId, actionType, serialized, clock.instant());
        return repository.save(action);
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
            throw new ActionExpiredException();
        }
        action.confirm(now);
        return repository.save(action);
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
