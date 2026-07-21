package ru.xrshop.common.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import ru.xrshop.XRShopMod;

import java.util.UUID;
import java.util.function.Supplier;

public record EditorActionC2S(UUID sessionId, Action action, String categoryId, String slotId,
                              String field, String value, String secondary) {
    public enum Action {
        CREATE_CATEGORY, DELETE_CATEGORY, COPY_CATEGORY, MOVE_CATEGORY,
        CREATE_PRODUCT, DELETE_PRODUCT, COPY_PRODUCT, MOVE_PRODUCT,
        SET_CATEGORY_FIELD, SET_PRODUCT_FIELD, SET_UI_FIELD, SET_GLOBAL_STYLE_FIELD,
        ADD_COMMAND, SET_COMMAND, REMOVE_COMMAND, VALIDATE, SAVE, CANCEL,
        CHECK_COMMAND, SAVE_FIELDS, CHECK_FIELDS
    }

    public static void encode(EditorActionC2S message, FriendlyByteBuf buffer) {
        buffer.writeUUID(message.sessionId); buffer.writeEnum(message.action);
        buffer.writeUtf(message.categoryId, 128); buffer.writeUtf(message.slotId, 128);
        buffer.writeUtf(message.field, 128); buffer.writeUtf(message.value, 65_535); buffer.writeUtf(message.secondary, 128);
    }
    public static EditorActionC2S decode(FriendlyByteBuf buffer) {
        return new EditorActionC2S(buffer.readUUID(), buffer.readEnum(Action.class), buffer.readUtf(128),
                buffer.readUtf(128), buffer.readUtf(128), buffer.readUtf(65_535), buffer.readUtf(128));
    }
    public static void handle(EditorActionC2S message, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        ServerPlayer sender = context.getSender();
        context.enqueueWork(() -> {
            if (sender != null) XRShopMod.runtime().ifPresent(runtime -> runtime.editor().handle(sender, message));
        });
        context.setPacketHandled(true);
    }
}
