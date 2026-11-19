package net.amethystesmp.anpc.scripting;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

class ScriptEngineExpressionTest {
    private boolean test(ScriptEngine engine, ScriptContext context, String expression) throws Exception {
        Method method = ScriptEngine.class.getDeclaredMethod("test", ScriptContext.class, String.class);
        method.setAccessible(true);
        return (boolean) method.invoke(engine, context, expression);
    }

    @Test
    void evaluatesBooleanOperatorsWithExpectedPrecedence() throws Exception {
        ScriptEngine engine = new ScriptEngine(null, null, null);
        ScriptContext context = new ScriptContext(null, null);
        context.locals().put("a", true);
        context.locals().put("b", false);
        context.locals().put("c", true);

        assertTrue(test(engine, context, "a and not b"));
        assertTrue(test(engine, context, "a and b or c"));
        assertFalse(test(engine, context, "a and b"));
        assertFalse(test(engine, context, "not a"));
    }

    @Test
    void comparesTextWithoutTreatingAllTextAsZero() throws Exception {
        ScriptEngine engine = new ScriptEngine(null, null, null);
        ScriptContext context = new ScriptContext(null, null);
        context.locals().put("first", "alpha");
        context.locals().put("second", "beta");

        assertFalse(test(engine, context, "first == second"));
        assertTrue(test(engine, context, "first != second"));
        assertTrue(test(engine, context, "first < second"));
    }

    @Test
    void supportsNumericExpressionsAndRepeatedContainsChecks() throws Exception {
        ScriptEngine engine = new ScriptEngine(null, null, null);
        ScriptContext context = new ScriptContext(null, null);
        context.locals().put("amount", 3.0);
        context.locals().put("items", "apple,apple,pear");
        context.locals().put("apple", "apple");

        assertTrue(test(engine, context, "amount + 2 == 5"));
        assertTrue(test(engine, context, "(items) contains (apple) more than 1 times"));
        assertFalse(test(engine, context, "(items) contains ("pear") more than 1 times"));
    }
}
