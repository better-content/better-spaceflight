package com.bettercontent.spaceflight.vs.compat.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.valkyrienskies.core.impl.util.serialization.VSJacksonUtil;
import org.valkyrienskies.core.internal.joints.VSJoint;

import java.io.IOException;
import java.util.Arrays;

/** Exact pinned VS mapper. NEVER call VSJoint.serialized(): that method MUTATES the live joint. */
public final class JointDefinitionCodec {
    private JointDefinitionCodec() {}
    public record Capture(byte[] bytes, VSJoint detached) {
        public Capture { bytes = bytes.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
    }
    private static ObjectMapper mapper() { return VSJacksonUtil.INSTANCE.getDtoMapper(); }
    public static Capture capture(VSJoint live) throws IOException {
        boolean originalFlag = live.getShouldBeSerialized();
        byte[] bytes = mapper().writeValueAsBytes(live);
        if (live.getShouldBeSerialized() != originalFlag) throw new IOException("Codec mutated live serialization policy");
        VSJoint detached = mapper().readValue(bytes, VSJoint.class);
        if (detached == live || detached.getShouldBeSerialized() != originalFlag
            || !Arrays.equals(bytes, mapper().writeValueAsBytes(detached))) {
            throw new IOException("Joint definition/flag is not detached losslessly");
        }
        return new Capture(bytes, detached);
    }
    public static VSJoint decode(byte[] frozen) throws IOException {
        VSJoint detached = mapper().readValue(frozen, VSJoint.class);
        if (!Arrays.equals(frozen, mapper().writeValueAsBytes(detached)))
            throw new IOException("Journal joint definition changed on decode");
        return detached;
    }
    public static boolean stillMatches(VSJoint live, byte[] frozen) throws IOException {
        return Arrays.equals(mapper().writeValueAsBytes(live), frozen);
    }
}
