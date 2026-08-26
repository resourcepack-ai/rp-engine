package ai.resourcepack.engine.core.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Numbers typed into a chat box. Nothing here may throw: the argument came
 * from a person, not a config file.
 */
class ArgsTest {

    @Test
    void radiusIsClampedToSomethingAServerCanAnswer() {
        assertEquals(32, Args.radius("32"));
        assertEquals(Args.MAX_RADIUS, Args.radius("100000"));
        assertEquals(Args.MIN_RADIUS, Args.radius("-4"));
    }

    @Test
    void anUnreadableRadiusIsTheDefault() {
        assertEquals(Args.DEFAULT_RADIUS, Args.radius("sixteen"));
        assertEquals(Args.DEFAULT_RADIUS, Args.radius(""));
        assertEquals(Args.DEFAULT_RADIUS, Args.radius(null));
    }

    @Test
    void amountIsAtLeastOneAndAtMostAStack() {
        assertEquals(8, Args.amount(" 8 "));
        assertEquals(1, Args.amount("0"));
        assertEquals(99, Args.amount("64000"));
    }

    @Test
    void anUnreadableAmountIsOne() {
        assertEquals(1, Args.amount("lots"));
        assertEquals(1, Args.amount(null));
    }
}
