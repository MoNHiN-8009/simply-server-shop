package ru.xrshop.client;

/** Pure texture placement math, kept independent from Minecraft rendering for deterministic tests. */
final class TextureLayout {
    private TextureLayout() {}

    record Placement(int x, int y, int width, int height, boolean tiled) {}

    static Placement calculate(String mode, String anchor, int textureWidth, int textureHeight,
                               int targetX, int targetY, int targetWidth, int targetHeight,
                               int offsetX, int offsetY, int scalePercent) {
        int tw = Math.max(1, textureWidth), th = Math.max(1, textureHeight);
        int targetW = Math.max(1, targetWidth), targetH = Math.max(1, targetHeight);
        double scale = Math.max(10, Math.min(400, scalePercent)) / 100D;
        String normalizedMode = mode == null ? "STRETCH" : mode;
        int drawW;
        int drawH;
        boolean tiled = "TILE".equals(normalizedMode);
        switch (normalizedMode) {
            case "COVER" -> {
                double ratio = Math.max(targetW / (double) tw, targetH / (double) th) * scale;
                drawW = rounded(tw * ratio); drawH = rounded(th * ratio);
            }
            case "CONTAIN" -> {
                double ratio = Math.min(targetW / (double) tw, targetH / (double) th) * scale;
                drawW = rounded(tw * ratio); drawH = rounded(th * ratio);
            }
            case "ORIGINAL", "TILE" -> {
                drawW = rounded(tw * scale); drawH = rounded(th * scale);
            }
            default -> {
                drawW = rounded(targetW * scale); drawH = rounded(targetH * scale);
            }
        }
        int x = anchored(targetX, targetW, drawW, anchor, true) + offsetX;
        int y = anchored(targetY, targetH, drawH, anchor, false) + offsetY;
        return new Placement(x, y, drawW, drawH, tiled);
    }

    private static int rounded(double value) { return Math.max(1, (int) Math.round(value)); }

    private static int anchored(int start, int target, int drawn, String anchor, boolean horizontal) {
        String value = anchor == null ? "CENTER" : anchor;
        boolean leading = horizontal ? value.endsWith("_LEFT") || value.equals("LEFT")
                : value.startsWith("TOP");
        boolean trailing = horizontal ? value.endsWith("_RIGHT") || value.equals("RIGHT")
                : value.startsWith("BOTTOM");
        if (leading) return start;
        if (trailing) return start + target - drawn;
        return start + (target - drawn) / 2;
    }
}
