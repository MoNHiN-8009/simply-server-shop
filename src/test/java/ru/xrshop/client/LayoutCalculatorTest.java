package ru.xrshop.client;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class LayoutCalculatorTest {
    @ParameterizedTest
    @CsvSource({
            "1280,720,1", "640,360,2", "426,240,3",
            "1920,1080,1", "960,540,2", "640,360,3",
            "2560,1440,1", "1280,720,2", "853,480,3"
    })
    void layoutFitsPhysicalResolutionsAndGuiScales(int width, int height, int guiScale) {
        var layout = LayoutCalculator.calculate(width, height, 12, 120, 90, 105, 8, 10);
        assertTrue(layout.columns() >= 1); assertTrue(layout.contentX() >= 0);
        assertTrue(layout.contentX() + layout.contentWidth() <= width);
        assertEquals(layout.contentX() + 10, layout.gridX());
        assertTrue(layout.cardWidth() <= layout.gridWidth()); assertTrue(layout.cardHeight() <= Math.max(105, height));
    }

    @ParameterizedTest
    @CsvSource({"72,64,15", "105,24,24", "105,64,48", "140,64,64"})
    void cardContentKeepsIconTextPriceAndButtonSeparated(int cardHeight, int requestedSize, int expectedSize) {
        var content = LayoutCalculator.cardContent(cardHeight, requestedSize);
        assertEquals(expectedSize, content.iconSize());
        assertTrue(content.iconY() + content.iconSize() < content.titleY());
        assertTrue(content.titleY() < content.priceY());
        assertTrue(content.priceY() + 9 + 3 <= content.buttonY());
    }
}
