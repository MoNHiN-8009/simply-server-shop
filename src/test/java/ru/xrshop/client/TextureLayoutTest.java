package ru.xrshop.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextureLayoutTest {
    @Test void coverPreservesAspectAndCropsAroundAnchor() {
        TextureLayout.Placement placement = TextureLayout.calculate("COVER", "CENTER", 200, 100,
                10, 20, 100, 100, 0, 0, 100);
        assertEquals(10 - 50, placement.x());
        assertEquals(20, placement.y());
        assertEquals(200, placement.width());
        assertEquals(100, placement.height());
    }

    @Test void containAndOriginalRespectAnchorOffsetAndScale() {
        TextureLayout.Placement contain = TextureLayout.calculate("CONTAIN", "BOTTOM_RIGHT", 200, 100,
                0, 0, 100, 100, -3, -4, 100);
        assertEquals(-3, contain.x());
        assertEquals(46, contain.y());
        assertEquals(100, contain.width());
        assertEquals(50, contain.height());

        TextureLayout.Placement original = TextureLayout.calculate("ORIGINAL", "TOP_LEFT", 16, 8,
                5, 7, 200, 100, 2, 3, 200);
        assertEquals(7, original.x()); assertEquals(10, original.y());
        assertEquals(32, original.width()); assertEquals(16, original.height());
    }

    @Test void tileModeIsMarkedAndScaleIsClamped() {
        TextureLayout.Placement tile = TextureLayout.calculate("TILE", "CENTER", 8, 8,
                0, 0, 100, 100, 0, 0, 1);
        assertTrue(tile.tiled());
        assertEquals(1, tile.width());
    }
}
