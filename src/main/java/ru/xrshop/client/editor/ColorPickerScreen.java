package ru.xrshop.client.editor;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.awt.Color;
import java.util.Locale;
import java.util.function.Consumer;

/** Cross-platform in-game ARGB picker with circle, HSV square and channel sliders. */
public final class ColorPickerScreen extends Screen {
    private enum Mode { CIRCLE, HSV_SQUARE, SLIDERS }

    private final Screen parent;
    private final Consumer<String> selected;
    private Mode mode = Mode.CIRCLE;
    private float hue;
    private float saturation;
    private float brightness;
    private int alpha;
    private int drag = -1;
    private EditBox hexBox;
    private boolean syncingHex;
    private int panelX;
    private int panelY;

    public ColorPickerScreen(Screen parent, String initial, Consumer<String> selected) {
        super(Component.literal("Выбор цвета"));
        this.parent = parent;
        this.selected = selected;
        setFromHex(initial);
    }

    @Override protected void init() {
        panelX = (width - Math.min(460, width - 20)) / 2;
        panelY = Math.max(8, (height - Math.min(350, height - 16)) / 2);
        int panelWidth = Math.min(460, width - 20);
        boolean compactHeader = panelWidth < 390;
        int modeWidth = Math.max(70, (panelWidth - 28) / 3);
        addRenderableWidget(Button.builder(Component.literal("Круг"), b -> { mode = Mode.CIRCLE; rebuild(); })
                .bounds(panelX + 8, panelY + 28, modeWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Квадрат HSV"), b -> { mode = Mode.HSV_SQUARE; rebuild(); })
                .bounds(panelX + 12 + modeWidth, panelY + 28, modeWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Ползунки"), b -> { mode = Mode.SLIDERS; rebuild(); })
                .bounds(panelX + 16 + modeWidth * 2, panelY + 28, modeWidth, 20).build());
        hexBox = new EditBox(font, panelX + 8, panelY + 55, compactHeader ? 64 : 112, 20, Component.literal("#AARRGGBB"));
        hexBox.setMaxLength(9); hexBox.setValue(hex());
        hexBox.setResponder(value -> { if (!syncingHex && validHex(value)) setFromHex(value); });
        addRenderableWidget(hexBox);
        int actionWidth = compactHeader ? 68 : 86;
        int cancelX = panelX + panelWidth - 8 - actionWidth;
        int applyX = cancelX - 4 - actionWidth;
        int removeX = applyX - 4 - actionWidth;
        addRenderableWidget(Button.builder(Component.literal("Убрать"), b -> {
            selected.accept(""); if (minecraft != null) minecraft.setScreen(parent);
        }).bounds(removeX, panelY + 55, actionWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Применить"), b -> {
            selected.accept(hex()); if (minecraft != null) minecraft.setScreen(parent);
        }).bounds(applyX, panelY + 55, actionWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Отмена"), b -> onClose())
                .bounds(cancelX, panelY + 55, actionWidth, 20).build());
    }

    private void rebuild() { clearWidgets(); init(); }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics); graphics.fill(0, 0, width, height, 0xE8101018);
        int panelWidth = Math.min(460, width - 20), panelHeight = Math.min(350, height - 16);
        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, 0xF0181820);
        graphics.drawString(font, title, panelX + 8, panelY + 10, 0xFFFFFFFF, false);
        int preview = argb();
        if (panelWidth >= 390) {
            graphics.fill(panelX + 126, panelY + 55, panelX + 166, panelY + 75, 0xFFFFFFFF);
            graphics.fill(panelX + 128, panelY + 57, panelX + 164, panelY + 73, preview);
        }
        if (mode == Mode.CIRCLE) renderCircle(graphics);
        else if (mode == Mode.HSV_SQUARE) renderSquare(graphics);
        else renderSliders(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void renderCircle(GuiGraphics graphics) {
        int panelHeight = Math.min(350, height - 16);
        int radius = Math.min(90, Math.max(34, (panelHeight - 110) / 2));
        int cx = panelX + Math.min(125, Math.max(radius + 12, (Math.min(460, width - 20) - 150) / 2));
        int cy = panelY + 88 + radius;
        for (int y = -radius; y <= radius; y += 2) for (int x = -radius; x <= radius; x += 2) {
            double distance = Math.sqrt(x * x + y * y);
            if (distance <= radius) {
                float h = (float) ((Math.atan2(y, x) / (Math.PI * 2) + 1.0) % 1.0);
                float s = (float) Math.min(1.0, distance / radius);
                graphics.fill(cx + x, cy + y, cx + x + 2, cy + y + 2, 0xFF000000 | (Color.HSBtoRGB(h, s, brightness) & 0xFFFFFF));
            }
        }
        int markerX = cx + Math.round((float) Math.cos(hue * Math.PI * 2) * saturation * radius);
        int markerY = cy + Math.round((float) Math.sin(hue * Math.PI * 2) * saturation * radius);
        marker(graphics, markerX, markerY);
        int barY = panelY + 92, barHeight = Math.min(170, panelHeight - 120);
        int valueX = panelX + Math.min(245, Math.min(460, width - 20) - 78);
        int alphaX = valueX + 42;
        drawValueBar(graphics, valueX, barY, 18, barHeight);
        drawAlphaBar(graphics, alphaX, barY, 18, barHeight);
        graphics.drawString(font, "V", valueX + 5, barY + barHeight + 4, 0xFFCCCCCC, false);
        graphics.drawString(font, "A", alphaX + 5, barY + barHeight + 4, 0xFFCCCCCC, false);
    }

    private void renderSquare(GuiGraphics graphics) {
        int panelHeight = Math.min(350, height - 16);
        int x0 = panelX + 24, y0 = panelY + 90;
        int size = Math.max(80, Math.min(180, Math.min(panelHeight - 112, Math.min(460, width - 20) - 120)));
        for (int y = 0; y < size; y += 2) for (int x = 0; x < size; x += 2) {
            float s = x / (float) (size - 1), v = 1F - y / (float) (size - 1);
            graphics.fill(x0 + x, y0 + y, x0 + x + 2, y0 + y + 2, 0xFF000000 | (Color.HSBtoRGB(hue, s, v) & 0xFFFFFF));
        }
        marker(graphics, x0 + Math.round(saturation * size), y0 + Math.round((1F - brightness) * size));
        drawHueBar(graphics, x0 + size + 20, y0, 22, size);
        drawAlphaBar(graphics, x0 + size + 64, y0, 22, size);
    }

    private void renderSliders(GuiGraphics graphics) {
        int rgb = Color.HSBtoRGB(hue, saturation, brightness);
        int[] channels = {alpha, (rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255};
        String[] names = {"A — прозрачность", "R — красный", "G — зелёный", "B — синий"};
        int panelHeight = Math.min(350, height - 16);
        int x = panelX + 24, width = Math.min(400, panelX + Math.min(460, this.width - 20) - x - 24);
        int step = Math.min(48, Math.max(27, (panelHeight - 112) / 4));
        for (int i = 0; i < 4; i++) {
            int y = panelY + 102 + i * step;
            graphics.drawString(font, names[i] + ": " + channels[i], x, y - 12, 0xFFFFFFFF, false);
            for (int px = 0; px < width; px += 2) {
                int value = Math.round(px * 255F / Math.max(1, width - 1));
                int color = switch (i) {
                    case 0 -> (value << 24) | (rgb & 0xFFFFFF);
                    case 1 -> 0xFF000000 | (value << 16) | (rgb & 0x00FFFF);
                    case 2 -> 0xFF000000 | (rgb & 0xFF00FF) | (value << 8);
                    default -> 0xFF000000 | (rgb & 0xFFFF00) | value;
                };
                graphics.fill(x + px, y, x + px + 2, y + 16, color);
            }
            int marker = x + Math.round(channels[i] / 255F * width);
            graphics.fill(marker - 1, y - 2, marker + 1, y + 18, 0xFFFFFFFF);
        }
    }

    private void drawHueBar(GuiGraphics graphics, int x, int y, int w, int h) {
        for (int py = 0; py < h; py += 2) graphics.fill(x, y + py, x + w, y + py + 2,
                0xFF000000 | (Color.HSBtoRGB(py / (float) h, 1F, 1F) & 0xFFFFFF));
        int marker = y + Math.round(hue * h); graphics.fill(x - 2, marker - 1, x + w + 2, marker + 1, 0xFFFFFFFF);
    }

    private void drawValueBar(GuiGraphics graphics, int x, int y, int w, int h) {
        for (int py = 0; py < h; py += 2) graphics.fill(x, y + py, x + w, y + py + 2,
                0xFF000000 | (Color.HSBtoRGB(hue, saturation, 1F - py / (float) h) & 0xFFFFFF));
        int marker = y + Math.round((1F - brightness) * h); graphics.fill(x - 2, marker - 1, x + w + 2, marker + 1, 0xFFFFFFFF);
    }

    private void drawAlphaBar(GuiGraphics graphics, int x, int y, int w, int h) {
        int rgb = Color.HSBtoRGB(hue, saturation, brightness) & 0xFFFFFF;
        for (int py = 0; py < h; py += 2) {
            int a = 255 - Math.round(py / (float) h * 255); graphics.fill(x, y + py, x + w, y + py + 2, (a << 24) | rgb);
        }
        int marker = y + Math.round((1F - alpha / 255F) * h); graphics.fill(x - 2, marker - 1, x + w + 2, marker + 1, 0xFFFFFFFF);
    }

    private static void marker(GuiGraphics graphics, int x, int y) {
        graphics.fill(x - 3, y - 3, x + 4, y - 2, 0xFFFFFFFF); graphics.fill(x - 3, y + 3, x + 4, y + 4, 0xFFFFFFFF);
        graphics.fill(x - 3, y - 2, x - 2, y + 3, 0xFFFFFFFF); graphics.fill(x + 3, y - 2, x + 4, y + 3, 0xFFFFFFFF);
    }

    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        if (button == 0) { drag = controlAt(mouseX, mouseY); updateDrag(mouseX, mouseY); return drag >= 0; }
        return false;
    }

    @Override public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        if (button == 0 && drag >= 0) { updateDrag(mouseX, mouseY); return true; }
        return super.mouseDragged(mouseX, mouseY, button, dx, dy);
    }

