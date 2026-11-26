package dev.bennett.codexmeter.glass;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** A balance card's row grows with its summary, so a long explanation isn't clipped. */
public class BalanceRowHeightTest {
    @Test
    public void rowFitsItsSummary() {
        assertEquals(52, CodexGlass.balanceHeight(null), 0);
        assertEquals(52, CodexGlass.balanceHeight(""), 0);
        assertEquals(66, CodexGlass.balanceHeight("$12.40 left"), 0);
        // OpenCode Free's explanation wraps to three lines on a phone.
        String explanation = "OpenCode counts free-model requests from your network each day and "
                + "resets them at midnight UTC. It doesn't share how many are left.";
        assertEquals(99, CodexGlass.balanceHeight(explanation), 0);
        assertTrue(CodexGlass.balanceHeight(explanation + explanation) <= 99);
    }
}
