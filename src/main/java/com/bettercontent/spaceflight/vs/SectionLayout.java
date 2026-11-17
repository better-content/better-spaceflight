package com.bettercontent.spaceflight.vs;

/** VS 2.4.11 copies chunk sections by index, so equal section counts alone are insufficient. */
public record SectionLayout(int minimumY, int height) {
    public SectionLayout {
        if (Math.floorMod(minimumY, 16) != 0 || height <= 0 || height % 16 != 0) {
            throw new IllegalArgumentException("Invalid vertical section layout: minimumY=" + minimumY + ", height=" + height);
        }
    }
    public void requireCompatible(SectionLayout destination) {
        if (!equals(destination)) {
            throw new IllegalArgumentException("Pinned VS transfer requires identical source/destination section layouts: "
                + this + " -> " + destination);
        }
    }
}
