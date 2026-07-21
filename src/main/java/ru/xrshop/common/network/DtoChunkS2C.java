package ru.xrshop.common.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import ru.xrshop.client.ClientState;

import java.util.UUID;
import java.util.function.Supplier;

public record DtoChunkS2C(Kind kind, UUID sessionId, int index, int count, int totalBytes, byte[] payload) {
    public static final int ABSOLUTE_PAYLOAD_MAX = 30 * 1024;
    public static final int ABSOLUTE_TRANSFER_MAX = 32 * 1024 * 1024;
    public static final int ABSOLUTE_CHUNK_MAX = 1024;

    public enum Kind { STORE, EDITOR }

    public static void encode(DtoChunkS2C message, FriendlyByteBuf buffer) {
        buffer.writeEnum(message.kind); buffer.writeUUID(message.sessionId);
        buffer.writeVarInt(message.index); buffer.writeVarInt(message.count); buffer.writeVarInt(message.totalBytes);
        buffer.writeVarInt(message.payload.length); buffer.writeBytes(message.payload);
    }

    public static DtoChunkS2C decode(FriendlyByteBuf buffer) {
        Kind kind = buffer.readEnum(Kind.class); UUID session = buffer.readUUID();
        int index = buffer.readVarInt(), count = buffer.readVarInt(), total = buffer.readVarInt(), length = buffer.readVarInt();
        if (count < 1 || count > ABSOLUTE_CHUNK_MAX || index < 0 || index >= count
                || total < 0 || total > ABSOLUTE_TRANSFER_MAX || length < 0 || length > ABSOLUTE_PAYLOAD_MAX
                || length > buffer.readableBytes()) throw new IllegalArgumentException("Некорректная часть DTO");
        byte[] bytes = new byte[length]; buffer.readBytes(bytes);
        return new DtoChunkS2C(kind, session, index, count, total, bytes);
    }

    public static void handle(DtoChunkS2C message, Supplier<NetworkEvent.Context> context) {
        context.get().setPacketHandled(true);
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientState.acceptChunk(message));
    }
}