    @Override public boolean mouseReleased(double mouseX, double mouseY, int button) { drag = -1; return super.mouseReleased(mouseX, mouseY, button); }

    private int controlAt(double mx, double my) {
        if (mode == Mode.CIRCLE) {
            int panelHeight = Math.min(350, height - 16);
            int radius = Math.min(90, Math.max(34, (panelHeight - 110) / 2));
            int cx = panelX + Math.min(125, Math.max(radius + 12, (Math.min(460, width - 20) - 150) / 2));
            int cy = panelY + 88 + radius;
            if (Math.hypot(mx - cx, my - cy) <= radius) return 0;
            int barY = panelY + 92, barHeight = Math.min(170, panelHeight - 120);
            int valueX = panelX + Math.min(245, Math.min(460, width - 20) - 78);
            if (inside(mx, my, valueX, barY, 18, barHeight)) return 1;
            if (inside(mx, my, valueX + 42, barY, 18, barHeight)) return 2;
        } else if (mode == Mode.HSV_SQUARE) {
            int panelHeight = Math.min(350, height - 16);
            int x0 = panelX + 24, y0 = panelY + 90;
            int size = Math.max(80, Math.min(180, Math.min(panelHeight - 112, Math.min(460, width - 20) - 120)));
            if (inside(mx, my, x0, y0, size, size)) return 3;
            if (inside(mx, my, x0 + size + 20, y0, 22, size)) return 4;
            if (inside(mx, my, x0 + size + 64, y0, 22, size)) return 5;
        } else {
            int panelHeight = Math.min(350, height - 16);
            int x = panelX + 24, w = Math.min(400, panelX + Math.min(460, width - 20) - x - 24);
            int step = Math.min(48, Math.max(27, (panelHeight - 112) / 4));
            for (int i = 0; i < 4; i++) if (inside(mx, my, x, panelY + 102 + i * step, w, 16)) return 10 + i;
        }
        return -1;
    }

