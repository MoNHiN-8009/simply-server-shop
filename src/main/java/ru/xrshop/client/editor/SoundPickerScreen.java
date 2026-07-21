package ru.xrshop.client.editor;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** Searchable sound registry picker with local preview and volume/pitch controls. */
public final class SoundPickerScreen extends Screen {
    public record Selection(String sound, float volume, float pitch) {}

    private final Screen parent;
    private final Consumer<Selection> selected;
    private final List<Entry> allSounds;
    private final List<Hit> hits = new ArrayList<>();
    private String soundId;
    private float volume;
    private float pitch;
    private EditBox search;
    private int scroll;
    private int panelX;
    private int panelWidth;
    private int listTop;
    private int listBottom;
    private int scrollbarX;
    private int scrollbarThumbY;
    private int scrollbarThumbHeight;
    private int scrollbarMaximum;
    private boolean draggingScrollbar;
    private int scrollbarDragOffset;

    public SoundPickerScreen(Screen parent, String initialSound, float initialVolume, float initialPitch,
                             Consumer<Selection> selected) {
        super(Component.literal("Выбор звука"));
        this.parent = parent; this.selected = selected;
        this.soundId = initialSound == null ? "" : initialSound;
        this.volume = clamp(initialVolume, 0F, 4F); this.pitch = clamp(initialPitch, 0.1F, 4F);
        this.allSounds = ForgeRegistries.SOUND_EVENTS.getEntries().stream()
                .map(entry -> new Entry(entry.getKey().location(), entry.getValue()))
                .sorted(Comparator.comparing(entry -> entry.id.toString())).toList();
    }

