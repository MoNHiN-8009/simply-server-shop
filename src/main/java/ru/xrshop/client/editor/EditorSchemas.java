package ru.xrshop.client.editor;

import java.util.ArrayList;
import java.util.List;

import static ru.xrshop.client.editor.EditorFieldSpec.Kind.*;

/** Complete user-facing schema for every configurable category, product, UI and style property. */
public final class EditorSchemas {
    private enum StyleScope { GLOBAL, CATEGORY, PRODUCT }
    public static final List<EditorFieldSpec> CATEGORY = category();
    public static final List<EditorFieldSpec> PRODUCT = product();
    public static final List<EditorFieldSpec> UI = ui();
    public static final List<EditorFieldSpec> GLOBAL_STYLE = style("", "Оформление", StyleScope.GLOBAL);

    private EditorSchemas() {}

    public static List<String> groups(List<EditorFieldSpec> schema) {
        return schema.stream().map(EditorFieldSpec::group).distinct().toList();
    }

    private static List<EditorFieldSpec> category() {
        List<EditorFieldSpec> fields = new ArrayList<>();
        fields.add(f("Основное", "title", "Название", TEXT, "Видимое игроками название категории."));
        fields.add(f("Основное", "description", "Описание", TEXT_LIST, "Несколько строк разделяются символом |. JSON вводить не нужно."));
        fields.add(f("Основное", "enabled", "Категория включена", BOOLEAN, "Выключенная категория не показывается игрокам."));
        fields.add(f("Основное", "order", "Порядок", INTEGER, "Категории с меньшим числом идут раньше."));
        fields.add(f("Основное", "permission", "Право доступа", TEXT, "Пусто — доступно всем. Иначе укажите permission node."));
        fields.add(f("Основное", "tab_width", "Ширина вкладки", INTEGER, "0 — использовать общую настройку UI."));
        fields.add(f("Основное", "padding", "Внутренний отступ", INTEGER, "0 — использовать общий стиль."));
        fields.add(f("Размеры", "product_icon_size", "Размер иконок товаров", INTEGER,
                "Размер от 8 до 64 пикселей. Применяется к товарам этой категории, у которых «Размер предмета» равен 0."));
        icon(fields, false);
        fields.addAll(style("style.", "Оформление", StyleScope.CATEGORY));
        return List.copyOf(fields);
    }

    private static List<EditorFieldSpec> product() {
        List<EditorFieldSpec> fields = new ArrayList<>();
        fields.add(f("Основное", "title", "Название", TEXT, "Название карточки товара."));
        fields.add(f("Основное", "description", "Описание", TEXT_LIST, "Строки подсказки разделяются символом |."));
        fields.add(f("Основное", "price_xr", "Цена XR", LONG, "Целое неотрицательное число. 0 — бесплатно."));
        fields.add(f("Основное", "enabled", "Товар включён", BOOLEAN, "Включайте товар только после добавления хотя бы одной команды."));
        fields.add(f("Основное", "purchase_button_text", "Текст кнопки", TEXT, "Например: Купить, Получить, Обменять."));
        fields.add(f("Основное", "confirmation_enabled", "Подтверждение", TRI_STATE, "Наследовать — взять общую настройку UI."));
        fields.add(f("Основное", "permission", "Право доступа", TEXT, "Пусто — доступно всем. Иначе укажите permission node."));

        fields.add(f("Размещение", "order", "Порядок", INTEGER, "Меньшее число показывается раньше."));
        fields.add(f("Размещение", "page", "Страница", INTEGER, "0 — автоматическое размещение."));
        fields.add(f("Размещение", "row", "Строка", INTEGER, "0 — автоматическое размещение."));
        fields.add(f("Размещение", "column", "Столбец", INTEGER, "0 — автоматическое размещение."));

        fields.add(f("Ограничения", "purchase_limit", "Лимит покупок", LONG, "0 — без лимита. Иначе укажите число покупок за период."));
        fields.add(f("Ограничения", "limit_period_seconds", "Период лимита, с", LONG, "Например 86400 — одни сутки. Нужен, если лимит больше 0."));
        fields.add(f("Ограничения", "cooldown_seconds", "Пауза между покупками, с", LONG, "0 — без паузы."));
        fields.add(f("Ограничения", "success_message", "Сообщение об успехе", TEXT, "Пусто — общее сообщение мода."));
        fields.add(f("Ограничения", "error_message", "Сообщение об ошибке", TEXT, "Пусто — общее сообщение мода."));
        icon(fields, true);
        sound(fields, "sound.", "Звук", "Звук успешной покупки этого товара.");
        fields.addAll(style("style.", "Оформление", StyleScope.PRODUCT));
        return List.copyOf(fields);
    }

