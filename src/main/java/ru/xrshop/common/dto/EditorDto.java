package ru.xrshop.common.dto;

import ru.xrshop.common.config.ShopConfig;

import java.util.UUID;

/** Administrative DTO; it is kept only for the lifetime of an authorized editor screen. */
public record EditorDto(UUID editorSessionId, long baseRevision, ShopConfig draft, EditorMode mode) {
    public EditorDto {
        if (mode == null) mode = EditorMode.STANDARD;
    }

    public EditorDto(UUID editorSessionId, long baseRevision, ShopConfig draft) {
        this(editorSessionId, baseRevision, draft, EditorMode.STANDARD);
    }
}
