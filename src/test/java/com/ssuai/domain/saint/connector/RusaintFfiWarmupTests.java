package com.ssuai.domain.saint.connector;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

/**
 * The boot warmup must invoke {@link RusaintClient#warmUp()} off the caller's
 * thread and never let a warmup failure escape (a broken native layer must not
 * crash startup — login would still work by paying the cold cost lazily).
 */
class RusaintFfiWarmupTests {

    @Test
    void runInvokesWarmUpAsynchronously() {
        RusaintClient client = mock(RusaintClient.class);

        new RusaintFfiWarmup(client).run(new DefaultApplicationArguments());

        // Async on a daemon thread — allow it to schedule and run.
        verify(client, timeout(2000)).warmUp();
    }

    @Test
    void runDoesNotPropagateWarmUpFailure() {
        RusaintClient client = mock(RusaintClient.class);
        doThrow(new RuntimeException("native load boom")).when(client).warmUp();

        // run() returns immediately (work is on a daemon thread); the exception
        // is confined to that thread and must not surface here.
        new RusaintFfiWarmup(client).run(new DefaultApplicationArguments());

        verify(client, timeout(2000)).warmUp();
    }
}
