package net.amethystesmp.anpc.scripting;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ScriptContextTest {
    @Test
    void continuationsAreDequeuedInRegistrationOrder() {
        ScriptContext context = new ScriptContext(null, null);
        List<Integer> order = new ArrayList<>();

        context.addContinuation(() -> order.add(1));
        context.addContinuation(() -> order.add(2));

        Runnable first = context.nextContinuation();
        Runnable second = context.nextContinuation();

        assertNotNull(first);
        assertNotNull(second);
        first.run();
        second.run();

        assertEquals(List.of(1, 2), order);
        assertNull(context.nextContinuation());
    }

    @Test
    void finishClearsPendingContinuationsAndNotifiesOnce() {
        ScriptContext context = new ScriptContext(null, null);
        AtomicInteger notifications = new AtomicInteger();

        context.addContinuation(() -> fail("A finished context must not resume"));
        context.setCompletionCallback(notifications::incrementAndGet);

        context.finish();
        context.finish();

        assertTrue(context.finished());
        assertFalse(context.suspended());
        assertNull(context.nextContinuation());
        assertEquals(1, notifications.get());
    }

    @Test
    void suspensionCanBeResumedWithoutLosingQueuedContinuations() {
        ScriptContext context = new ScriptContext(null, null);
        AtomicInteger executions = new AtomicInteger();

        context.addContinuation(executions::incrementAndGet);
        context.suspend();

        assertTrue(context.suspended());
        context.resume();

        assertFalse(context.suspended());
        Runnable continuation = context.nextContinuation();
        assertNotNull(continuation);
        continuation.run();
        assertEquals(1, executions.get());
    }
}
