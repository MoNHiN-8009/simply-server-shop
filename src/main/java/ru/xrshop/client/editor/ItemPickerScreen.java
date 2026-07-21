package ru.xrshop.client.editor;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** Searchable creative-like item grid used by icon and give-command editors. */
public final class ItemPickerScreen extends Screen {
    private final Screen parent;
    private final Consumer<String> selected;
    private final List<Entry> allItems;
    private final List<Hit> hits = new ArrayList<>();
    private EditBox search;
    private int scrollRows;
    private ItemStack hovered = ItemStack.EMPTY;

    public ItemPickerScreen(Screen parent, Consumer<String> selected) {
        super(Component.literal("Выбор предмета"));
        this.parent = parent;
        this.selected = selected;
        this.allItems = ForgeRegistries.ITEMS.getEntries().stream()
                .map(entry -> new Entry(entry.getKey().location(), entry.getValue(), new ItemStack(entry.getValue()).getHoverName().getString()))
                .filter(entry -> entry.item != Items.AIR)
                .sorted(Comparator.comparing(entry -> entry.id.toString()))
                .toList();
    }

    @Override protected void init() {
        int panelWidth = Math.min(560, width - 24);
        int left = (width - panelWidth) / 2;
        search = new EditBox(font, left + 10, 38, panelWidth - 90, 20, Component.literal("Поиск"));
        search.setHint(Component.literal("Поиск по названию или ID…"));
        search.setMaxLength(128);
        search.setResponder(value -> scrollRows = 0);
        addRenderableWidget(search);
        addRenderableWidget(Button.builder(Component.literal("Назад"), button -> onClose())
                .bounds(left + panelWidth - 70, 38, 60, 20).build());
        setInitialFocus(search);
    }

    private List<Entry> filtered() {
        String query = search == null ? "" : search.getValue().trim().toLowerCase(Locale.ROOT);
        if (query.isEmpty()) return allItems;
        return allItems.stream().filter(entry -> entry.id.toString().contains(query)
                || entry.name.toLowerCase(Locale.ROOT).contains(query)).toList();
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.fill(0, 0, width, height, 0xE8101018);
        int panelWidth = Math.min(560, width - 24);
        int left = (width - panelWidth) / 2;
        graphics.fill(left, 12, left + panelWidth, height - 12, 0xF0181820);
        graphics.drawString(font, title, left + 10, 20, 0xFFFFFFFF, false);
        graphics.drawString(font, "Найдите предмет и нажмите на его иконку", left + 10, 64, 0xFFAAAAAA, false);
        renderGrid(graphics, left + 10, 80, panelWidth - 20, height - 104, mouseX, mouseY);
        super.render(graphics, mouseX, mouseY, partialTick);
        if (!hovered.isEmpty()) graphics.renderTooltip(font, hovered, mouseX, mouseY);
    }

    private void renderGrid(GuiGraphics graphics, int x, int y, int areaWidth, int areaHeight, int mouseX, int mouseY) {
        hits.clear(); hovered = ItemStack.EMPTY;
        List<Entry> entries = filtered();
        int slot = 22;
        int columns = Math.max(1, areaWidth / slot);
        int visibleRows = Math.max(1, areaHeight / slot);
        int maxRows = Math.max(0, (entries.size() + columns - 1) / columns - visibleRows);
        scrollRows = Math.max(0, Math.min(scrollRows, maxRows));
        int first = scrollRows * columns;
        int last = Math.min(entries.size(), first + visibleRows * columns);
        for (int index = first; index < last; index++) {
            int local = index - first;
            int sx = x + (local % columns) * slot;
            int sy = y + (local / columns) * slot;
            boolean hover = mouseX >= sx && mouseX < sx + 20 && mouseY >= sy && mouseY < sy + 20;
            graphics.fill(sx, sy, sx + 20, sy + 20, hover ? 0xFF557799 : 0xFF2A3440);
            ItemStack stack = new ItemStack(entries.get(index).item);
            graphics.renderItem(stack, sx + 2, sy + 2);
            hits.add(new Hit(entries.get(index), sx, sy));
            if (hover) hovered = stack;
        }
        if (entries.isEmpty()) graphics.drawCenteredString(font, "Ничего не найдено", x + areaWidth / 2, y + 20, 0xFFFFAA55);
        else graphics.drawString(font, (first + 1) + "–" + last + " / " + entries.size(), x, y + areaHeight + 3, 0xFFAAAAAA, false);
    }

    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) for (Hit hit : hits) {
            if (mouseX >= hit.x && mouseX < hit.x + 20 && mouseY >= hit.y && mouseY < hit.y + 20) {
                selected.accept(hit.entry.id.toString());
                if (minecraft != null) minecraft.setScreen(parent);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        scrollRows = Math.max(0, scrollRows - (int) Math.signum(delta) * 3);
        return true;
    }

    @Override public void onClose() { if (minecraft != null) minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }

    private record Entry(ResourceLocation id, Item item, String name) {}
    private record Hit(Entry entry, int x, int y) {}
}
