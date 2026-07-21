package ru.xrshop.server.view;

import net.minecraft.server.level.ServerPlayer;
import ru.xrshop.common.config.ShopConfig;
import ru.xrshop.common.dto.StoreViewDto;
import ru.xrshop.server.config.ShopSnapshot;
import ru.xrshop.server.security.ModPermissions;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

public final class StoreViewFactory {
    public StoreViewDto create(UUID sessionId, ShopSnapshot snapshot, ServerPlayer player, long balance) {
        return create(sessionId, snapshot, balance, permission -> ModPermissions.hasNamed(player, permission));
    }

    public StoreViewDto create(UUID sessionId, ShopSnapshot snapshot, long balance, Predicate<String> permissionCheck) {
        ShopConfig.UiDefinition ui = snapshot.copyUi();
        List<StoreViewDto.CategoryDto> categories = new ArrayList<>();
        for (ShopSnapshot.CategoryEntry entry : snapshot.orderedCategories()) {
            if (!entry.enabled() || !permissionCheck.test(entry.permission())) continue;
            ShopConfig.CategoryDefinition category = entry.copyDefinition();
            List<StoreViewDto.ProductDto> products = new ArrayList<>();
            for (ShopSnapshot.ProductEntry productEntry : entry.orderedProducts()) {
                if (!productEntry.enabled() || !permissionCheck.test(productEntry.permission())) continue;
                ShopConfig.ProductDefinition product = productEntry.copyDefinition();
                products.add(new StoreViewDto.ProductDto(product.slot_id, product.title, product.description,
                        product.price_xr, product.order, product.page, product.row, product.column,
                        product.confirmation_enabled != null ? product.confirmation_enabled : ui.confirmation_enabled,
                        product.purchase_button_text,
                        icon(product.icon, product.icon.size == 0 ? category.product_icon_size : product.icon.size),
                        sound(product.sound), style(product.style)));
            }
            categories.add(new StoreViewDto.CategoryDto(category.category_id, category.title,
                    category.description, category.order, icon(category.icon), style(category.style),
                    category.tab_width, category.padding, products));
        }
        return new StoreViewDto(sessionId, snapshot.revision(), new StoreViewDto.UiDto(ui.title,
                ui.empty_message, ui.background_color, ui.panel_color, ui.text_color, ui.accent_color,
                ui.error_color, ui.success_color, ui.tab_width, ui.card_width, ui.card_height,
                ui.horizontal_gap, ui.vertical_gap, ui.margin, ui.grid_left_padding, ui.grid_rows, ui.grid_columns,
                ui.pagination_enabled, ui.scrolling_enabled, ui.confirmation_enabled, ui.dim_world,
                ui.purchase_success_text, ui.insufficient_funds_text, sound(ui.open_sound),
                sound(ui.success_sound), sound(ui.error_sound)), style(snapshot.copyStyle()),
                categories, balance);
    }

    public static StoreViewDto.IconDto icon(ShopConfig.IconDefinition icon) {
        return icon(icon, icon.size);
    }
    private static StoreViewDto.IconDto icon(ShopConfig.IconDefinition icon, int size) {
        return new StoreViewDto.IconDto(icon.type, icon.item, icon.count, size, icon.display_nbt, icon.texture);
    }
    public static StoreViewDto.StyleDto style(ShopConfig.StyleDefinition style) {
        return new StoreViewDto.StyleDto(style.background_color, style.panel_color, style.category_list_color,
                style.tab_color, style.tab_hover_color, style.tab_active_color, style.card_color,
                style.card_hover_color, style.button_color, style.border_color, style.text_color,
                style.price_color, style.balance_color, style.texture,
                texture(style.background_texture), texture(style.panel_texture), texture(style.category_list_texture),
                texture(style.tab_texture), texture(style.tab_hover_texture), texture(style.tab_active_texture),
                texture(style.card_texture), texture(style.card_hover_texture), texture(style.button_texture),
                style.border_width, style.padding, style.opacity);
    }
    public static StoreViewDto.SoundDto sound(ShopConfig.SoundDefinition sound) {
        return new StoreViewDto.SoundDto(sound == null ? "" : sound.sound,
                sound == null ? 1.0F : sound.volume, sound == null ? 1.0F : sound.pitch);
    }
    public static StoreViewDto.TextureDto texture(ShopConfig.TextureDefinition texture) {
        return new StoreViewDto.TextureDto(texture == null ? "" : texture.path,
                texture == null ? "STRETCH" : texture.mode, texture == null ? "CENTER" : texture.anchor,
                texture == null ? 0 : texture.offset_x, texture == null ? 0 : texture.offset_y,
                texture == null ? 100 : texture.scale);
    }
}
