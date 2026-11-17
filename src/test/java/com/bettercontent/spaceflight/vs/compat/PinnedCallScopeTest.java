package com.bettercontent.spaceflight.vs.compat;

import org.junit.jupiter.api.Test;

import static com.bettercontent.spaceflight.vs.compat.PinnedCallScope.Mode.*;
import static org.junit.jupiter.api.Assertions.*;

/** Pure decisions called by real FU redirects; not a transformed/native Mixin test. */
final class PinnedCallScopeTest {
    @Test void onlyExactReceiverIdAndLiveObjectHideScopedRemoved() {
        Object source = new Object(), unrelated = new Object(), live = new Object();
        Object deserializedEqualButNotIdentical = new Object();
        assertTrue(PinnedCallScope.matches(source, 7, live, REMOVE, source, 7, live, REMOVE));
        assertTrue(PinnedCallScope.suppressEvent(source, 7, live, REMOVE, source, 7, live, "REMOVED"));
        assertFalse(PinnedCallScope.suppressEvent(source, 7, live, REMOVE, unrelated, 7, live, "REMOVED"));
        assertFalse(PinnedCallScope.suppressEvent(source, 7, live, REMOVE, source, 8, live, "REMOVED"));
        assertFalse(PinnedCallScope.suppressEvent(source, 7, live, REMOVE, source, 7,
            deserializedEqualButNotIdentical, "REMOVED"));
    }
    @Test void nestedUnrelatedCallbackCannotBorrowOuterSuppression() {
        Object level = new Object(), joint = new Object(), unrelated = new Object();
        assertFalse(PinnedCallScope.suppressEvent(level, 17, joint, REMOVE,
            level, 18, unrelated, "REMOVED"));
        assertFalse(PinnedCallScope.matches(level, 17, joint, REMOVE, level, 17, joint, RESTORE));
        assertTrue(PinnedCallScope.suppressEvent(level, 17, joint, REMOVE,
            level, 17, joint, "REMOVED"));
    }
    @Test void restoredEventSuppressionIsNotGeneralRemovedEventSuppression() {
        Object dest = new Object(), joint = new Object();
        assertTrue(PinnedCallScope.suppressEvent(dest, 23, joint, RESTORE, dest, 23, joint, "RESTORED"));
        assertFalse(PinnedCallScope.suppressEvent(dest, 23, joint, RESTORE, dest, 23, joint, "REMOVED"));
        assertFalse(PinnedCallScope.suppressEvent(dest, 23, joint, REMOVE, dest, 23, joint, "RESTORED"));
    }
    @Test void unscopedAndWrongModeNeverBypassNativeChecks() {
        Object dest = new Object(), joint = new Object();
        assertFalse(PinnedCallScope.matches(null, 3, joint, RESTORE, dest, 3, joint, RESTORE));
        assertFalse(PinnedCallScope.matches(dest, 3, null, RESTORE, dest, 3, joint, RESTORE));
        assertFalse(PinnedCallScope.matches(dest, 3, joint, REMOVE, dest, 3, joint, RESTORE));
    }
}
