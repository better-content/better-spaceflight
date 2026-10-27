package com.bettercontent.spaceflight.vs;

import org.joml.Vector3dc;

/** Exact voxel-placement translation, deliberately independent of body/COM transforms. */
public record AssemblyTranslation(int x, int y, int z) {
    /** Primitive snapshot: never retain a mutable vector owned by VS or its caller. */
    public record Center(double x, double y, double z) {
        public Center {
            if (!halfIntegral(x) || !halfIntegral(y) || !halfIntegral(z)) {
                throw new IllegalArgumentException("Assembly geometric center must be finite and exactly half-integral");
            }
        }

        private static boolean halfIntegral(double coordinate) {
            return Double.isFinite(coordinate) && coordinate == Math.rint(coordinate * 2.0) / 2.0;
        }

        public static Center snapshot(Vector3dc vector) {
            return new Center(vector.x(), vector.y(), vector.z());
        }

        public static Center fromBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
            if (minX > maxX || minY > maxY || minZ > maxZ) {
                throw new IllegalArgumentException("Assembly bounds are inverted");
            }
            // Include the entire last voxel; promote before addition to avoid integer overflow.
            return new Center(((double) minX + maxX + 1.0) / 2.0,
                ((double) minY + maxY + 1.0) / 2.0,
                ((double) minZ + maxZ + 1.0) / 2.0);
        }
    }

    public static AssemblyTranslation between(Center originalGeometricCenter, Center placementCenter) {
        return new AssemblyTranslation(exactOffset(placementCenter.x - originalGeometricCenter.x),
            exactOffset(placementCenter.y - originalGeometricCenter.y),
            exactOffset(placementCenter.z - originalGeometricCenter.z));
    }

    private static int exactOffset(double value) {
        // Both geometric centers are integral/half-integral. No rounding tolerance is needed.
        if (!Double.isFinite(value) || value != Math.rint(value)
                || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Assembly placement is not an exact integer translation: " + value);
        }
        return (int) value;
    }
}
