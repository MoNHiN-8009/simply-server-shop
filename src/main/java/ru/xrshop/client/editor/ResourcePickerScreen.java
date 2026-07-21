package ru.xrshop.client.editor;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import ru.xrshop.client.TextureRenderer;
import ru.xrshop.common.config.ShopConfig;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** In-game resource browser with exact layer placement controls; never opens an OS file chooser. */
public final class ResourcePickerScreen extends Screen {
    private static final List<String> MODES = List.of("STRETCH", "COVER", "CONTAIN", "ORIGINAL", "TILE");
    private static final List<String> ANCHORS = List.of("TOP_LEFT", "TOP", "TOP_RIGHT", "LEFT", "CENTER", "RIGHT",
            "BOTTOM_LEFT", "BOTTOM", "BOTTOM_RIGHT");

    private final Screen parent;
    private final Consumer<String> selectedPathConsumer;
    private final Consumer<ShopConfig.TextureDefinition> selectedTextureConsumer;
    private final boolean placementEnabled;
    private final List<ResourceLocation> allTextures;
    private final List<Hit> hits = new ArrayList<>();
    private final TextureRenderer textureRenderer = new TextureRenderer();
    private final ShopConfig.TextureDefinition texture;
    private String selectedPath;
    private EditBox search;
    private EditBox offsetXBox;
    private EditBox offsetYBox;
    private EditBox scaleBox;
    private int scroll;
    private int panelX;
    private int panelWidth;
    private int listWidth;
    private int previewX;
    private int previewY;
    private int previewWidth;
    private int previewHeight;
    private int pathTextY;
    private int placementControlsX;
    private int placementThird;
    private int placementLabelsY;
    private int scrollbarX;
    private int scrollbarTop;
    private int scrollbarHeight;
    private int scrollbarThumbY;
    private int scrollbarThumbHeight;
    private int scrollbarMaximum;
    private boolean draggingScrollbar;
    private int scrollbarDragOffset;

    public ResourcePickerScreen(Screen parent, String initial, Consumer<String> selected) {
        this(parent, simple(initial), selected, null, false);
    }

    public ResourcePickerScreen(Screen parent, ShopConfig.TextureDefinition initial,
                                Consumer<ShopConfig.TextureDefinition> selected) {
        this(parent, copy(initial), null, selected, true);
    }

    private ResourcePickerScreen(Screen parent, ShopConfig.TextureDefinition initial, Consumer<String> pathConsumer,
                                 Consumer<ShopConfig.TextureDefinition> textureConsumer, boolean placementEnabled) {
        super(Component.literal(placementEnabled ? "Текстура и размещение" : "Выбор ресурса Minecraft"));
        this.parent = parent;
        this.texture = initial;
        this.selectedPath = initial.path == null ? "" : initial.path;
        this.selectedPathConsumer = pathConsumer;
        this.selectedTextureConsumer = textureConsumer;
        this.placementEnabled = placementEnabled;
        this.allTextures = Minecraft.getInstance().getResourceManager()
                .listResources("textures", id -> id.getPath().endsWith(".png")).keySet().stream()
                .sorted(Comparator.comparing(ResourceLocation::toString)).toList();
    }

