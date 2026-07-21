package ru.xrshop.client.editor;

import java.util.List;

public record EditorFieldSpec(String group, String path, String label, Kind kind, String help,
                              List<String> options) {
    public EditorFieldSpec(String group, String path, String label, Kind kind, String help) {
        this(group, path, label, kind, help, List.of());
    }

    public enum Kind {
        TEXT, TEXT_LIST, BOOLEAN, TRI_STATE, INTEGER, LONG, DECIMAL,
        COLOR, RESOURCE, ITEM, NBT, ENUM, SOUND, PATH, TEXTURE
    }
}
