package com.bettercontent.spaceflight.vs.compat;

import com.bettercontent.spaceflight.vs.compat.internal.JointDefinitionCodec;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import org.valkyrienskies.core.internal.joints.VSDistanceJoint;
import org.valkyrienskies.core.internal.joints.VSFixedJoint;
import org.valkyrienskies.core.internal.joints.VSJoint;
import org.valkyrienskies.core.internal.joints.VSJointMaxForceTorque;
import org.valkyrienskies.core.internal.joints.VSJointPose;

import static org.junit.jupiter.api.Assertions.*;

/** Pure pinned DTO+mapper tests. No physics world, JNI or Minecraft bootstrap. */
final class JointDefinitionCodecTest {
    private static VSJointPose pose(Vector3d position, Quaterniond orientation) {
        return new VSJointPose(position, orientation);
    }
    @Test void fixedJointCaptureNeverMutatesLiveFlagAndDetachesMutableJoml() throws Exception {
        var position = new Vector3d(1, 2, 3);
        var orientation = new Quaterniond();
        var original = new VSFixedJoint(101L, pose(position, orientation), 102L,
            pose(new Vector3d(4, 5, 6), new Quaterniond()),
            new VSJointMaxForceTorque(1_000f, 500f), 0.125);
        original.setShouldBeSerialized(false);
        var captured = JointDefinitionCodec.capture(original);
        assertFalse(original.getShouldBeSerialized());
        assertNotSame(original, captured.detached());
        assertFalse(captured.detached().getShouldBeSerialized());
        assertEquals(original, captured.detached());
        byte[] frozen = captured.bytes();
        assertTrue(JointDefinitionCodec.stillMatches(original, frozen));
        position.set(99, -3, 8);
        orientation.rotateY(0.8);
        assertFalse(JointDefinitionCodec.stillMatches(original, frozen));
        assertArrayEquals(frozen, captured.bytes());
        assertEquals(1.0, captured.detached().getPose0().getPos().x());
        assertEquals(1.0, captured.detached().getPose0().getRot().w());
        captured.bytes()[0] ^= 1;
        assertArrayEquals(frozen, captured.bytes());
        assertEquals(captured.detached(), JointDefinitionCodec.decode(frozen));
    }
    @Test void distanceJointRoundTripPreservesRequiredFlagAndConstraintParameters() throws Exception {
        var original = new VSDistanceJoint(201L, pose(new Vector3d(1, 0, 0), new Quaterniond()),
            202L, pose(new Vector3d(-1, 0, 0), new Quaterniond()),
            new VSJointMaxForceTorque(123f, 456f), 0.25, 1.0f, 12.0f, 0.1f, 3.0f, 0.5f);
        original.setShouldBeSerialized(true);
        var capture = JointDefinitionCodec.capture(original);
        assertTrue(original.getShouldBeSerialized());
        assertTrue(capture.detached().getShouldBeSerialized());
        assertEquals(original, JointDefinitionCodec.decode(capture.bytes()));
        assertTrue(JointDefinitionCodec.stillMatches(original, capture.bytes()));
    }
}