    private void updateDrag(double mx, double my) {
        if (drag < 0) return;
        if (drag == 0) {
            int panelHeight = Math.min(350, height - 16);
            int radius = Math.min(90, Math.max(34, (panelHeight - 110) / 2));
            int cx = panelX + Math.min(125, Math.max(radius + 12, (Math.min(460, width - 20) - 150) / 2));
            int cy = panelY + 88 + radius;
            hue = (float) ((Math.atan2(my - cy, mx - cx) / (Math.PI * 2) + 1.0) % 1.0);
            saturation = clamp((float) (Math.hypot(mx - cx, my - cy) / radius));
        } else if (drag == 1 || drag == 2) {
            int panelHeight = Math.min(350, height - 16), barHeight = Math.min(170, panelHeight - 120);
            float value = clamp((float) ((my - panelY - 92) / barHeight));
            if (drag == 1) brightness = 1F - value; else alpha = Math.round((1F - value) * 255);
        }
        else if (drag == 3) {
            int panelHeight = Math.min(350, height - 16);
            int size = Math.max(80, Math.min(180, Math.min(panelHeight - 112, Math.min(460, width - 20) - 120)));
            saturation = clamp((float) ((mx - panelX - 24) / size));
            brightness = 1F - clamp((float) ((my - panelY - 90) / size));
        } else if (drag == 4 || drag == 5) {
            int panelHeight = Math.min(350, height - 16);
            int size = Math.max(80, Math.min(180, Math.min(panelHeight - 112, Math.min(460, width - 20) - 120)));
            float value = clamp((float) ((my - panelY - 90) / size));
            if (drag == 4) hue = value; else alpha = Math.round((1F - value) * 255);
        } else if (drag >= 10) updateChannel(drag - 10, mx);
        syncHex();
    }