    @Override protected void init() {
        panelWidth = Math.min(600, width - 20); panelX = (width - panelWidth) / 2;
        listTop = 108; listBottom = height - 42;
        search = new EditBox(font, panelX + 8, 34, panelWidth - 194, 20, Component.literal("Поиск"));
        search.setHint(Component.literal("Поиск звука…")); search.setMaxLength(128); search.setResponder(value -> scroll = 0);
        addRenderableWidget(search);
        addRenderableWidget(Button.builder(Component.literal("▶ Прослушать"), b -> preview())
                .bounds(panelX + panelWidth - 182, 34, 108, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Без звука"), b -> { soundId = ""; })
                .bounds(panelX + panelWidth - 70, 34, 62, 20).build());
        addRenderableWidget(new ValueSlider(panelX + 8, 60, (panelWidth - 20) / 2, 20, "Громкость", volume / 4D, 0F, 4F,
                value -> volume = value));
        addRenderableWidget(new ValueSlider(panelX + 12 + (panelWidth - 20) / 2, 60, (panelWidth - 20) / 2, 20, "Высота", (pitch - 0.1D) / 3.9D, 0.1F, 4F,
                value -> pitch = value));
        addRenderableWidget(Button.builder(Component.literal("Применить"), b -> {
            selected.accept(new Selection(soundId, volume, pitch)); if (minecraft != null) minecraft.setScreen(parent);
        }).bounds(panelX + panelWidth - 188, height - 30, 86, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Отмена"), b -> onClose()).bounds(panelX + panelWidth - 98, height - 30, 90, 20).build());
    }

    private List<Entry> filtered() {
        String query = search == null ? "" : search.getValue().trim().toLowerCase(Locale.ROOT);
        return query.isEmpty() ? allSounds : allSounds.stream().filter(entry -> entry.id.toString().contains(query)).toList();
    }

    private void preview() {
        ResourceLocation id = ResourceLocation.tryParse(soundId);
        if (id == null || minecraft == null) return;
        SoundEvent event = ForgeRegistries.SOUND_EVENTS.getValue(id);
        if (event != null) minecraft.getSoundManager().play(SimpleSoundInstance.forUI(event, pitch, volume));
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics); graphics.fill(0, 0, width, height, 0xE8101018);
        graphics.fill(panelX, 8, panelX + panelWidth, height - 8, 0xF0181820);
        graphics.drawString(font, title, panelX + 8, 16, 0xFFFFFFFF, false);
        graphics.drawString(font, "Выбрано: " + (soundId.isBlank() ? "без звука" : soundId), panelX + 8, 88, 0xFF55AAFF, false);
        renderList(graphics, mouseX, mouseY);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void renderList(GuiGraphics graphics, int mouseX, int mouseY) {
        hits.clear(); List<Entry> sounds = filtered(); int rowHeight = 20;
        int visible = Math.max(1, (listBottom - listTop) / rowHeight);
        scrollbarMaximum = Math.max(0, sounds.size() - visible);
        scroll = Math.max(0, Math.min(scroll, scrollbarMaximum));
        scrollbarX = panelX + panelWidth - 12;
        int trackHeight = Math.max(20, listBottom - listTop);
        scrollbarThumbHeight = scrollbarMaximum == 0 ? trackHeight
                : Math.max(20, Math.round(trackHeight * (visible / (float) sounds.size())));
        int thumbTravel = Math.max(0, trackHeight - scrollbarThumbHeight);
        scrollbarThumbY = listTop + (scrollbarMaximum == 0 ? 0
                : Math.round(thumbTravel * (scroll / (float) scrollbarMaximum)));
        int listRight = scrollbarX - 4;
        int y = listTop;
        for (int i = scroll; i < sounds.size() && i < scroll + visible; i++) {
            Entry entry = sounds.get(i); boolean selected = entry.id.toString().equals(soundId);
            int rowWidth = Math.max(20, listRight - panelX - 8);
            boolean hover = inside(mouseX, mouseY, panelX + 8, y, rowWidth, 18);
            graphics.fill(panelX + 8, y, listRight, y + 18, selected ? 0xFF315B7D : hover ? 0xFF30465C : 0xFF242C35);
            graphics.drawString(font, trim(entry.id.toString(), rowWidth - 10), panelX + 13, y + 5, 0xFFFFFFFF, false);
            hits.add(new Hit(entry, panelX + 8, y, rowWidth, 18)); y += rowHeight;
        }
        graphics.fill(scrollbarX, listTop, scrollbarX + 6, listBottom, 0xFF111820);
        graphics.fill(scrollbarX + 1, scrollbarThumbY, scrollbarX + 5, scrollbarThumbY + scrollbarThumbHeight,
                draggingScrollbar ? 0xFF77BBEE : 0xFF4B7598);
        if (sounds.isEmpty()) graphics.drawCenteredString(font, "Звуки не найдены", width / 2, listTop + 12, 0xFFFFAA55);
    }

    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && scrollbarMaximum > 0 && inside(mouseX, mouseY, scrollbarX, listTop, 6, listBottom - listTop)) {
            draggingScrollbar = true;
            scrollbarDragOffset = inside(mouseX, mouseY, scrollbarX, scrollbarThumbY, 6, scrollbarThumbHeight)
                    ? (int) mouseY - scrollbarThumbY : scrollbarThumbHeight / 2;
            scrollFromThumb(mouseY); return true;
        }
        if (button == 0) for (Hit hit : hits) if (inside(mouseX, mouseY, hit.x, hit.y, hit.w, hit.h)) {
            soundId = hit.entry.id.toString(); preview(); return true;
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
        int travel = Math.max(1, listBottom - listTop - scrollbarThumbHeight);
        int position = Math.max(0, Math.min((int) mouseY - scrollbarDragOffset - listTop, travel));
        scroll = Math.round(scrollbarMaximum * (position / (float) travel));
    }

    @Override public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (mouseY >= listTop && mouseY <= listBottom) {
            scroll = Math.max(0, Math.min(scrollbarMaximum, scroll - (int) Math.signum(delta) * 4)); return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override public void onClose() { if (minecraft != null) minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
    private static float clamp(float value, float min, float max) { return Math.max(min, Math.min(max, value)); }
    private String trim(String text, int pixels) { return font.width(text) <= pixels ? text
            : font.plainSubstrByWidth(text, Math.max(1, pixels - font.width("…"))) + "…"; }
    private static boolean inside(double mx, double my, int x, int y, int w, int h) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    private record Entry(ResourceLocation id, SoundEvent event) {}
    private record Hit(Entry entry, int x, int y, int w, int h) {}

    private static final class ValueSlider extends AbstractSliderButton {
        private final String label; private final float min; private final float max; private final Consumer<Float> changed;
        private ValueSlider(int x, int y, int width, int height, String label, double value, float min, float max, Consumer<Float> changed) {
            super(x, y, width, height, Component.empty(), Math.max(0D, Math.min(1D, value)));
            this.label = label; this.min = min; this.max = max; this.changed = changed; updateMessage();
        }
        private float actual() { return min + (float) value * (max - min); }
        @Override protected void updateMessage() { setMessage(Component.literal(label + ": " + String.format(Locale.ROOT, "%.2f", actual()))); }
        @Override protected void applyValue() { changed.accept(actual()); }
    }
}
