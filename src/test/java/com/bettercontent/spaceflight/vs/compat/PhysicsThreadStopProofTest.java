package com.bettercontent.spaceflight.vs.compat;

import com.bettercontent.spaceflight.vs.compat.internal.PhysicsThreadStopProof;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

final class PhysicsThreadStopProofTest {
    @Test void neverStartedOrNeverObservedIsNotStoppedProof() {
        var fresh = new Thread(() -> {});
        assertFalse(PhysicsThreadStopProof.terminated(fresh, true));
        assertFalse(PhysicsThreadStopProof.terminated(fresh, false));
        assertFalse(PhysicsThreadStopProof.terminated(null, true));
    }
    @Test void observedRunningThreadOnlyQualifiesAfterActualTermination() throws Exception {
        var entered = new CountDownLatch(1); var finish = new CountDownLatch(1);
        var physics = new Thread(() -> {
            entered.countDown();
            try { finish.await(3, TimeUnit.SECONDS); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
        });
        physics.start();
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertTrue(physics.isAlive());
            assertFalse(PhysicsThreadStopProof.awaitFromWatcher(physics, true, 1));
            assertFalse(PhysicsThreadStopProof.terminated(physics, true));
        } finally { finish.countDown(); physics.join(3_000); }
        assertFalse(physics.isAlive());
        assertTrue(PhysicsThreadStopProof.awaitFromWatcher(physics, true, 1));
        assertFalse(PhysicsThreadStopProof.terminated(physics, false));
    }
}
