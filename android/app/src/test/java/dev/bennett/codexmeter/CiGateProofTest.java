package dev.bennett.codexmeter;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Fails on purpose, to show that the required CI gate check blocks the merge. Never merge this. */
public class CiGateProofTest {
    @Test
    public void failsOnPurpose() {
        assertEquals("CI gate should block this PR", 1, 2);
    }
}
