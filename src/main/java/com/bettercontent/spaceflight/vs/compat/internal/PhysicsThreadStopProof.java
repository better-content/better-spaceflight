package com.bettercontent.spaceflight.vs.compat.internal;

/**
 * Only an actual previously observed alive FQ.h thread reaching TERMINATED proves
 * the physics LOOP ended; it does not prove FN.j asynchronous native close finished.
 * Call await once from a dedicated watcher, never the Minecraft or physics thread.
 */
public final class PhysicsThreadStopProof {
    private PhysicsThreadStopProof() {}
    public static boolean terminated(Thread observedThread, boolean wasObservedAlive) {
        return wasObservedAlive && observedThread != null && !observedThread.isAlive()
            && observedThread.getState() == Thread.State.TERMINATED;
    }
    public static boolean awaitFromWatcher(Thread observedThread, boolean wasObservedAlive, long millis)
        throws InterruptedException {
        if (observedThread == null || !wasObservedAlive || millis <= 0
            || Thread.currentThread() == observedThread) return false;
        observedThread.join(millis);
        return terminated(observedThread, wasObservedAlive);
    }
}