    private static List<EditorFieldSpec> ui() {
        List<EditorFieldSpec> fields = new ArrayList<>();
        fields.add(f("Текст", "title", "Заголовок магазина", TEXT, "Показывается в верхней части GUI."));
        fields.add(f("Текст", "empty_message", "Пустой магазин", TEXT, "Текст, если игроку не доступна ни одна категория."));
        fields.add(f("Текст", "purchase_success_text", "Успешная покупка", TEXT, "Общий текст успеха."));
        fields.add(f("Текст", "insufficient_funds_text", "Недостаточно XR", TEXT, "Текст при нехватке валюты."));
        String[][] colors = {{"background_color","Фон"},{"panel_color","Панель"},{"text_color","Текст"},{"accent_color","Акцент"},{"error_color","Ошибка"},{"success_color","Успех"}};
        for (String[] color : colors) fields.add(f("Цвета", color[0], color[1], COLOR, "ARGB-цвет вида #AARRGGBB, например #FF55AAFF."));
        String[][] sizes = {{"tab_width","Ширина вкладки"},{"card_width","Ширина карточки"},{"card_height","Высота карточки"},{"horizontal_gap","Горизонтальный зазор"},{"vertical_gap","Вертикальный зазор"},{"margin","Внешний отступ"},{"grid_rows","Строки сетки"},{"grid_columns","Столбцы сетки"}};
        for (String[] size : sizes) fields.add(f("Размеры", size[0], size[1], INTEGER, "Размер в логических пикселях. Для сетки 0 означает автоподбор."));
        fields.add(f("Размеры", "grid_left_padding", "Отступ товаров слева", INTEGER,
                "Свободное место между левым краем панели товаров и первой карточкой. 0 — без отступа, стандартно 8 пикселей."));
        fields.add(f("Поведение", "pagination_enabled", "Страницы", BOOLEAN, "Разрешить разбиение товаров по страницам."));
        fields.add(f("Поведение", "scrolling_enabled", "Прокрутка", BOOLEAN, "Разрешить прокрутку колёсиком мыши."));
        fields.add(f("Поведение", "confirmation_enabled", "Подтверждать покупку", BOOLEAN, "Общая настройка окна подтверждения."));
        fields.add(f("Поведение", "dim_world", "Затемнять мир", BOOLEAN, "Затемнять игровой мир за GUI."));
        sound(fields, "open_sound.", "Звуки", "Звук открытия магазина.");
        sound(fields, "success_sound.", "Звуки", "Звук успешной покупки.");
        sound(fields, "error_sound.", "Звуки", "Звук ошибки.");
        return List.copyOf(fields);
    }

    private static void icon(List<EditorFieldSpec> fields, boolean product) {
        fields.add(new EditorFieldSpec("Иконка", "icon.type", "Тип иконки", ENUM, "ITEM — предмет, TEXTURE — текстура из ресурспака.", List.of("ITEM", "TEXTURE")));
        fields.add(f("Иконка", "icon.item", "Предмет", ITEM, "Выбирается из меню с поиском. Используется при типе ITEM."));
        fields.add(f("Иконка", "icon.count", "Число на иконке", INTEGER, "От 1 до 99."));
        if (product) fields.add(f("Иконка", "icon.size", "Размер предмета", INTEGER,
                "0 — взять «Размер иконок товаров» из категории. Либо задайте собственный размер от 8 до 64 пикселей. Если карточка низкая, размер безопасно уменьшается без перекрытий."));
        fields.add(f("Иконка", "icon.display_nbt", "NBT иконки", NBT, "Необязательный SNBT, например {CustomModelData:1}."));
        fields.add(f("Иконка", "icon.texture", "Текстура", PATH, "ResourceLocation вида modid:textures/gui/icon.png. Нужна при TEXTURE."));
    }

