package ru.xrshop.common.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import ru.xrshop.XRShopMod;

import java.util.UUID;
import java.util.function.Supplier;

public record CloseSessionC2S(Kind kind, UUID sessionId) {
    public enum Kind { STORE, EDITOR }
    public static void encode(CloseSessionC2S message, FriendlyByteBuf buffer) { buffer.writeEnum(message.kind); buffer.writeUUID(message.sessionId); }
    public static CloseSessionC2S decode(FriendlyByteBuf buffer) { return new CloseSessionC2S(buffer.readEnum(Kind.class), buffer.readUUID()); }
    public static void handle(CloseSessionC2S message, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get(); ServerPlayer sender = context.getSender();
        context.enqueueWork(() -> {
            if (sender == null) return;
            XRShopMod.runtime().ifPresent(runtime -> {
                if (message.kind == Kind.STORE) runtime.sessions().close(sender.getUUID(), message.sessionId);
                else runtime.editor().close(sender, message.sessionId);
            });
        });
        context.setPacketHandled(true);
    }
}
