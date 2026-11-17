package com.bettercontent.spaceflight.vs.compat.recovery;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/** Synthetic provider metadata; never invokes the original serialization twice. */
final class ForgeChunkCapabilityEvidenceTest {
    private static final Object OWNER = new Object(), DISPATCH = new Object(), WRITER = new Object();
    private static ForgeChunkCapabilityEvidence.Snapshot state(boolean lazy, boolean initialized,
        boolean supplier, Object dispatcher, String[] names, Object[] writers, CompoundTag cached) {
        return new ForgeChunkCapabilityEvidence.Snapshot(OWNER, lazy, initialized, supplier,
            dispatcher, names, writers, cached);
    }
    private static CompoundTag output(CompoundTag caps) {
        var tag = new CompoundTag(); if (caps != null) tag.put("ForgeCaps", caps); return tag;
    }
    @Test void exactMappedProviderAndChunkSerializerBytecodeMatchesPinnedCallsite() throws Exception {
        ForgeChunkCapabilityEvidence.verifyClasses(); // no Minecraft/Forge runtime execution
    }
    @Test void actualNoProviderAndLazyCachedPayloadAreDistinctProvenCases() throws Exception {
        var empty = state(false, true, false, null, null, null, null);
        assertFalse(ForgeChunkCapabilityEvidence.validate(empty, empty, output(null)));
        var cached = new CompoundTag(); cached.putInt("example:data", 42);
        var lazy = state(true, false, true, null, null, null, cached);
        assertTrue(ForgeChunkCapabilityEvidence.validate(lazy, lazy, output(cached)));
        var changed = new CompoundTag(); changed.putInt("example:data", 41);
        assertThrows(IOException.class, () -> ForgeChunkCapabilityEvidence.validate(lazy, lazy, output(changed)));
        assertThrows(IOException.class, () -> ForgeChunkCapabilityEvidence.validate(lazy, lazy, output(null)));
        var unknown = state(true, false, true, null, null, null, null);
        assertThrows(IOException.class, () -> ForgeChunkCapabilityEvidence.validate(unknown, unknown, output(null)));
    }
    @Test void initializedProviderRequiresEveryExactNamedWriterAndNoExtraKeys() throws Exception {
        var names = new String[]{"example:first", "example:second"};
        var writers = new Object[]{WRITER, new Object()};
        var ready = state(false, true, false, DISPATCH, names, writers, null);
        var caps = new CompoundTag(); caps.putInt(names[0], 1); caps.putString(names[1], "data");
        assertTrue(ForgeChunkCapabilityEvidence.validate(ready, ready, output(caps)));
        assertThrows(IOException.class, () -> ForgeChunkCapabilityEvidence.validate(ready, ready, output(null)));
        caps.remove(names[1]);
        assertThrows(IOException.class, () -> ForgeChunkCapabilityEvidence.validate(ready, ready, output(caps)));
        caps.putString(names[1], "data"); caps.putInt("example:extra", 1);
        assertThrows(IOException.class, () -> ForgeChunkCapabilityEvidence.validate(ready, ready, output(caps)));
    }
    @Test void duplicateOrMalformedNamesMismatchedArraysAndChangedRegistrationRefuse() throws Exception {
        var caps = new CompoundTag(); caps.putInt("example:first", 1);
        var duplicate = state(false, true, false, DISPATCH,
            new String[]{"example:first", "example:first"}, new Object[]{WRITER, WRITER}, null);
        assertThrows(IOException.class, () -> ForgeChunkCapabilityEvidence.validate(duplicate, duplicate, output(caps)));
        var malformed = state(false, true, false, DISPATCH,
            new String[]{"not valid:name"}, new Object[]{WRITER}, null);
        assertThrows(IOException.class, () -> ForgeChunkCapabilityEvidence.validate(malformed, malformed, output(caps)));
        var differentLength = state(false, true, false, DISPATCH,
            new String[]{"example:first"}, new Object[]{WRITER, WRITER}, null);
        assertThrows(IOException.class, () -> ForgeChunkCapabilityEvidence.validate(differentLength, differentLength, output(caps)));
        var before = state(false, true, false, DISPATCH,
            new String[]{"example:first"}, new Object[]{WRITER}, null);
        var after = state(false, true, false, DISPATCH,
            new String[]{"example:first"}, new Object[]{new Object()}, null);
        assertThrows(IOException.class, () -> ForgeChunkCapabilityEvidence.validate(before, after, output(caps)));
        var otherOwner = new ForgeChunkCapabilityEvidence.Snapshot(new Object(), false, true, false,
            DISPATCH, new String[]{"example:first"}, new Object[]{WRITER}, null);
        assertThrows(IOException.class, () -> ForgeChunkCapabilityEvidence.validate(before, otherOwner, output(caps)));
    }
}
