package ru.xrshop.client;

public final class LayoutCalculator {
    private static final int ICON_TOP = 7;
    private static final int ICON_TITLE_GAP = 4;
    private static final int TEXT_LINE_STEP = 13;
    private static final int FONT_HEIGHT = 9;
    private static final int PRICE_BUTTON_GAP = 3;
    private static final int BUTTON_BOTTOM_SPACE = 21;

    private LayoutCalculator() {}
    public static Layout calculate(int screenWidth, int screenHeight, int margin, int requestedTabWidth,
                                   int requestedCardWidth, int requestedCardHeight, int horizontalGap,
                                   int requestedGridLeftPadding) {
        int safeMargin = Math.max(4, Math.min(margin, Math.max(4, Math.min(screenWidth, screenHeight) / 8)));
        int tab = Math.max(72, Math.min(requestedTabWidth, Math.max(72, screenWidth / 3)));
        int left = safeMargin + tab + safeMargin;
        int available = Math.max(64, screenWidth - left - safeMargin);
        int gridLeftPadding = Math.max(0, Math.min(requestedGridLeftPadding, Math.max(0, available - 64)));
        int gridWidth = Math.max(64, available - gridLeftPadding);
        int cardWidth = Math.max(64, Math.min(requestedCardWidth, gridWidth));
        int gap = Math.max(0, horizontalGap);
        int columns = Math.max(1, (gridWidth + gap) / Math.max(1, cardWidth + gap));
        int cardHeight = Math.max(72, Math.min(requestedCardHeight, Math.max(72, screenHeight - 2 * safeMargin - 34)));
        return new Layout(safeMargin, tab, left, available, left + gridLeftPadding, gridWidth,
                cardWidth, cardHeight, gap, columns);
    }
    public record Layout(int margin, int tabWidth, int contentX, int contentWidth, int gridX, int gridWidth,
                         int cardWidth, int cardHeight, int horizontalGap, int columns) {}

    public static CardContent cardContent(int cardHeight, int requestedIconSize) {
        int safeHeight = Math.max(72, cardHeight);
        int buttonY = safeHeight - BUTTON_BOTTOM_SPACE;
        int maximumIcon = buttonY - ICON_TOP - ICON_TITLE_GAP - TEXT_LINE_STEP - FONT_HEIGHT - PRICE_BUTTON_GAP;
        int iconSize = Math.min(Math.max(8, Math.min(64, requestedIconSize)), Math.max(8, maximumIcon));
        int titleY = ICON_TOP + iconSize + ICON_TITLE_GAP;
        return new CardContent(ICON_TOP, iconSize, titleY, titleY + TEXT_LINE_STEP, buttonY);
    }

    public record CardContent(int iconY, int iconSize, int titleY, int priceY, int buttonY) {}
}
