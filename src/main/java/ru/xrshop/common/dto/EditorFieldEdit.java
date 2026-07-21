package ru.xrshop.common.dto;

/** One leaf-field update submitted by the friendly editor as part of an atomic save. */
public record EditorFieldEdit(Target target, String categoryId, String slotId, String path, String jsonValue) {
    public enum Target { CATEGORY, PRODUCT, UI, GLOBAL_STYLE }
}
