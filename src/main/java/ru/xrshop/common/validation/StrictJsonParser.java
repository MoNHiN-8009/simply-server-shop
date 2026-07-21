package ru.xrshop.common.validation;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.internal.Streams;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class StrictJsonParser {
    private StrictJsonParser() {}

    public static JsonElement parse(String text) {
        try {
            JsonReader reader = new JsonReader(new StringReader(text));
            reader.setLenient(false);
            JsonElement result = Streams.parse(reader);
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw new JsonParseException("Лишние данные после корневого JSON-объекта");
            }
            return result;
        } catch (IOException | IllegalStateException ex) {
            throw new JsonParseException("Повреждённый JSON: " + ex.getMessage(), ex);
        }
    }

    public static void rejectUnknownFields(JsonElement element, Class<?> type, String path,
                                           List<ValidationIssue> issues) {
        inspect(element, type, path, issues);
    }

    private static void inspect(JsonElement element, Type type, String path, List<ValidationIssue> issues) {
        if (element == null || element.isJsonNull()) return;
        if (type instanceof ParameterizedType pt && pt.getRawType() == List.class) {
            if (!element.isJsonArray()) return;
            Type child = pt.getActualTypeArguments()[0];
            for (int i = 0; i < element.getAsJsonArray().size(); i++) {
                inspect(element.getAsJsonArray().get(i), child, path + "[" + i + "]", issues);
            }
            return;
        }
        if (!(type instanceof Class<?> clazz) || isScalar(clazz) || !element.isJsonObject()) return;
        Map<String, Field> fields = fields(clazz);
        JsonObject object = element.getAsJsonObject();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            Field field = fields.get(entry.getKey());
            String childPath = path + "." + entry.getKey();
            if (field == null) {
                issues.add(new ValidationIssue(childPath, "неизвестное поле"));
            } else {
                inspect(entry.getValue(), field.getGenericType(), childPath, issues);
            }
        }
    }

    private static Map<String, Field> fields(Class<?> clazz) {
        Map<String, Field> result = new HashMap<>();
        for (Field field : clazz.getFields()) {
            if (!Modifier.isStatic(field.getModifiers())) result.put(field.getName(), field);
        }
        return result;
    }

    private static boolean isScalar(Class<?> clazz) {
        return clazz.isPrimitive() || Number.class.isAssignableFrom(clazz)
                || clazz == String.class || clazz == Boolean.class || clazz.isEnum();
    }
}
