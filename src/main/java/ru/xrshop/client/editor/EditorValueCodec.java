package ru.xrshop.client.editor;

import com.google.gson.JsonElement;
import ru.xrshop.common.config.JsonCodec;
import ru.xrshop.common.config.ShopConfig;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public final class EditorValueCodec {
    private EditorValueCodec() {}

    public static Object read(Object root, String path) {
        try {
            Object value = root;
            for (String part : path.split("\\.")) {
                if (value == null) return null;
                Field field = value.getClass().getField(part);
                value = field.get(value);
            }
            return value;
        } catch (ReflectiveOperationException ex) {
            throw new IllegalArgumentException("Неизвестное поле редактора: " + path, ex);
        }
    }

    public static String display(Object root, EditorFieldSpec spec) {
        Object value = read(root, spec.path());
        if (value == null) return spec.kind() == EditorFieldSpec.Kind.TRI_STATE ? "Наследовать" : "";
        if (value instanceof List<?> list) return list.stream().map(String::valueOf).collect(Collectors.joining(" | "));
        if (value instanceof Boolean bool) return bool ? "Да" : "Нет";
        if (value instanceof ShopConfig.TextureDefinition texture)
            return JsonCodec.GSON.toJson(texture);
        return String.valueOf(value);
    }

    public static String toJson(EditorFieldSpec spec, String displayed) {
        String value = displayed == null ? "" : displayed.trim();
        try {
            return switch (spec.kind()) {
                case TEXT, COLOR, RESOURCE, ITEM, NBT, SOUND, PATH -> JsonCodec.GSON.toJson(value);
                case TEXTURE -> {
                    ShopConfig.TextureDefinition texture = JsonCodec.GSON.fromJson(value, ShopConfig.TextureDefinition.class);
                    if (texture == null) throw new IllegalArgumentException("Настройка текстуры отсутствует");
                    yield JsonCodec.GSON.toJson(texture);
                }
                case TEXT_LIST -> JsonCodec.GSON.toJson(value.isBlank() ? List.of() :
                        Arrays.stream(value.split("\\s*\\|\\s*", -1)).filter(s -> !s.isBlank()).toList());
                case BOOLEAN -> parseBoolean(value);
                case TRI_STATE -> parseTriState(value);
                case INTEGER -> Integer.toString(Integer.parseInt(value));
                case LONG -> Long.toString(Long.parseLong(value));
                case DECIMAL -> Float.toString(Float.parseFloat(value.replace(',', '.')));
                case ENUM -> {
                    if (!spec.options().contains(value)) throw new IllegalArgumentException("Выберите значение из списка");
                    yield JsonCodec.GSON.toJson(value);
                }
            };
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Ожидается число");
        }
    }

    public static String normalizeJson(EditorFieldSpec spec, String displayed) {
        JsonElement parsed = JsonCodec.parse(toJson(spec, displayed));
        return parsed.toString();
    }

    private static String parseBoolean(String value) {
        if (value.equalsIgnoreCase("Да") || value.equalsIgnoreCase("true")) return "true";
        if (value.equalsIgnoreCase("Нет") || value.equalsIgnoreCase("false")) return "false";
        throw new IllegalArgumentException("Выберите «Да» или «Нет»");
    }

    private static String parseTriState(String value) {
        if (value.equalsIgnoreCase("Наследовать") || value.equalsIgnoreCase("null")) return "null";
        return parseBoolean(value);
    }
}
