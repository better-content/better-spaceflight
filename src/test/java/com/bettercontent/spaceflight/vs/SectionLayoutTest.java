package com.bettercontent.spaceflight.vs;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SectionLayoutTest {
    @Test void acceptsEqualOverworldLayouts() {
        assertDoesNotThrow(() -> new SectionLayout(-64, 384).requireCompatible(new SectionLayout(-64, 384)));
    }
    @Test void rejectsNetherHeightBeforeNativeCopy() {
        assertThrows(IllegalArgumentException.class,
            () -> new SectionLayout(-64, 384).requireCompatible(new SectionLayout(0, 256)));
    }
    @Test void rejectsEqualCountWithDifferentMinimum() {
        assertThrows(IllegalArgumentException.class,
            () -> new SectionLayout(-64, 384).requireCompatible(new SectionLayout(0, 384)));
    }
    @Test void rejectsMalformedLayouts() {
        assertThrows(IllegalArgumentException.class, () -> new SectionLayout(-63, 384));
        assertThrows(IllegalArgumentException.class, () -> new SectionLayout(-64, 383));
        assertThrows(IllegalArgumentException.class, () -> new SectionLayout(-64, 0));
        assertThrows(IllegalArgumentException.class, () -> new SectionLayout(-64, -16));
    }
}