    @Override protected void init() {
        panelWidth = Math.min(760, width - 20);
        panelX = (width - panelWidth) / 2;
        int rightWidth = Math.max(160, Math.min(260, panelWidth / 3));
        listWidth = panelWidth - rightWidth;
        search = new EditBox(font, panelX + 8, 34, listWidth - 16, 20, Component.literal("Поиск ресурса"));
        search.setHint(Component.literal("Поиск namespace:path…"));
        search.setMaxLength(256);
        search.setResponder(value -> scroll = 0);
        addRenderableWidget(search);

        int controlsX = panelX + listWidth + 8;
        int controlsWidth = Math.max(80, rightWidth - 16);
        addRenderableWidget(Button.builder(Component.literal("Пусто — без текстуры"), b -> selectedPath = "")
                .bounds(controlsX, 34, controlsWidth, 20).build());

        previewX = controlsX;
        previewY = 74;
        previewWidth = controlsWidth;
        previewHeight = placementEnabled ? Math.max(42, Math.min(110, height - 190)) : Math.max(70, Math.min(140, height - 130));
        int controlsY = previewY + previewHeight + 5;
        pathTextY = controlsY;
        if (placementEnabled) {
            int half = Math.max(55, (controlsWidth - 4) / 2);
            Button mode = Button.builder(Component.literal("Режим: " + modeLabel(texture.mode)), b -> {
                texture.mode = next(MODES, texture.mode); b.setMessage(Component.literal("Режим: " + modeLabel(texture.mode)));
            }).bounds(controlsX, controlsY, half, 20).build();
            addRenderableWidget(mode);
            Button anchor = Button.builder(Component.literal(anchorLabel(texture.anchor)), b -> {
                texture.anchor = next(ANCHORS, texture.anchor); b.setMessage(Component.literal(anchorLabel(texture.anchor)));
            }).bounds(controlsX + half + 4, controlsY, Math.max(55, controlsWidth - half - 4), 20).build();
            addRenderableWidget(anchor);

            placementControlsX = controlsX;
            placementLabelsY = controlsY + 24;
            int valuesY = controlsY + 34;
            int third = Math.max(38, (controlsWidth - 8) / 3);
            placementThird = third;
            offsetXBox = valueBox(controlsX, valuesY, third, "X", Integer.toString(texture.offset_x));
            offsetYBox = valueBox(controlsX + third + 4, valuesY, third, "Y", Integer.toString(texture.offset_y));
            scaleBox = valueBox(controlsX + (third + 4) * 2, valuesY,
                    Math.max(38, controlsWidth - (third + 4) * 2), "Масштаб %", Integer.toString(texture.scale));
            pathTextY = valuesY + 25;
        }

        addRenderableWidget(Button.builder(Component.literal("Применить"), b -> applySelection())
                .bounds(panelX + panelWidth - 188, height - 30, 86, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Отмена"), b -> onClose())
                .bounds(panelX + panelWidth - 98, height - 30, 90, 20).build());
    }

    private EditBox valueBox(int x, int y, int width, String hint, String value) {
        EditBox box = new EditBox(font, x, y, width, 20, Component.literal(hint));
        box.setHint(Component.literal(hint)); box.setMaxLength(8); box.setValue(value); addRenderableWidget(box); return box;
    }

    private void applySelection() {
        if (placementEnabled) {
            texture.path = selectedPath;
            texture.offset_x = parse(offsetXBox, texture.offset_x, -4096, 4096);
            texture.offset_y = parse(offsetYBox, texture.offset_y, -4096, 4096);
            texture.scale = parse(scaleBox, texture.scale, 10, 400);
            selectedTextureConsumer.accept(copy(texture));
        } else selectedPathConsumer.accept(selectedPath);
        if (minecraft != null) minecraft.setScreen(parent);
    }

    private List<ResourceLocation> filtered() {
        String query = search == null ? "" : search.getValue().trim().toLowerCase(Locale.ROOT);
        return query.isEmpty() ? allTextures : allTextures.stream().filter(id -> id.toString().contains(query)).toList();
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics); graphics.fill(0, 0, width, height, 0xE8101018);
        graphics.fill(panelX, 8, panelX + panelWidth, height - 8, 0xF0181820);
        graphics.drawString(font, title, panelX + 8, 16, 0xFFFFFFFF, false);
        renderList(graphics, mouseX, mouseY);
        graphics.drawString(font, "Предпросмотр", previewX, 62, 0xFF55AAFF, false);
        graphics.fill(previewX, previewY, previewX + previewWidth, previewY + previewHeight, 0xFF252D36);
        ShopConfig.TextureDefinition preview = currentTexture();
        textureRenderer.draw(graphics, preview, previewX, previewY, previewWidth, previewHeight);
        if (selectedPath.isBlank()) graphics.drawWordWrap(font,
                Component.literal("Текстура не выбрана. Этот слой останется пустым."), previewX + 6, previewY + 8,
                Math.max(60, previewWidth - 12), 0xFFAAAAAA);
        if (placementEnabled) {
            graphics.drawString(font, "X", placementControlsX + 2, placementLabelsY, 0xFFAAAAAA, false);
            graphics.drawString(font, "Y", placementControlsX + placementThird + 6, placementLabelsY, 0xFFAAAAAA, false);
            graphics.drawString(font, "Масштаб %", placementControlsX + (placementThird + 4) * 2 + 2,
                    placementLabelsY, 0xFFAAAAAA, false);
        }
        graphics.drawWordWrap(font, Component.literal(selectedPath.isBlank() ? "(пусто)" : selectedPath), previewX, pathTextY,
                Math.max(70, previewWidth), 0xFFFFFFFF);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private ShopConfig.TextureDefinition currentTexture() {
        ShopConfig.TextureDefinition value = copy(texture);
        value.path = selectedPath;
        if (placementEnabled) {
            value.offset_x = parse(offsetXBox, value.offset_x, -4096, 4096);
            value.offset_y = parse(offsetYBox, value.offset_y, -4096, 4096);
            value.scale = parse(scaleBox, value.scale, 10, 400);
        }
        return value;
    }

    private void renderList(GuiGraphics graphics, int mouseX, int mouseY) {
        hits.clear(); List<ResourceLocation> entries = filtered(); int top = 62, bottom = height - 38, row = 20;
        int visible = Math.max(1, (bottom - top) / row);
        scrollbarMaximum = Math.max(0, entries.size() - visible);
        scroll = Math.max(0, Math.min(scroll, scrollbarMaximum));
        scrollbarX = panelX + listWidth - 12;
        scrollbarTop = top;
        scrollbarHeight = Math.max(20, bottom - top);
        scrollbarThumbHeight = scrollbarMaximum == 0 ? scrollbarHeight
                : Math.max(20, Math.round(scrollbarHeight * (visible / (float) entries.size())));
        int thumbTravel = Math.max(0, scrollbarHeight - scrollbarThumbHeight);
        scrollbarThumbY = scrollbarTop + (scrollbarMaximum == 0 ? 0
                : Math.round(thumbTravel * (scroll / (float) scrollbarMaximum)));
        int listRight = scrollbarX - 4;
        int y = top;
        for (int i = scroll; i < entries.size() && i < scroll + visible; i++) {
            ResourceLocation id = entries.get(i); boolean selected = id.toString().equals(selectedPath);
            int rowWidth = Math.max(20, listRight - panelX - 8);
            boolean hover = inside(mouseX, mouseY, panelX + 8, y, rowWidth, 18);
            graphics.fill(panelX + 8, y, listRight, y + 18,
                    selected ? 0xFF315B7D : hover ? 0xFF30465C : 0xFF242C35);
            graphics.drawString(font, trim(id.toString(), rowWidth - 10), panelX + 13, y + 5, 0xFFFFFFFF, false);
            hits.add(new Hit(id, panelX + 8, y, rowWidth, 18)); y += row;
        }
        graphics.fill(scrollbarX, scrollbarTop, scrollbarX + 6, scrollbarTop + scrollbarHeight, 0xFF111820);
        graphics.fill(scrollbarX + 1, scrollbarThumbY, scrollbarX + 5, scrollbarThumbY + scrollbarThumbHeight,
                draggingScrollbar ? 0xFF77BBEE : 0xFF4B7598);
        if (entries.isEmpty()) graphics.drawCenteredString(font, "Текстуры не найдены",
                panelX + listWidth / 2, top + 12, 0xFFFFAA55);
    }

    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && scrollbarMaximum > 0 && inside(mouseX, mouseY, scrollbarX, scrollbarTop, 6, scrollbarHeight)) {
            draggingScrollbar = true;
            scrollbarDragOffset = inside(mouseX, mouseY, scrollbarX, scrollbarThumbY, 6, scrollbarThumbHeight)
                    ? (int) mouseY - scrollbarThumbY : scrollbarThumbHeight / 2;
            scrollFromThumb(mouseY); return true;
        }
        if (button == 0) for (Hit hit : hits) if (inside(mouseX, mouseY, hit.x, hit.y, hit.w, hit.h)) {
            selectedPath = hit.id.toString(); return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button == 0 && draggingScrollbar) { scrollFromThumb(mouseY); return true; }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && draggingScrollbar) { draggingScrollbar = false; return true; }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private void scrollFromThumb(double mouseY) {
        int travel = Math.max(1, scrollbarHeight - scrollbarThumbHeight);
        int position = Math.max(0, Math.min((int) mouseY - scrollbarDragOffset - scrollbarTop, travel));
        scroll = Math.round(scrollbarMaximum * (position / (float) travel));
    }

    @Override public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (inside(mouseX, mouseY, panelX + 8, scrollbarTop, listWidth - 14, scrollbarHeight)) {
            scroll = Math.max(0, Math.min(scrollbarMaximum, scroll - (int) Math.signum(delta) * 4)); return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    private static int parse(EditBox box, int fallback, int min, int max) {
        if (box == null) return fallback;
        try { return Math.max(min, Math.min(max, Integer.parseInt(box.getValue().trim()))); }
        catch (RuntimeException ex) { return fallback; }
    }

    private static String next(List<String> values, String current) {
        int index = values.indexOf(current); return values.get((Math.max(0, index) + 1) % values.size());
    }

    private static String modeLabel(String mode) {
        return switch (mode == null ? "STRETCH" : mode) {
            case "COVER" -> "Обрезать";
            case "CONTAIN" -> "Вписать";
            case "ORIGINAL" -> "Исходный";
            case "TILE" -> "Плитка";
            default -> "Растянуть";
        };
    }

    private static String anchorLabel(String anchor) {
        return switch (anchor == null ? "CENTER" : anchor) {
            case "TOP_LEFT" -> "↖ Сверху слева"; case "TOP" -> "↑ Сверху"; case "TOP_RIGHT" -> "↗ Сверху справа";
            case "LEFT" -> "← Слева"; case "RIGHT" -> "→ Справа";
            case "BOTTOM_LEFT" -> "↙ Снизу слева"; case "BOTTOM" -> "↓ Снизу"; case "BOTTOM_RIGHT" -> "↘ Снизу справа";
            default -> "● По центру";
        };
    }

    private static ShopConfig.TextureDefinition simple(String path) {
        ShopConfig.TextureDefinition value = new ShopConfig.TextureDefinition(); value.path = path == null ? "" : path; return value;
    }

    private static ShopConfig.TextureDefinition copy(ShopConfig.TextureDefinition source) {
        ShopConfig.TextureDefinition value = new ShopConfig.TextureDefinition();
        if (source == null) return value;
        value.path = source.path == null ? "" : source.path;
        value.mode = source.mode == null ? "STRETCH" : source.mode;
        value.anchor = source.anchor == null ? "CENTER" : source.anchor;
        value.offset_x = source.offset_x; value.offset_y = source.offset_y; value.scale = source.scale;
        return value;
    }

    private String trim(String text, int pixels) {
        return font.width(text) <= pixels ? text : font.plainSubstrByWidth(text,
                Math.max(1, pixels - font.width("…"))) + "…";
    }
    private static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }
    @Override public void onClose() { if (minecraft != null) minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
    private record Hit(ResourceLocation id, int x, int y, int w, int h) {}
}
