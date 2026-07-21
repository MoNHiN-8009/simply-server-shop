package ru.xrshop.common.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import ru.xrshop.client.ClientState;
import ru.xrshop.common.dto.PurchaseResultCode;

import java.util.UUID;
import java.util.function.Supplier;

public record ServerEventS2C(Type type, UUID sessionId, long revision, long balance,
                             PurchaseResultCode code, String message) {
    public enum Type { BALANCE, PURCHASE_RESULT, STORE_UPDATED, EDITOR_RESULT }

    public static void encode(ServerEventS2C message, FriendlyByteBuf buffer) {
        buffer.writeEnum(message.type); buffer.writeUUID(message.sessionId == null ? new UUID(0, 0) : message.sessionId);
        buffer.writeLong(message.revision); buffer.writeLong(message.balance); buffer.writeEnum(message.code);
        buffer.writeUtf(message.message, 2048);
    }
    public static ServerEventS2C decode(FriendlyByteBuf buffer) {
        Type type = buffer.readEnum(Type.class); UUID session = buffer.readUUID();
        if (session.getMostSignificantBits() == 0 && session.getLeastSignificantBits() == 0) session = null;
        return new ServerEventS2C(type, session, buffer.readLong(), buffer.readLong(),
                buffer.readEnum(PurchaseResultCode.class), buffer.readUtf(2048));
    }
    public static void handle(ServerEventS2C message, Supplier<NetworkEvent.Context> context) {
        context.get().setPacketHandled(true);
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientState.acceptEvent(message));
    }
}