    private void updateChannel(int channel, double mouseX) {
        int x = panelX + 24, w = Math.min(400, panelX + Math.min(460, width - 20) - x - 24);
        int value = Math.round(clamp((float) ((mouseX - x) / w)) * 255);
        int rgb = Color.HSBtoRGB(hue, saturation, brightness);
        int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
        if (channel == 0) alpha = value;
        else {
            if (channel == 1) r = value; else if (channel == 2) g = value; else b = value;
            float[] hsb = Color.RGBtoHSB(r, g, b, null); hue = hsb[0]; saturation = hsb[1]; brightness = hsb[2];
        }
    }

    private void setFromHex(String text) {
        try {
            String raw = text == null ? "" : text.trim(); if (raw.startsWith("#")) raw = raw.substring(1);
            if (raw.length() == 6) raw = "FF" + raw;
            long value = Long.parseLong(raw, 16); alpha = (int) ((value >> 24) & 255);
            int r = (int) ((value >> 16) & 255), g = (int) ((value >> 8) & 255), b = (int) (value & 255);
            float[] hsb = Color.RGBtoHSB(r, g, b, null); hue = hsb[0]; saturation = hsb[1]; brightness = hsb[2];
        } catch (RuntimeException ex) { alpha = 255; hue = 0F; saturation = 0F; brightness = 1F; }
    }

    private void syncHex() { if (hexBox != null) { syncingHex = true; hexBox.setValue(hex()); syncingHex = false; } }
    private String hex() { return String.format(Locale.ROOT, "#%08X", argb()); }
    private int argb() { return (alpha << 24) | (Color.HSBtoRGB(hue, saturation, brightness) & 0xFFFFFF); }
    private static boolean validHex(String value) { return value != null && value.matches("#[0-9a-fA-F]{8}|#[0-9a-fA-F]{6}"); }
    private static float clamp(float value) { return Math.max(0F, Math.min(1F, value)); }
    private static boolean inside(double mx, double my, int x, int y, int w, int h) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    @Override public void onClose() { if (minecraft != null) minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
