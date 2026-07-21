package ru.xrshop.common.validation;

import net.minecraft.resources.ResourceLocation;
import ru.xrshop.common.config.ServerConfig;
import ru.xrshop.common.config.ShopConfig;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ShopValidator {
    public static final int SUPPORTED_SCHEMA = 1;
    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+");
    private static final Pattern COLOR = Pattern.compile("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([a-z_][a-z0-9_]*)}");
    private static final Set<String> PLACEHOLDERS = Set.of(
            "player", "uuid", "price", "category_id", "slot_id", "transaction_id");
    private static final Set<String> TEXTURE_MODES = Set.of("STRETCH", "COVER", "CONTAIN", "ORIGINAL", "TILE");
    private static final Set<String> TEXTURE_ANCHORS = Set.of("TOP_LEFT", "TOP", "TOP_RIGHT", "LEFT", "CENTER", "RIGHT",
            "BOTTOM_LEFT", "BOTTOM", "BOTTOM_RIGHT");

    private final ServerConfig.Limits limits;
    private final RuntimeChecks runtime;

    public ShopValidator(ServerConfig.Limits limits, RuntimeChecks runtime) {
        this.limits = limits;
        this.runtime = runtime;
    }

    public List<ValidationIssue> validate(ShopConfig config) {
        List<ValidationIssue> out = new ArrayList<>();
        if (config == null) {
            out.add(new ValidationIssue("$", "конфигурация отсутствует"));
            return out;
        }
        if (config.schema_version != SUPPORTED_SCHEMA)
            out.add(new ValidationIssue("$.schema_version", "неподдерживаемая версия " + config.schema_version));
        if (config.revision < 1) out.add(new ValidationIssue("$.revision", "должна быть не меньше 1"));
        if (config.ui == null) out.add(new ValidationIssue("$.ui", "обязательный объект отсутствует"));
        else validateUi(config.ui, out);
        validateStyle(config.style, "$.style", out);
        if (config.categories == null) {
            out.add(new ValidationIssue("$.categories", "обязательный массив отсутствует"));
            return out;
        }
        if (config.categories.size() > limits.max_categories)
            out.add(new ValidationIssue("$.categories", "превышен лимит категорий " + limits.max_categories));
        Set<String> categoryIds = new HashSet<>();
        for (int i = 0; i < config.categories.size(); i++) {
            validateCategory(config.categories.get(i), i, categoryIds, out);
        }
        return out;
    }

    public void requireValid(ShopConfig config) throws ValidationException {
        List<ValidationIssue> issues = validate(config);
        if (!issues.isEmpty()) throw new ValidationException(issues);
    }

    private void validateUi(ShopConfig.UiDefinition ui, List<ValidationIssue> out) {
        text(ui.title, "$.ui.title", limits.max_title_length, out);
        text(ui.empty_message, "$.ui.empty_message", limits.max_description_line_length, out);
        color(ui.background_color, "$.ui.background_color", true, out);
        color(ui.panel_color, "$.ui.panel_color", true, out);
        color(ui.text_color, "$.ui.text_color", true, out);
        color(ui.accent_color, "$.ui.accent_color", true, out);
        color(ui.error_color, "$.ui.error_color", true, out);
        color(ui.success_color, "$.ui.success_color", true, out);
        dimension(ui.tab_width, "$.ui.tab_width", out);
        dimension(ui.card_width, "$.ui.card_width", out);
        dimension(ui.card_height, "$.ui.card_height", out);
        nonNegative(ui.horizontal_gap, "$.ui.horizontal_gap", limits.max_dimension, out);
        nonNegative(ui.vertical_gap, "$.ui.vertical_gap", limits.max_dimension, out);
        nonNegative(ui.margin, "$.ui.margin", limits.max_dimension, out);
        nonNegative(ui.grid_left_padding, "$.ui.grid_left_padding", limits.max_dimension, out);
        nonNegative(ui.grid_rows, "$.ui.grid_rows", 128, out);
        nonNegative(ui.grid_columns, "$.ui.grid_columns", 128, out);
        sound(ui.open_sound, "$.ui.open_sound", out);
        sound(ui.success_sound, "$.ui.success_sound", out);
        sound(ui.error_sound, "$.ui.error_sound", out);
    }

    private void validateCategory(ShopConfig.CategoryDefinition category, int index,
                                  Set<String> categoryIds, List<ValidationIssue> out) {
        String path = "$.categories[" + index + "]";
        if (category == null) { out.add(new ValidationIssue(path, "null недопустим")); return; }
        id(category.category_id, path + ".category_id", out);
        if (category.category_id != null && !categoryIds.add(category.category_id))
            out.add(new ValidationIssue(path + ".category_id", "дублирующийся category_id"));
        text(category.title, path + ".title", limits.max_title_length, out);
        lines(category.description, path + ".description", out);
        permission(category.permission, path + ".permission", out);
        icon(category.icon, path + ".icon", out);
        validateStyle(category.style, path + ".style", out);
        nonNegative(category.tab_width, path + ".tab_width", limits.max_dimension, out);
        nonNegative(category.padding, path + ".padding", limits.max_dimension, out);
        iconSize(category.product_icon_size, path + ".product_icon_size", false, out);
        if (category.products == null) { out.add(new ValidationIssue(path + ".products", "массив отсутствует")); return; }
        if (category.products.size() > limits.max_products_per_category)
            out.add(new ValidationIssue(path + ".products", "превышен лимит товаров " + limits.max_products_per_category));
        Set<String> slotIds = new HashSet<>();
        for (int i = 0; i < category.products.size(); i++)
            validateProduct(category.products.get(i), path + ".products[" + i + "]", slotIds, out);
    }

    private void validateProduct(ShopConfig.ProductDefinition product, String path,
                                 Set<String> slotIds, List<ValidationIssue> out) {
        if (product == null) { out.add(new ValidationIssue(path, "null недопустим")); return; }
        id(product.slot_id, path + ".slot_id", out);
        if (product.slot_id != null && !slotIds.add(product.slot_id))
            out.add(new ValidationIssue(path + ".slot_id", "дублирующийся slot_id в категории"));
        text(product.title, path + ".title", limits.max_title_length, out);
        lines(product.description, path + ".description", out);
        if (product.price_xr < 0) out.add(new ValidationIssue(path + ".price_xr", "цена не может быть отрицательной"));
        coordinate(product.page, path + ".page", out);
        coordinate(product.row, path + ".row", out);
        coordinate(product.column, path + ".column", out);
        permission(product.permission, path + ".permission", out);
        text(product.purchase_button_text, path + ".purchase_button_text", 64, out);
        if (product.purchase_limit < 0) out.add(new ValidationIssue(path + ".purchase_limit", "лимит не может быть отрицательным"));
        range(product.limit_period_seconds, path + ".limit_period_seconds", limits.max_limit_period_seconds, out);
        range(product.cooldown_seconds, path + ".cooldown_seconds", limits.max_cooldown_seconds, out);
        if (product.purchase_limit > 0 && product.limit_period_seconds <= 0)
            out.add(new ValidationIssue(path + ".limit_period_seconds", "для лимита требуется положительный период"));
        icon(product.icon, path + ".icon", true, out);
        sound(product.sound, path + ".sound", out);
        validateStyle(product.style, path + ".style", out);
        if (product.enabled || (product.purchase_commands != null && !product.purchase_commands.isEmpty()))
            commands(product.purchase_commands, path + ".purchase_commands", out);
        else if (product.purchase_commands == null)
            out.add(new ValidationIssue(path + ".purchase_commands", "массив отсутствует"));
    }

    private void commands(List<String> commands, String path, List<ValidationIssue> out) {
        if (commands == null || commands.isEmpty()) { out.add(new ValidationIssue(path, "нужна хотя бы одна команда")); return; }
        if (commands.size() > limits.max_commands_per_product)
            out.add(new ValidationIssue(path, "превышен лимит команд " + limits.max_commands_per_product));
        for (int i = 0; i < commands.size(); i++) {
            String command = commands.get(i);
            String p = path + "[" + i + "]";
            if (command == null || command.isBlank()) { out.add(new ValidationIssue(p, "пустая команда")); continue; }
            if (command.length() > limits.max_command_length) out.add(new ValidationIssue(p, "команда слишком длинная"));
            if (command.startsWith("/")) out.add(new ValidationIssue(p, "начальный символ / запрещён"));
            if (command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0)
                out.add(new ValidationIssue(p, "переводы строк запрещены"));
            Matcher matcher = PLACEHOLDER.matcher(command);
            while (matcher.find()) if (!PLACEHOLDERS.contains(matcher.group(1)))
                out.add(new ValidationIssue(p, "неизвестный заполнитель ${" + matcher.group(1) + "}"));
            String stripped = PLACEHOLDER.matcher(command).replaceAll("");
            if (stripped.contains("${"))
                out.add(new ValidationIssue(p, "повреждённый заполнитель; используйте ${имя}"));
            String test = substitute(command, "TestPlayer", "00000000-0000-0000-0000-000000000001",
                    1, "test", "test", "00000000-0000-0000-0000-000000000002");
            String error = runtime.validateCommand(test);
            if (error != null) out.add(new ValidationIssue(p, error));
        }
    }

    public static String substitute(String command, String player, String uuid, long price,
                                    String categoryId, String slotId, String transactionId) {
        return command.replace("${player}", player).replace("${uuid}", uuid)
                .replace("${price}", Long.toString(price)).replace("${category_id}", categoryId)
                .replace("${slot_id}", slotId).replace("${transaction_id}", transactionId);
    }

    private void icon(ShopConfig.IconDefinition icon, String path, List<ValidationIssue> out) {
        icon(icon, path, false, out);
    }

    private void icon(ShopConfig.IconDefinition icon, String path, boolean allowInheritedSize,
                      List<ValidationIssue> out) {
        if (icon == null) { out.add(new ValidationIssue(path, "обязательный объект отсутствует")); return; }
        iconSize(icon.size, path + ".size", allowInheritedSize, out);
        if ("ITEM".equals(icon.type)) {
            if (!resource(icon.item)) out.add(new ValidationIssue(path + ".item", "некорректный registry ID"));
            else if (!runtime.itemExists(icon.item)) out.add(new ValidationIssue(path + ".item", "предмет не существует"));
            if (icon.count < 1 || icon.count > 99) out.add(new ValidationIssue(path + ".count", "допустимо от 1 до 99"));
            if (icon.display_nbt != null && !icon.display_nbt.isEmpty()) {
                if (icon.display_nbt.getBytes(StandardCharsets.UTF_8).length > limits.max_nbt_bytes)
                    out.add(new ValidationIssue(path + ".display_nbt", "NBT превышает лимит размера"));
                if (maxNesting(icon.display_nbt) > limits.max_nbt_depth)
                    out.add(new ValidationIssue(path + ".display_nbt", "NBT превышает лимит вложенности"));
                String error = runtime.validateDisplayNbt(icon.display_nbt);
                if (error != null) out.add(new ValidationIssue(path + ".display_nbt", error));
            }
        } else if ("TEXTURE".equals(icon.type)) {
            if (!resource(icon.texture)) out.add(new ValidationIssue(path + ".texture", "некорректный ResourceLocation"));
        } else out.add(new ValidationIssue(path + ".type", "допустимо ITEM или TEXTURE"));
    }

    private void iconSize(int size, String path, boolean allowInheritedSize, List<ValidationIssue> out) {
        if ((allowInheritedSize && size == 0) || (size >= 8 && size <= 64)) return;
        out.add(new ValidationIssue(path, allowInheritedSize
                ? "допустимо 0 для наследования или размер от 8 до 64 пикселей"
                : "размер допустим от 8 до 64 пикселей"));
    }

    private void validateStyle(ShopConfig.StyleDefinition style, String path, List<ValidationIssue> out) {
        if (style == null) { out.add(new ValidationIssue(path, "обязательный объект отсутствует")); return; }
        color(style.background_color, path + ".background_color", true, out);
        color(style.panel_color, path + ".panel_color", true, out);
        color(style.category_list_color, path + ".category_list_color", true, out);
        color(style.tab_color, path + ".tab_color", true, out);
        color(style.tab_hover_color, path + ".tab_hover_color", true, out);
        color(style.tab_active_color, path + ".tab_active_color", true, out);
        color(style.card_color, path + ".card_color", true, out);
        color(style.card_hover_color, path + ".card_hover_color", true, out);
        color(style.button_color, path + ".button_color", true, out);
        color(style.border_color, path + ".border_color", true, out);
        color(style.text_color, path + ".text_color", true, out);
        color(style.price_color, path + ".price_color", true, out);
        color(style.balance_color, path + ".balance_color", true, out);
        if (style.texture != null && !style.texture.isBlank() && !resource(style.texture))
            out.add(new ValidationIssue(path + ".texture", "некорректный ResourceLocation"));
        texture(style.background_texture, path + ".background_texture", out);
        texture(style.panel_texture, path + ".panel_texture", out);
        texture(style.category_list_texture, path + ".category_list_texture", out);
        texture(style.tab_texture, path + ".tab_texture", out);
        texture(style.tab_hover_texture, path + ".tab_hover_texture", out);
        texture(style.tab_active_texture, path + ".tab_active_texture", out);
        texture(style.card_texture, path + ".card_texture", out);
        texture(style.card_hover_texture, path + ".card_hover_texture", out);
        texture(style.button_texture, path + ".button_texture", out);
        nonNegative(style.border_width, path + ".border_width", 32, out);
        nonNegative(style.padding, path + ".padding", limits.max_dimension, out);
        nonNegative(style.opacity, path + ".opacity", 255, out);
    }

    private void sound(ShopConfig.SoundDefinition sound, String path, List<ValidationIssue> out) {
        if (sound == null) return;
        if (sound.sound != null && !sound.sound.isBlank() && !resource(sound.sound))
            out.add(new ValidationIssue(path + ".sound", "некорректный ResourceLocation"));
        if (!Float.isFinite(sound.volume) || sound.volume < 0 || sound.volume > 4)
            out.add(new ValidationIssue(path + ".volume", "допустимо от 0 до 4"));
        if (!Float.isFinite(sound.pitch) || sound.pitch < 0.1F || sound.pitch > 4)
            out.add(new ValidationIssue(path + ".pitch", "допустимо от 0.1 до 4"));
    }

    private void texture(ShopConfig.TextureDefinition texture, String path, List<ValidationIssue> out) {
        if (texture == null) { out.add(new ValidationIssue(path, "объект текстуры отсутствует")); return; }
        if (texture.path != null && !texture.path.isBlank() && !resource(texture.path))
            out.add(new ValidationIssue(path + ".path", "некорректный ResourceLocation"));
        if (texture.mode == null || !TEXTURE_MODES.contains(texture.mode))
            out.add(new ValidationIssue(path + ".mode", "допустимо STRETCH, COVER, CONTAIN, ORIGINAL или TILE"));
        if (texture.anchor == null || !TEXTURE_ANCHORS.contains(texture.anchor))
            out.add(new ValidationIssue(path + ".anchor", "некорректная привязка"));
        if (Math.abs((long) texture.offset_x) > limits.max_dimension)
            out.add(new ValidationIssue(path + ".offset_x", "смещение превышает допустимый размер"));
        if (Math.abs((long) texture.offset_y) > limits.max_dimension)
            out.add(new ValidationIssue(path + ".offset_y", "смещение превышает допустимый размер"));
        if (texture.scale < 10 || texture.scale > 400)
            out.add(new ValidationIssue(path + ".scale", "масштаб допустим от 10 до 400 процентов"));
    }

    private void id(String value, String path, List<ValidationIssue> out) {
        if (value == null || value.isEmpty() || value.length() > limits.max_id_length || !ID.matcher(value).matches())
            out.add(new ValidationIssue(path, "ожидается [a-z0-9_.-]+ длиной до " + limits.max_id_length));
    }
    private void text(String value, String path, int max, List<ValidationIssue> out) {
        if (value == null || value.isBlank()) out.add(new ValidationIssue(path, "строка не должна быть пустой"));
        else if (value.length() > max) out.add(new ValidationIssue(path, "строка длиннее " + max));
    }
    private void lines(List<String> values, String path, List<ValidationIssue> out) {
        if (values == null) { out.add(new ValidationIssue(path, "массив отсутствует")); return; }
        if (values.size() > limits.max_description_lines) out.add(new ValidationIssue(path, "слишком много строк"));
        for (int i = 0; i < values.size(); i++) {
            String value = values.get(i);
            if (value == null || value.length() > limits.max_description_line_length)
                out.add(new ValidationIssue(path + "[" + i + "]", "строка отсутствует или слишком длинная"));
        }
    }
    private void permission(String value, String path, List<ValidationIssue> out) {
        if (value != null && (value.length() > limits.max_permission_length || (!value.isBlank() && !ID.matcher(value).matches())))
            out.add(new ValidationIssue(path, "некорректное имя права"));
    }
    private void color(String value, String path, boolean optional, List<ValidationIssue> out) {
        if ((value == null || value.isEmpty()) && optional) return;
        if (value == null || !COLOR.matcher(value).matches())
            out.add(new ValidationIssue(path, "ожидается #RRGGBB или #AARRGGBB"));
    }
    private void dimension(int value, String path, List<ValidationIssue> out) {
        if (value < 16 || value > limits.max_dimension) out.add(new ValidationIssue(path, "размер вне диапазона 16.." + limits.max_dimension));
    }
    private void coordinate(int value, String path, List<ValidationIssue> out) { nonNegative(value, path, limits.max_coordinate, out); }
    private void nonNegative(int value, String path, int max, List<ValidationIssue> out) {
        if (value < 0 || value > max) out.add(new ValidationIssue(path, "значение вне диапазона 0.." + max));
    }
    private void range(long value, String path, long max, List<ValidationIssue> out) {
        if (value < 0 || value > max) out.add(new ValidationIssue(path, "значение вне диапазона 0.." + max));
    }
    private static boolean resource(String value) { return value != null && ResourceLocation.tryParse(value) != null; }
    private static int maxNesting(String text) {
        int current = 0, max = 0;
        boolean quoted = false, escaped = false;
        for (char c : text.toCharArray()) {
            if (escaped) { escaped = false; continue; }
            if (c == '\\' && quoted) { escaped = true; continue; }
            if (c == '"') { quoted = !quoted; continue; }
            if (!quoted && (c == '{' || c == '[')) max = Math.max(max, ++current);
            else if (!quoted && (c == '}' || c == ']')) current--;
        }
        return max;
    }

    public interface RuntimeChecks {
        boolean itemExists(String resourceLocation);
        String validateDisplayNbt(String nbt);
        String validateCommand(String substitutedCommand);
    }
}
