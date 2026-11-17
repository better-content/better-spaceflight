package com.bettercontent.spaceflight.vs;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class AssemblyTranslationTest {
    @Test void reproducesNegativeShipyardComEpsilonAndUsesUnmodifiedGeometricCenter() {
        var shiftedCom = new Vector3d(3.5000000298023224, -56.459266802444006, 3.4999999962747097);
        var placement = new Vector3d(-28669951.5, 128.0, 12290047.5);
        var old = new Vector3d(2, -57, 2).sub(shiftedCom).add(placement);
        assertEquals(-28669954, (int) Math.floor(old.x)); // actual failed assertion coordinate
        var source = AssemblyTranslation.Center.fromBounds(2, -57, 2, 4, -56, 4);
        var translation = AssemblyTranslation.between(source, AssemblyTranslation.Center.snapshot(placement));
        assertEquals(new AssemblyTranslation(-28669955, 184, 12290044), translation);
        assertEquals(-28669953, 2 + translation.x());
        assertEquals(127, -57 + translation.y());
        assertEquals(12290046, 2 + translation.z());
    }

    @Test void positiveAndNegativeOddEvenBoundsProduceExactOffsets() {
        var odd = AssemblyTranslation.Center.fromBounds(2, -57, 2, 4, -56, 4);
        assertEquals(new AssemblyTranslation.Center(3.5, -56.0, 3.5), odd);
        assertEquals(new AssemblyTranslation(97, 184, 197),
            AssemblyTranslation.between(odd, new AssemblyTranslation.Center(100.5, 128, 200.5)));
        var even = AssemblyTranslation.Center.fromBounds(-6, -10, -8, -3, -7, -5);
        assertEquals(new AssemblyTranslation.Center(-4, -8, -6), even);
        assertEquals(new AssemblyTranslation(-96, -192, -294),
            AssemblyTranslation.between(even, new AssemblyTranslation.Center(-100, -200, -300)));
    }

    @Test void snapshotsIsolateSubsequentInputVectorMutation() {
        var sourceVector = new Vector3d(-4, -8, -6);
        var destinationVector = new Vector3d(-100, -200, -300);
        var source = AssemblyTranslation.Center.snapshot(sourceVector);
        var destination = AssemblyTranslation.Center.snapshot(destinationVector);
        var before = AssemblyTranslation.between(source, destination);
        sourceVector.add(700, 800, 900);
        destinationVector.zero();
        assertEquals(new AssemblyTranslation(-96, -192, -294), before);
        assertEquals(before, AssemblyTranslation.between(source, destination));
    }

    @Test void halfBlockTranslationIsRejectedRatherThanRounded() {
        var source = new AssemblyTranslation.Center(0.5, 0, 0);
        assertThrows(IllegalArgumentException.class,
            () -> AssemblyTranslation.between(source, new AssemblyTranslation.Center(1, 0, 0)));
    }

    @Test void rejectsTinyComEpsilonAndQuarterBlockCentersWithoutTolerance() {
        assertThrows(IllegalArgumentException.class,
            () -> AssemblyTranslation.Center.snapshot(new Vector3d(Math.nextUp(-28669951.5), 128, 12290047.5)));
        assertThrows(IllegalArgumentException.class, () -> new AssemblyTranslation.Center(0.25, 0, 0));
        assertThrows(IllegalArgumentException.class,
            () -> AssemblyTranslation.Center.snapshot(new Vector3d(3.5000000298023224, -56.0, 3.5)));
    }

    @Test void rejectsNonfiniteCoordinatesAndOutOfRangeTranslations() {
        for (double value : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> new AssemblyTranslation.Center(value, 0, 0));
            assertThrows(IllegalArgumentException.class, () -> new AssemblyTranslation.Center(0, value, 0));
            assertThrows(IllegalArgumentException.class, () -> new AssemblyTranslation.Center(0, 0, value));
        }
        var zero = new AssemblyTranslation.Center(0, 0, 0);
        assertThrows(IllegalArgumentException.class,
            () -> AssemblyTranslation.between(zero, new AssemblyTranslation.Center(2147483648.0, 0, 0)));
        assertThrows(IllegalArgumentException.class,
            () -> AssemblyTranslation.between(zero, new AssemblyTranslation.Center(0, -2147483649.0, 0)));
    }

    @Test void boundingBoxCenterCalculationDoesNotOverflowAndRejectsInversion() {
        assertEquals(new AssemblyTranslation.Center(0, 0, 0), AssemblyTranslation.Center.fromBounds(
            Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE,
            Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> AssemblyTranslation.Center.fromBounds(1, 0, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> AssemblyTranslation.Center.fromBounds(0, 1, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> AssemblyTranslation.Center.fromBounds(0, 0, 1, 0, 0, 0));
    }

    @Test void exactIntegerTranslationLimitsRemainSupported() {
        assertEquals(new AssemblyTranslation(Integer.MAX_VALUE, 0, Integer.MIN_VALUE),
            AssemblyTranslation.between(new AssemblyTranslation.Center(0, 0, 0),
                new AssemblyTranslation.Center(Integer.MAX_VALUE, 0, Integer.MIN_VALUE)));
    }
}
