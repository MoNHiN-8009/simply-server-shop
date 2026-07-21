package ru.xrshop.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.item.ItemStack;
import ru.xrshop.common.dto.PurchaseResultCode;
import ru.xrshop.common.dto.StoreViewDto;
import ru.xrshop.common.network.ServerEventS2C;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class StoreScreen extends Screen {
    private StoreViewDto dto;
    private String selectedCategory;
    private int page;
    private int productScroll;
    private int categoryScroll;
    private boolean processing;
    private StoreViewDto.ProductDto confirmation;
    private StoreViewDto.ProductDto lastPurchased;
    private String status = "";
    private int statusColor = 0xFFFFFFFF;
    private LayoutCalculator.Layout layout;
    private final List<CardHit> cards = new ArrayList<>();
    private final Map<String, ItemStack> itemIcons = new HashMap<>();
    private final TextureRenderer textureRenderer = new TextureRenderer();
    private boolean closed;

    public StoreScreen(StoreViewDto dto) {
        super(Component.literal(dto.ui().title())); this.dto = dto;
        selectedCategory = dto.categories().isEmpty() ? null : dto.categories().get(0).categoryId();
        rebuildIconCache();
    }

    public UUID sessionId() { return dto.sessionId(); }

    @Override protected void init() {
        addRenderableWidget(Button.builder(Component.translatable("xrshop.screen.store.close"), button -> onClose())
                .bounds(Math.max(4, width - 74), Math.max(4, height - 24), 70, 20).build());
        play(dto.ui().openSound());
    }

    public void update(StoreViewDto updated) {
        this.dto = updated;
        if (selectedCategory == null || updated.categories().stream().noneMatch(c -> c.categoryId().equals(selectedCategory)))
            selectedCategory = updated.categories().isEmpty() ? null : updated.categories().get(0).categoryId();
        page = 0; productScroll = 0; categoryScroll = 0; processing = false; confirmation = null;
        rebuildIconCache();
    }

    public void serverEvent(ServerEventS2C event) {
        if (event.sessionId() != null && !event.sessionId().equals(dto.sessionId())) return;
        processing = event.code() == PurchaseResultCode.PROCESSING;
        status = event.message();
        boolean success = event.code() == PurchaseResultCode.SUCCESS;
        statusColor = color(success ? dto.ui().successColor() : dto.ui().errorColor(), success ? 0xFF55FF55 : 0xFFFF5555);
        if (event.balance() >= 0) dto = new StoreViewDto(dto.sessionId(), dto.revision(), dto.ui(), dto.style(), dto.categories(), event.balance());
        if (success) play(lastPurchased != null && lastPurchased.sound() != null && !lastPurchased.sound().sound().isBlank()
                ? lastPurchased.sound() : dto.ui().successSound());
        else if (event.code() != PurchaseResultCode.PROCESSING) play(dto.ui().errorSound());
        if (!processing) { confirmation = null; lastPurchased = null; }
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (dto.ui().dimWorld()) renderBackground(graphics);
        StoreViewDto.CategoryDto activeCategory = selected();
        StoreViewDto.StyleDto activeStyle = activeCategory == null ? null : activeCategory.style();
        int background = color(nonBlank(activeStyle == null ? "" : activeStyle.backgroundColor(),
                dto.style().backgroundColor(), dto.ui().backgroundColor()), 0xCC101018);
        graphics.fill(0, 0, width, height, background);
        drawBackgroundTexture(graphics, activeStyle);
        layout = LayoutCalculator.calculate(width, height, dto.ui().margin(), dto.ui().tabWidth(),
                dto.ui().cardWidth(), dto.ui().cardHeight(), dto.ui().horizontalGap(), dto.ui().gridLeftPadding());
        int panel = color(nonBlank(activeStyle == null ? "" : activeStyle.panelColor(),
                dto.style().panelColor(), dto.ui().panelColor()), 0xEE181820);
        int categoryPanel = color(nonBlank(activeStyle == null ? "" : activeStyle.categoryListColor(),
                dto.style().categoryListColor(), dto.style().panelColor(), dto.ui().panelColor()), 0xEE181820);
        graphics.fill(layout.margin(), layout.margin(), layout.margin() + layout.tabWidth(), height - layout.margin(), categoryPanel);
        graphics.fill(layout.contentX(), layout.margin(), width - layout.margin(), height - layout.margin(), panel);
        textureRenderer.draw(graphics, firstTexture(activeStyle == null ? null : activeStyle.categoryListTexture(),
                        dto.style().categoryListTexture()), layout.margin(), layout.margin(), layout.tabWidth(), height - layout.margin() * 2);
        textureRenderer.draw(graphics, firstTexture(activeStyle == null ? null : activeStyle.panelTexture(),
                        dto.style().panelTexture()), layout.contentX(), layout.margin(),
                width - layout.margin() - layout.contentX(), height - layout.margin() * 2);
        graphics.drawString(font, dto.ui().title(), layout.contentX() + 6, layout.margin() + 6,
                color(dto.ui().textColor(), 0xFFFFFFFF), false);
        String balance = Component.translatable("xrshop.screen.store.balance", dto.balance()).getString();
        graphics.drawString(font, balance, Math.max(layout.contentX() + 6, width - layout.margin() - font.width(balance) - 6),
                layout.margin() + 6, color(nonBlank(dto.style().balanceColor(), dto.ui().accentColor()), 0xFF55AAFF), false);
        renderCategories(graphics, mouseX, mouseY);
        renderProducts(graphics, mouseX, mouseY);
        if (!status.isBlank()) graphics.drawCenteredString(font, trim(status, Math.max(80, width - 180)), width / 2, height - 19, statusColor);
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
        if (confirmation != null) renderConfirmation(graphics, mouseX, mouseY);
    }

    private void renderCategories(GuiGraphics graphics, int mouseX, int mouseY) {
        int categoryTop = layout.margin() + 28;
        categoryScroll = StoreScrollLimits.clamp(categoryScroll,
                StoreScrollLimits.linear(dto.categories().size(), 26, height - layout.margin() - categoryTop));
        int x = layout.margin() + 4, y = categoryTop - categoryScroll;
        int w = layout.tabWidth() - 8;
        if (dto.categories().isEmpty()) {
            graphics.drawWordWrap(font, Component.literal(dto.ui().emptyMessage()), x, y, w,
                    color(dto.ui().textColor(), 0xFFFFFFFF)); return;
        }
        for (StoreViewDto.CategoryDto category : dto.categories()) {
            int categoryWidth = category.tabWidth() > 0 ? Math.max(48, Math.min(w, category.tabWidth())) : w;
            int padding = Math.max(2, Math.min(12, category.padding()));
            boolean hover = inside(mouseX, mouseY, x, y, categoryWidth, 23);
            boolean selected = category.categoryId().equals(selectedCategory);
            String configured = selected ? category.style().tabActiveColor() : hover ? category.style().tabHoverColor() : category.style().tabColor();
            String global = selected ? dto.style().tabActiveColor() : hover ? dto.style().tabHoverColor() : dto.style().tabColor();
            graphics.fill(x, y, x + categoryWidth, y + 23, withOpacity(color(nonBlank(configured, global), selected ? 0xFF315A7D : hover ? 0xFF2A3948 : 0xFF202830), category.style().opacity()));
            StoreViewDto.TextureDto tabTexture = selected
                    ? firstTexture(category.style().tabActiveTexture(), dto.style().tabActiveTexture())
                    : hover ? firstTexture(category.style().tabHoverTexture(), dto.style().tabHoverTexture())
                    : firstTexture(category.style().tabTexture(), dto.style().tabTexture());
            textureRenderer.draw(graphics, tabTexture, x, y, categoryWidth, 23);
            drawIcon(graphics, category.categoryId() + "/", category.icon(), x + padding, y + 3);
            String title = trim(category.title(), categoryWidth - 22 - padding * 2);
            graphics.drawString(font, title, x + 19 + padding, y + 7,
                    color(nonBlank(category.style().textColor(), dto.style().textColor(), dto.ui().textColor()), 0xFFFFFFFF), false);
            y += 26;
        }
    }

    private void renderProducts(GuiGraphics graphics, int mouseX, int mouseY) {
        cards.clear(); StoreViewDto.CategoryDto category = selected(); if (category == null) return;
        List<StoreViewDto.ProductDto> products = category.products().stream().filter(p -> p.page() == page)
                .sorted(Comparator.comparingInt(StoreViewDto.ProductDto::order).thenComparing(StoreViewDto.ProductDto::slotId)).toList();
        int maxPage = category.products().stream().mapToInt(StoreViewDto.ProductDto::page).max().orElse(0);
        int top = layout.margin() + 29;
        if (maxPage > 0) {
            graphics.drawCenteredString(font, "‹  " + (page + 1) + "/" + (maxPage + 1) + "  ›",
                    layout.gridX() + layout.gridWidth() / 2, layout.margin() + 17, color(dto.ui().textColor(), 0xFFFFFFFF));
        }
        int columns = dto.ui().gridColumns() > 0 ? Math.max(1, Math.min(layout.columns(), dto.ui().gridColumns())) : layout.columns();
        int contentBottom = height - layout.margin() - 24;
        productScroll = StoreScrollLimits.clamp(productScroll, StoreScrollLimits.products(products, columns,
                layout.cardHeight(), dto.ui().verticalGap(), contentBottom - top));
        Set<Integer> used = new HashSet<>(); int sequential = 0;
        for (StoreViewDto.ProductDto product : products) {
            int desired = product.row() * columns + Math.min(product.column(), columns - 1);
            int position = desired >= 0 && !used.contains(desired) ? desired : sequential;
            while (used.contains(position)) position++; used.add(position); sequential = Math.max(sequential, position + 1);
            int col = position % columns, row = position / columns;
            int x = layout.gridX() + col * (layout.cardWidth() + layout.horizontalGap());
            int y = top + row * (layout.cardHeight() + dto.ui().verticalGap()) - productScroll;
            if (y + layout.cardHeight() < top || y > height - layout.margin() - 24) continue;
            boolean hover = inside(mouseX, mouseY, x, y, layout.cardWidth(), layout.cardHeight());
            String configured = hover ? product.style().cardHoverColor() : product.style().cardColor();
            String categoryColor = hover ? category.style().cardHoverColor() : category.style().cardColor();
            String global = hover ? dto.style().cardHoverColor() : dto.style().cardColor();
            graphics.fill(x, y, x + layout.cardWidth(), y + layout.cardHeight(), withOpacity(color(nonBlank(configured, categoryColor, global), hover ? 0xFF303C49 : 0xFF252C35), product.style().opacity()));
            StoreViewDto.TextureDto cardTexture = hover
                    ? firstTexture(product.style().cardHoverTexture(), product.style().cardTexture(),
                    category.style().cardHoverTexture(), category.style().cardTexture(),
                    dto.style().cardHoverTexture(), dto.style().cardTexture(),
                    legacyTexture(product.style().legacyTexture()), legacyTexture(category.style().legacyTexture()),
                    legacyTexture(dto.style().legacyTexture()))
                    : firstTexture(product.style().cardTexture(), category.style().cardTexture(), dto.style().cardTexture(),
                    legacyTexture(product.style().legacyTexture()), legacyTexture(category.style().legacyTexture()),
                    legacyTexture(dto.style().legacyTexture()));
            textureRenderer.draw(graphics, cardTexture, x, y, layout.cardWidth(), layout.cardHeight());
            int border = Math.max(product.style().borderWidth(), Math.max(category.style().borderWidth(), dto.style().borderWidth()));
            int borderColor = color(nonBlank(product.style().borderColor(), category.style().borderColor(), dto.style().borderColor()), 0xFF586675);
            drawBorder(graphics, x, y, layout.cardWidth(), layout.cardHeight(), border, borderColor);
            LayoutCalculator.CardContent cardContent = LayoutCalculator.cardContent(layout.cardHeight(), product.icon().size());
            int buttonY = y + cardContent.buttonY();
            int iconSize = cardContent.iconSize();
            int iconY = y + cardContent.iconY();
            drawIcon(graphics, category.categoryId() + "/" + product.slotId(), product.icon(),
                    x + (layout.cardWidth() - iconSize) / 2, iconY, iconSize);
            int text = color(nonBlank(product.style().textColor(), category.style().textColor(), dto.style().textColor(), dto.ui().textColor()), 0xFFFFFFFF);
            int titleY = y + cardContent.titleY();
            graphics.drawCenteredString(font, trim(product.title(), layout.cardWidth() - 8), x + layout.cardWidth() / 2, titleY, text);
            int price = color(nonBlank(product.style().priceColor(), category.style().priceColor(), dto.style().priceColor(), dto.ui().accentColor()), 0xFF55AAFF);
            graphics.drawCenteredString(font, product.priceXr() + " XR", x + layout.cardWidth() / 2, y + cardContent.priceY(), price);
            graphics.fill(x + 5, buttonY, x + layout.cardWidth() - 5, buttonY + 16,
                    color(nonBlank(product.style().buttonColor(), category.style().buttonColor(), dto.style().buttonColor()), processing ? 0xFF444444 : 0xFF28649A));
            textureRenderer.draw(graphics, firstTexture(product.style().buttonTexture(), category.style().buttonTexture(),
                    dto.style().buttonTexture()), x + 5, buttonY, layout.cardWidth() - 10, 16);
            String button = processing ? Component.translatable("xrshop.screen.store.processing").getString() : product.purchaseButtonText();
            graphics.drawCenteredString(font, trim(button, layout.cardWidth() - 14), x + layout.cardWidth() / 2, buttonY + 4, 0xFFFFFFFF);
            cards.add(new CardHit(product, x, y, layout.cardWidth(), layout.cardHeight()));
        }
        if (products.isEmpty()) graphics.drawCenteredString(font, dto.ui().emptyMessage(), layout.gridX() + layout.gridWidth() / 2,
                top + 24, color(dto.ui().textColor(), 0xFFFFFFFF));
    }

    private void renderTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        int categoryX = layout.margin() + 4, categoryY = layout.margin() + 28 - categoryScroll;
        int categoryMaxWidth = layout.tabWidth() - 8;
        for (StoreViewDto.CategoryDto category : dto.categories()) {
            int categoryWidth = category.tabWidth() > 0 ? Math.max(48, Math.min(categoryMaxWidth, category.tabWidth())) : categoryMaxWidth;
            if (!category.description().isEmpty() && inside(mouseX, mouseY, categoryX, categoryY, categoryWidth, 23)) {
                graphics.renderComponentTooltip(font, category.description().stream().map(line -> (Component) Component.literal(line)).toList(), mouseX, mouseY);
                return;
            }
            categoryY += 26;
        }
        for (CardHit card : cards) if (inside(mouseX, mouseY, card.x, card.y, card.w, card.h)) {
            List<Component> lines = new ArrayList<>(); lines.add(Component.literal(card.product.title()));
            card.product.description().forEach(line -> lines.add(Component.literal(line.replace("{price}", Long.toString(card.product.priceXr())))));
            lines.add(Component.literal("Цена: " + card.product.priceXr() + " XR"));
            graphics.renderComponentTooltip(font, lines, mouseX, mouseY); return;
        }
    }

    private void renderConfirmation(GuiGraphics graphics, int mouseX, int mouseY) {
        int w = Math.min(280, width - 20), h = 90, x = (width - w) / 2, y = (height - h) / 2;
        graphics.fill(0, 0, width, height, 0x99000000); graphics.fill(x, y, x + w, y + h, color(dto.ui().panelColor(), 0xFF181820));
        drawBorder(graphics, x, y, w, h, 1, color(dto.ui().accentColor(), 0xFF55AAFF));
        graphics.drawCenteredString(font, Component.translatable("xrshop.screen.store.confirm"), width / 2, y + 15, color(dto.ui().textColor(), 0xFFFFFFFF));
        graphics.drawCenteredString(font, confirmation.title() + " — " + confirmation.priceXr() + " XR", width / 2, y + 31, color(dto.ui().accentColor(), 0xFF55AAFF));
        int by = y + 57; graphics.fill(x + 20, by, x + w / 2 - 5, by + 20, inside(mouseX, mouseY, x + 20, by, w / 2 - 25, 20) ? 0xFF377AB2 : 0xFF285C88);
        graphics.fill(x + w / 2 + 5, by, x + w - 20, by + 20, inside(mouseX, mouseY, x + w / 2 + 5, by, w / 2 - 25, 20) ? 0xFF744444 : 0xFF593333);
        graphics.drawCenteredString(font, Component.translatable("xrshop.screen.store.yes"), x + w / 4 + 7, by + 6, 0xFFFFFFFF);
        graphics.drawCenteredString(font, Component.translatable("xrshop.screen.store.no"), x + 3 * w / 4 - 7, by + 6, 0xFFFFFFFF);
    }

    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button);
        if (confirmation != null) {
            int w = Math.min(280, width - 20), h = 90, x = (width - w) / 2, y = (height - h) / 2, by = y + 57;
            if (inside(mouseX, mouseY, x + 20, by, w / 2 - 25, 20)) { StoreViewDto.ProductDto product = confirmation; confirmation = null; buy(product); }
            else if (inside(mouseX, mouseY, x + w / 2 + 5, by, w / 2 - 25, 20)) confirmation = null;
            return true;
        }
        int x = layout.margin() + 4, y = layout.margin() + 28 - categoryScroll, w = layout.tabWidth() - 8;
        for (StoreViewDto.CategoryDto category : dto.categories()) {
            int categoryWidth = category.tabWidth() > 0 ? Math.max(48, Math.min(w, category.tabWidth())) : w;
            if (inside(mouseX, mouseY, x, y, categoryWidth, 23)) { selectedCategory = category.categoryId(); page = 0; productScroll = 0; return true; }
            y += 26;
        }
        StoreViewDto.CategoryDto category = selected();
        if (category != null) {
            int maxPage = category.products().stream().mapToInt(StoreViewDto.ProductDto::page).max().orElse(0);
            int center = layout.gridX() + layout.gridWidth() / 2;
            if (maxPage > 0 && mouseY >= layout.margin() + 13 && mouseY <= layout.margin() + 28) {
                if (mouseX < center) page = Math.max(0, page - 1); else page = Math.min(maxPage, page + 1);
                productScroll = 0; return true;
            }
        }
        if (!processing) for (CardHit card : cards) if (inside(mouseX, mouseY, card.x, card.y, card.w, card.h)) {
            if (card.product.confirmationEnabled()) confirmation = card.product; else buy(card.product);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (layout == null) return super.mouseScrolled(mouseX, mouseY, delta);
        if (mouseX < layout.contentX()) {
            int top = layout.margin() + 28;
            int maximum = StoreScrollLimits.linear(dto.categories().size(), 26, height - layout.margin() - top);
            categoryScroll = StoreScrollLimits.clamp(categoryScroll - (int) (delta * 22), maximum);
        } else if (dto.ui().scrollingEnabled()) {
            StoreViewDto.CategoryDto category = selected();
            if (category == null) return true;
            List<StoreViewDto.ProductDto> products = category.products().stream().filter(p -> p.page() == page)
                    .sorted(Comparator.comparingInt(StoreViewDto.ProductDto::order).thenComparing(StoreViewDto.ProductDto::slotId)).toList();
            int columns = dto.ui().gridColumns() > 0 ? Math.max(1, Math.min(layout.columns(), dto.ui().gridColumns())) : layout.columns();
            int top = layout.margin() + 29, bottom = height - layout.margin() - 24;
            int maximum = StoreScrollLimits.products(products, columns, layout.cardHeight(), dto.ui().verticalGap(), bottom - top);
            productScroll = StoreScrollLimits.clamp(productScroll - (int) (delta * 28), maximum);
        }
        return true;
    }

    private void buy(StoreViewDto.ProductDto product) {
        if (minecraft == null || minecraft.getConnection() == null || selectedCategory == null) return;
        processing = true; status = Component.translatable("xrshop.screen.store.processing").getString();
        lastPurchased = product;
        minecraft.getConnection().sendCommand("ms buy category " + selectedCategory + " slot " + product.slotId());
    }

    private StoreViewDto.CategoryDto selected() {
        return dto.categories().stream().filter(c -> c.categoryId().equals(selectedCategory)).findFirst().orElse(null);
    }
    private void rebuildIconCache() {
        itemIcons.clear();
        for (StoreViewDto.CategoryDto category : dto.categories()) {
            cacheIcon(category.categoryId() + "/", category.icon());
            for (StoreViewDto.ProductDto product : category.products()) cacheIcon(category.categoryId() + "/" + product.slotId(), product.icon());
        }
    }
    private void cacheIcon(String key, StoreViewDto.IconDto icon) {
        if (icon == null || !"ITEM".equals(icon.type())) return;
        ResourceLocation id = ResourceLocation.tryParse(icon.item());
        if (id == null) return;
        BuiltInRegistries.ITEM.getOptional(id).ifPresent(item -> {
            ItemStack stack = new ItemStack(item, Math.max(1, Math.min(99, icon.count())));
            if (icon.displayNbt() != null && !icon.displayNbt().isBlank()) try { stack.setTag(TagParser.parseTag(icon.displayNbt())); } catch (Exception ignored) {}
            itemIcons.put(key, stack);
        });
    }
    private void drawIcon(GuiGraphics graphics, String key, StoreViewDto.IconDto icon, int x, int y) {
        drawIcon(graphics, key, icon, x, y, 16);
    }
    private void drawIcon(GuiGraphics graphics, String key, StoreViewDto.IconDto icon, int x, int y, int size) {
        if (icon == null) return;
        int safeSize = Math.max(8, Math.min(64, size));
        if ("ITEM".equals(icon.type())) {
            ItemStack stack = itemIcons.get(key);
            if (stack != null) {
                float scale = safeSize / 16F;
                graphics.pose().pushPose();
                graphics.pose().translate(x, y, 0);
                graphics.pose().scale(scale, scale, 1F);
                graphics.renderItem(stack, 0, 0);
                graphics.renderItemDecorations(font, stack, 0, 0);
                graphics.pose().popPose();
            }
        } else if ("TEXTURE".equals(icon.type()) && icon.texture() != null && !icon.texture().isBlank()) {
            textureRenderer.draw(graphics, icon.texture(), "CONTAIN", "CENTER", 0, 0, 100,
                    x, y, safeSize, safeSize);
        }
    }
    private void drawBackgroundTexture(GuiGraphics graphics, StoreViewDto.StyleDto activeStyle) {
        StoreViewDto.TextureDto texture = firstTexture(activeStyle == null ? null : activeStyle.backgroundTexture(),
                dto.style().backgroundTexture(), legacyTexture(dto.style().legacyTexture()));
        textureRenderer.draw(graphics, texture, 0, 0, width, height);
    }
    private void play(StoreViewDto.SoundDto sound) {
        if (sound == null || sound.sound() == null || sound.sound().isBlank()) return;
        ResourceLocation id = ResourceLocation.tryParse(sound.sound());
        if (id == null || minecraft == null || minecraft.player == null) return;
        BuiltInRegistries.SOUND_EVENT.getOptional(id).ifPresent(event -> minecraft.player.playSound(event, sound.volume(), sound.pitch()));
    }
    private String trim(String text, int pixels) { return font.width(text) <= pixels ? text : font.plainSubstrByWidth(text, Math.max(1, pixels - font.width("…"))) + "…"; }
    private static String nonBlank(String... values) { for (String value : values) if (value != null && !value.isBlank()) return value; return ""; }
    private static StoreViewDto.TextureDto firstTexture(StoreViewDto.TextureDto... values) {
        for (StoreViewDto.TextureDto value : values)
            if (value != null && value.path() != null && !value.path().isBlank()) return value;
        return null;
    }
    private static StoreViewDto.TextureDto legacyTexture(String path) {
        return path == null || path.isBlank() ? null : new StoreViewDto.TextureDto(path, "STRETCH", "CENTER", 0, 0, 100);
    }
    static int color(String value, int fallback) {
        try { if (value == null || value.isBlank()) return fallback; String hex = value.substring(1); if (hex.length() == 6) hex = "FF" + hex; return (int) Long.parseLong(hex, 16); }
        catch (RuntimeException ex) { return fallback; }
    }
    private static int withOpacity(int argb, int opacity) {
        int alpha = (argb >>> 24) & 0xFF;
        int adjusted = alpha * Math.max(0, Math.min(255, opacity)) / 255;
        return (argb & 0x00FFFFFF) | (adjusted << 24);
    }
    private static void drawBorder(GuiGraphics graphics, int x, int y, int w, int h, int size, int color) {
        int s = Math.max(0, Math.min(8, size)); if (s == 0) return;
        graphics.fill(x, y, x + w, y + s, color); graphics.fill(x, y + h - s, x + w, y + h, color);
        graphics.fill(x, y, x + s, y + h, color); graphics.fill(x + w - s, y, x + w, y + h, color);
    }
    private static boolean inside(double mx, double my, int x, int y, int w, int h) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    @Override public void removed() { if (!closed) { closed = true; ClientState.closeStore(dto.sessionId()); } super.removed(); }
    @Override public boolean isPauseScreen() { return false; }
    private record CardHit(StoreViewDto.ProductDto product, int x, int y, int w, int h) {}
}
