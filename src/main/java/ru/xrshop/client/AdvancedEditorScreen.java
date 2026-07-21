package ru.xrshop.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import ru.xrshop.common.config.ShopConfig;
import ru.xrshop.common.dto.EditorDto;
import ru.xrshop.common.dto.PurchaseResultCode;
import ru.xrshop.common.network.EditorActionC2S;
import ru.xrshop.common.network.NetworkHandler;
import ru.xrshop.common.network.ServerEventS2C;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public final class AdvancedEditorScreen extends Screen implements EditorSessionScreen {
    private EditorDto dto;
    private EditBox categoryBox;
    private EditBox slotBox;
    private EditBox secondaryBox;
    private EditBox fieldBox;
    private EditBox valueBox;
    private String status = "";
    private int statusColor = 0xFFFFFFFF;
    private boolean dirty;
    private boolean closed;
    private boolean saveInFlight;
    private long confirmCloseUntil;
    private String pendingDelete = "";
    private long pendingDeleteUntil;
    private EditorActionC2S.Action lastAction;
    private final List<ListHit> listHits = new ArrayList<>();
    private int listScroll;

    public AdvancedEditorScreen(EditorDto dto) { super(Component.literal("Расширенный редактор XR Shop")); this.dto = dto; }
    public UUID sessionId() { return dto.editorSessionId(); }

    @Override protected void init() {
        int x = 10, boxWidth = Math.min(250, Math.max(150, width / 3 - 18));
        categoryBox = box(x, 28, boxWidth, "category_id");
        slotBox = box(x, 52, boxWidth, "slot_id");
        secondaryBox = box(x, 76, boxWidth, "новый/целевой ID");
        fieldBox = box(x, 100, boxWidth, "поле, например style.card_color");
        valueBox = box(x, 124, boxWidth, "значение JSON или команда"); valueBox.setMaxLength(4096);
        int y = 150;
        addRow(y, new ActionButton("+ кат.", EditorActionC2S.Action.CREATE_CATEGORY, false),
                new ActionButton("− кат.", EditorActionC2S.Action.DELETE_CATEGORY, true),
                new ActionButton("Коп. кат.", EditorActionC2S.Action.COPY_CATEGORY, false));
        addRow(y + 22, new ActionButton("+ товар", EditorActionC2S.Action.CREATE_PRODUCT, false),
                new ActionButton("− товар", EditorActionC2S.Action.DELETE_PRODUCT, true),
                new ActionButton("Коп. тов.", EditorActionC2S.Action.COPY_PRODUCT, false),
                new ActionButton("Перенести", EditorActionC2S.Action.MOVE_PRODUCT, false));
        addRow(y + 44, new ActionButton("Поле кат.", EditorActionC2S.Action.SET_CATEGORY_FIELD, false),
                new ActionButton("Поле тов.", EditorActionC2S.Action.SET_PRODUCT_FIELD, false),
                new ActionButton("Поле UI", EditorActionC2S.Action.SET_UI_FIELD, false),
                new ActionButton("Глоб. стиль", EditorActionC2S.Action.SET_GLOBAL_STYLE_FIELD, false));
        addRow(y + 66, new ActionButton("+ команда", EditorActionC2S.Action.ADD_COMMAND, false),
                new ActionButton("Команда", EditorActionC2S.Action.SET_COMMAND, false),
                new ActionButton("− команда", EditorActionC2S.Action.REMOVE_COMMAND, true));
        int bottom = Math.max(y + 92, height - 26);
        addRenderableWidget(Button.builder(Component.translatable("xrshop.screen.editor.validate"), b -> send(EditorActionC2S.Action.VALIDATE))
                .bounds(x, bottom, 78, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("xrshop.screen.editor.save"), b -> send(EditorActionC2S.Action.SAVE))
                .bounds(x + 82, bottom, 78, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("xrshop.screen.editor.cancel"), b -> cancelAndClose())
                .bounds(x + 164, bottom, 86, 20).build());
    }

    private EditBox box(int x, int y, int width, String hint) {
        EditBox box = new EditBox(font, x, y, width, 20, Component.literal(hint)); box.setHint(Component.literal(hint));
        addRenderableWidget(box); return box;
    }

    private void addRow(int y, ActionButton... definitions) {
        int x = 10, total = Math.min(250, Math.max(150, width / 3 - 18)), gap = 3;
        int w = Math.max(42, (total - gap * (definitions.length - 1)) / definitions.length);
        for (ActionButton definition : definitions) {
            addRenderableWidget(Button.builder(Component.literal(definition.label), b -> {
                if (!definition.destructive || confirmDelete(definition.action.name())) send(definition.action);
            }).bounds(x, y, w, 20).build()); x += w + gap;
        }
    }

    public void update(EditorDto updated) { this.dto = updated; }

    public void serverEvent(ServerEventS2C event) {
        if (event.type() != ServerEventS2C.Type.EDITOR_RESULT) return;
        if (event.sessionId() != null && !event.sessionId().equals(dto.editorSessionId())) return;
        status = event.message(); statusColor = event.code() == PurchaseResultCode.SUCCESS ? 0xFF55FF55 : 0xFFFF5555;
        if (lastAction == EditorActionC2S.Action.SAVE) {
            saveInFlight = false;
            if (event.code() == PurchaseResultCode.SUCCESS) {
                dirty = false;
            }
        }
    }

    private void send(EditorActionC2S.Action action) {
        if (minecraft == null || minecraft.getConnection() == null) return;
        if (saveInFlight) {
            status = "Дождитесь завершения сохранения."; statusColor = 0xFFFFFF55; return;
        }
        lastAction = action;
        if (action == EditorActionC2S.Action.SAVE) saveInFlight = true;
        NetworkHandler.sendToServer(new EditorActionC2S(dto.editorSessionId(), action,
                categoryBox.getValue().trim(), slotBox.getValue().trim(), fieldBox.getValue().trim(),
                valueBox.getValue(), secondaryBox.getValue().trim()));
        if (action != EditorActionC2S.Action.VALIDATE && action != EditorActionC2S.Action.SAVE
                && action != EditorActionC2S.Action.CANCEL) dirty = true;
        status = "Операция отправлена серверу…"; statusColor = 0xFFFFFF55;
    }

    private boolean confirmDelete(String key) {
        long now = System.currentTimeMillis();
        if (key.equals(pendingDelete) && now <= pendingDeleteUntil) { pendingDelete = ""; return true; }
        pendingDelete = key; pendingDeleteUntil = now + 3_000; status = "Нажмите кнопку удаления ещё раз для подтверждения."; statusColor = 0xFFFFAA55; return false;
    }

    private void cancelAndClose() {
        if (saveInFlight) { status = "Дождитесь завершения сохранения."; statusColor = 0xFFFFFF55; return; }
        send(EditorActionC2S.Action.CANCEL); dirty = false; closed = true;
        if (minecraft != null) minecraft.setScreen(null);
    }

    @Override public void onClose() {
        if (saveInFlight) { status = "Дождитесь завершения сохранения."; statusColor = 0xFFFFFF55; return; }
        if (dirty && System.currentTimeMillis() > confirmCloseUntil) {
            confirmCloseUntil = System.currentTimeMillis() + 4_000;
            status = "Несохранённые изменения будут отменены. Нажмите Esc ещё раз."; statusColor = 0xFFFFAA55; return;
        }
        if (dirty) send(EditorActionC2S.Action.CANCEL);
        dirty = false; super.onClose();
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics); graphics.fill(0, 0, width, height, 0xE5101018);
        graphics.drawString(font, title, 10, 10, 0xFFFFFFFF, false);
        int listX = Math.min(width - 120, Math.max(275, width / 3 + 5));
        int listWidth = Math.max(110, Math.min(260, width / 3));
        graphics.fill(listX - 4, 24, listX + listWidth, height - 8, 0xEE181820);
        graphics.drawString(font, "Категории и товары", listX, 30, 0xFF55AAFF, false);
        renderList(graphics, listX, 46 - listScroll, listWidth, mouseX, mouseY);
        int previewX = listX + listWidth + 8;
        if (width - previewX > 110) renderPreview(graphics, previewX, 24, width - previewX - 8, height - 32);
        if (!status.isBlank()) graphics.drawString(font, trim(status, Math.max(120, width - 280)), 10, Math.max(238, height - 38), statusColor, false);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void renderList(GuiGraphics graphics, int x, int y, int w, int mouseX, int mouseY) {
        listHits.clear();
        List<ShopConfig.CategoryDefinition> categories = dto.draft().categories.stream()
                .sorted(Comparator.comparingInt(c -> c.order)).toList();
        for (ShopConfig.CategoryDefinition category : categories) {
            if (y > 38 && y < height - 20) {
                boolean hover = inside(mouseX, mouseY, x, y, w - 4, 18);
                graphics.fill(x, y, x + w - 4, y + 18, hover ? 0xFF30465C : 0xFF24303C);
                graphics.drawString(font, trim(category.title + " [" + category.category_id + "]", w - 10), x + 4, y + 5, 0xFFFFFFFF, false);
                listHits.add(new ListHit(category.category_id, "", x, y, w - 4, 18));
            }
            y += 20;
            for (ShopConfig.ProductDefinition product : category.products.stream().sorted(Comparator.comparingInt(p -> p.order)).toList()) {
                if (y > 38 && y < height - 20) {
                    boolean hover = inside(mouseX, mouseY, x + 10, y, w - 14, 17);
                    graphics.fill(x + 10, y, x + w - 4, y + 17, hover ? 0xFF405060 : 0xFF29333D);
                    graphics.drawString(font, trim(product.title + " [" + product.slot_id + "]", w - 26), x + 14, y + 4, product.enabled ? 0xFFFFFFFF : 0xFFAAAAAA, false);
                    listHits.add(new ListHit(category.category_id, product.slot_id, x + 10, y, w - 14, 17));
                }
                y += 19;
            }
        }
    }

    private void renderPreview(GuiGraphics graphics, int x, int y, int w, int h) {
        graphics.fill(x, y, x + w, y + h, StoreScreen.color(dto.draft().ui.panel_color, 0xEE181820));
        graphics.drawString(font, "Предварительный просмотр", x + 5, y + 6, 0xFF55AAFF, false);
        ShopConfig.CategoryDefinition selected = dto.draft().categories.stream().filter(c -> c.category_id.equals(categoryBox.getValue())).findFirst().orElse(null);
        if (selected == null) { graphics.drawWordWrap(font, Component.literal(dto.draft().ui.empty_message), x + 5, y + 24, w - 10, 0xFFFFFFFF); return; }
        graphics.drawString(font, trim(selected.title, w - 10), x + 5, y + 24, 0xFFFFFFFF, false);
        int cardW = Math.max(64, Math.min(dto.draft().ui.card_width, Math.max(64, w - 10))), cardH = Math.max(60, Math.min(dto.draft().ui.card_height, 100));
        int px = x + 5, py = y + 40;
        for (ShopConfig.ProductDefinition product : selected.products) {
            if (py + cardH > y + h) break;
            graphics.fill(px, py, Math.min(x + w - 5, px + cardW), py + cardH, StoreScreen.color(product.style.card_color, 0xFF252C35));
            graphics.drawString(font, trim(product.title, cardW - 8), px + 4, py + 7, 0xFFFFFFFF, false);
            graphics.drawString(font, product.price_xr + " XR", px + 4, py + 20, 0xFF55AAFF, false);
            py += cardH + 5;
        }
    }

    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) for (ListHit hit : listHits) if (inside(mouseX, mouseY, hit.x, hit.y, hit.w, hit.h)) {
            categoryBox.setValue(hit.category); slotBox.setValue(hit.slot); return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
    @Override public boolean mouseScrolled(double mouseX, double mouseY, double delta) { listScroll = Math.max(0, listScroll - (int) (delta * 24)); return true; }
    private String trim(String text, int pixels) { return font.width(text) <= pixels ? text : font.plainSubstrByWidth(text, Math.max(1, pixels - font.width("…"))) + "…"; }
    private static boolean inside(double mx, double my, int x, int y, int w, int h) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    @Override public void removed() { if (!closed) { closed = true; ClientState.closeEditor(dto.editorSessionId()); } super.removed(); }
    @Override public boolean isPauseScreen() { return false; }
    private record ActionButton(String label, EditorActionC2S.Action action, boolean destructive) {}
    private record ListHit(String category, String slot, int x, int y, int w, int h) {}
}
