package net.amethystesmp.anpc.scripting;

import org.junit.jupiter.api.Test;
import org.bukkit.Material;
import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;

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
    @Test
    void eventItemIsCopiedWhenStoredAndRead() {
        ScriptContext context = new ScriptContext(null, null);
        ItemStack original = new ItemStack(Material.STONE, 1);

        context.setEventItem(original);
        original.setAmount(5);

        assertEquals(1, context.eventItem().getAmount());
        ItemStack exposed = context.eventItem();
        exposed.setAmount(9);
        assertEquals(1, context.eventItem().getAmount());
    }

    @Test
    void eventPlaceIsReturnedAsACopy() {
        ScriptContext context = new ScriptContext(null, null);
        Location original = new Location(null, 1, 2, 3);

        context.setEventPlace(original);
        original.setX(50);

        assertEquals(1, context.eventPlace().getX());
        Location exposed = context.eventPlace();
        exposed.setX(90);
        assertEquals(1, context.eventPlace().getX());
    }

}
