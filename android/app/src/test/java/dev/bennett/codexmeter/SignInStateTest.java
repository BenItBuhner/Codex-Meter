package dev.bennett.codexmeter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Transitions of the sign-in screen and of the sign-in service's attempts. */
public class SignInStateTest {
    @Test
    public void browserSignInWaitsThenConnects() {
        SignInFlow flow = new SignInFlow();
        assertTrue(flow.on(SignInFlow.Event.SERVICE_STARTED));
        assertTrue(flow.waitingForService());
        assertTrue("asking again resends the link", flow.on(SignInFlow.Event.SERVICE_STARTED));
        assertTrue(flow.on(SignInFlow.Event.SERVICE_SUCCEEDED));
        assertEquals(SignInFlow.Phase.CONNECTED, flow.phase());
        assertFalse("nothing leaves CONNECTED", flow.on(SignInFlow.Event.SERVICE_STARTED));
    }

    @Test
    public void cancelAndFailureReturnToIdle() {
        SignInFlow flow = new SignInFlow();
        flow.on(SignInFlow.Event.RESUME_PENDING);
        assertTrue(flow.waitingForService());
        assertTrue(flow.on(SignInFlow.Event.CANCELLED));
        assertEquals(SignInFlow.Phase.IDLE, flow.phase());
        flow.on(SignInFlow.Event.SERVICE_STARTED);
        assertTrue(flow.on(SignInFlow.Event.SERVICE_FAILED));
        assertEquals(SignInFlow.Phase.IDLE, flow.phase());
        flow.on(SignInFlow.Event.SERVICE_STARTED);
        assertTrue(flow.on(SignInFlow.Event.SERVICE_FAILED_TO_START));
        assertEquals(SignInFlow.Phase.IDLE, flow.phase());
    }

    @Test
    public void pasteConnectIsExclusiveAndSurvivesAStaleServiceFailure() {
        SignInFlow flow = new SignInFlow();
        assertTrue(flow.on(SignInFlow.Event.CONNECT));
        assertFalse("a second tap is ignored", flow.on(SignInFlow.Event.CONNECT));
        assertFalse("no browser sign-in while checking", flow.on(SignInFlow.Event.SERVICE_STARTED));
        assertFalse(flow.on(SignInFlow.Event.CANCELLED));
        flow.on(SignInFlow.Event.SERVICE_FAILED);
        assertTrue("a late service failure doesn't end the check", flow.connecting());
        assertTrue(flow.on(SignInFlow.Event.CONNECT_FAILED));
        assertEquals(SignInFlow.Phase.IDLE, flow.phase());
        assertFalse("only a running check can succeed", flow.on(SignInFlow.Event.CONNECT_SUCCEEDED));
        flow.on(SignInFlow.Event.CONNECT);
        assertTrue(flow.on(SignInFlow.Event.CONNECT_SUCCEEDED));
        assertEquals(SignInFlow.Phase.CONNECTED, flow.phase());
    }

    @Test
    public void oneAttemptAtATime() {
        SignInAttempts attempts = new SignInAttempts();
        SignInAttempts.Attempt first = attempts.begin(UsageProvider.CODEX);
        assertNotNull(first);
        assertNull("busy while one is current", attempts.begin(UsageProvider.CODEX));
        assertFalse(attempts.shouldStop(first));
        assertFalse("the main thread sees a running service", attempts.shouldStop(null));
        assertTrue(attempts.finish(first));
        assertEquals(SignInAttempts.State.FINISHED, first.state());
        assertNull(attempts.current());
        assertTrue(attempts.shouldStop(null));
    }

    @Test
    public void aCancelledAttemptStaysStoppedAfterANewOneStarts() {
        SignInAttempts attempts = new SignInAttempts();
        SignInAttempts.Attempt old = attempts.begin(UsageProvider.CODEX);
        attempts.cancel();
        assertEquals(SignInAttempts.State.CANCELLED, old.state());
        SignInAttempts.Attempt fresh = attempts.begin(UsageProvider.CODEX);
        assertSame(fresh, attempts.current());
        assertTrue("the old loop never revives", attempts.shouldStop(old));
        assertTrue(attempts.superseded(old));
        assertFalse("the old thread can't finish the new attempt", attempts.finish(old));
        assertSame(fresh, attempts.current());
        assertFalse(attempts.shouldStop(fresh));
        assertFalse(attempts.superseded(fresh));
    }

    @Test
    public void theMainThreadCanAlwaysStopTheService() {
        SignInAttempts attempts = new SignInAttempts();
        SignInAttempts.Attempt running = attempts.begin(UsageProvider.CODEX);
        assertTrue(attempts.finish(null));
        assertEquals(SignInAttempts.State.FINISHED, running.state());
        assertTrue(attempts.shouldStop(running));
        assertTrue("finishing when idle is harmless", attempts.finish(null));
    }
}
