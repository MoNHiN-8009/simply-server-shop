package ru.xrshop.common.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

public final class JsonCodec {
    public static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .setPrettyPrinting()
            .create();

    private JsonCodec() {}

    public static <T> T copy(T value, Class<T> type) {
        return GSON.fromJson(GSON.toJsonTree(value), type);
    }

    public static JsonElement parse(String text) {
        return JsonParser.parseString(text);
    }
}
