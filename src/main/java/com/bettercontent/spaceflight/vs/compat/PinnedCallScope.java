package com.bettercontent.spaceflight.vs.compat;

/**
 * Pure identity matcher used by the pinned native hook's thread-local scope. Scope is never
 * inferred from equal IDs or equal deserialized values alone: same receiver AND same live
 * object AND matching operation are required for event or persisted-map suppression.
 */
public final class PinnedCallScope {
    public enum Mode { REMOVE, RESTORE }
    private PinnedCallScope() {}
    public static boolean matches(Object scopeReceiver, int scopeId, Object scopeJoint, Mode scopeMode,
                                  Object receiver, int id, Object joint, Mode callMode) {
        return scopeReceiver != null && scopeJoint != null && scopeMode != null && receiver != null
            && joint != null && callMode != null && scopeReceiver == receiver && scopeJoint == joint
            && scopeId == id && scopeMode == callMode;
    }
    public static boolean suppressEvent(Object scopeReceiver, int scopeId, Object scopeJoint, Mode mode,
                                        Object receiver, int id, Object joint, String operation) {
        if (mode == null || operation == null) return false;
        return matches(scopeReceiver, scopeId, scopeJoint, mode, receiver, id, joint, mode)
            && ((mode == Mode.REMOVE && operation.equals("REMOVED"))
                || (mode == Mode.RESTORE && operation.equals("RESTORED")));
    }
}