    private static void sound(List<EditorFieldSpec> fields, String prefix, String group, String help) {
        fields.add(f(group, prefix + "sound", soundLabel(prefix), SOUND, help + " Пусто — без звука."));
        fields.add(f(group, prefix + "volume", soundLabel(prefix) + ": громкость", DECIMAL, "Допустимо 0..4."));
        fields.add(f(group, prefix + "pitch", soundLabel(prefix) + ": высота", DECIMAL, "Допустимо 0.1..4."));
    }

    private static String soundLabel(String prefix) {
        if (prefix.startsWith("open")) return "Открытие";
        if (prefix.startsWith("success")) return "Успех";
        if (prefix.startsWith("error")) return "Ошибка";
        return "Покупка";
    }

    private static List<EditorFieldSpec> style(String prefix, String group, StyleScope scope) {
        List<EditorFieldSpec> fields = new ArrayList<>();
        String[][] colors = scope == StyleScope.PRODUCT
                ? new String[][] {{"card_color","Карточка"},{"card_hover_color","Карточка при наведении"},
                {"button_color","Кнопка"},{"border_color","Рамка"},{"text_color","Текст"},{"price_color","Цена"}}
                : new String[][] {{"background_color","Фон"},{"panel_color","Панель"},{"category_list_color","Список категорий"},
                {"tab_color","Вкладка"},{"tab_hover_color","Вкладка при наведении"},{"tab_active_color","Активная вкладка"},
                {"card_color","Карточка"},{"card_hover_color","Карточка при наведении"},{"button_color","Кнопка"},
                {"border_color","Рамка"},{"text_color","Текст"},{"price_color","Цена"},{"balance_color","Баланс"}};
        for (String[] color : colors) fields.add(f(group, prefix + color[0], color[1], COLOR, "Цвет #AARRGGBB. Пусто в стиле категории/товара — наследовать."));
        String textures = "Текстуры";
        if (scope != StyleScope.PRODUCT) {
            fields.add(f(textures, prefix + "background_texture", "Фон магазина", TEXTURE,
                    "Отдельный фон всего магазина. В категории переопределяет фон только при выборе этой категории."));
            fields.add(f(textures, prefix + "panel_texture", "Основная панель", TEXTURE,
                    "Текстура области с товарами. Категория может переопределить общий вариант."));
            fields.add(f(textures, prefix + "category_list_texture", "Панель категорий", TEXTURE,
                    "Текстура левой панели со списком категорий."));
            fields.add(f(textures, prefix + "tab_texture", "Вкладка", TEXTURE, "Обычная текстура вкладки категории."));
            fields.add(f(textures, prefix + "tab_hover_texture", "Вкладка при наведении", TEXTURE,
                    "Текстура вкладки, когда на неё наведена мышь."));
            fields.add(f(textures, prefix + "tab_active_texture", "Активная вкладка", TEXTURE,
                    "Текстура выбранной категории."));
        }
        fields.add(f(textures, prefix + "card_texture", "Карточка товара", TEXTURE,
                "Обычная текстура карточки. В товаре применяется только к этому товару."));
        fields.add(f(textures, prefix + "card_hover_texture", "Карточка при наведении", TEXTURE,
                "Текстура карточки под курсором."));
        fields.add(f(textures, prefix + "button_texture", "Кнопка покупки", TEXTURE,
                "Текстура кнопки покупки внутри карточки."));
        fields.add(f(group, prefix + "border_width", "Толщина рамки", INTEGER, "Толщина в пикселях."));
        if (scope != StyleScope.PRODUCT)
            fields.add(f(group, prefix + "padding", "Внутренний отступ", INTEGER, "Отступ в пикселях."));
        fields.add(f(group, prefix + "opacity", "Прозрачность", INTEGER, "0 — прозрачно, 255 — непрозрачно."));
        return List.copyOf(fields);
    }

    private static EditorFieldSpec f(String group, String path, String label, EditorFieldSpec.Kind kind, String help) {
        return new EditorFieldSpec(group, path, label, kind, help);
    }
}
