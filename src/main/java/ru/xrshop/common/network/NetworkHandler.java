package ru.xrshop.common.network;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import ru.xrshop.XRShopMod;
import ru.xrshop.common.config.JsonCodec;
import ru.xrshop.common.dto.EditorDto;
import ru.xrshop.common.dto.StoreViewDto;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class NetworkHandler {
    public static final String PROTOCOL = "3";
    private static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
            .named(ResourceLocation.fromNamespaceAndPath(XRShopMod.MOD_ID, "main"))
            .networkProtocolVersion(() -> PROTOCOL)
            .clientAcceptedVersions(PROTOCOL::equals)
            .serverAcceptedVersions(PROTOCOL::equals)
            .simpleChannel();
    private static int discriminator;

    private NetworkHandler() {}

    public static void register() {
        CHANNEL.messageBuilder(DtoChunkS2C.class, discriminator++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(DtoChunkS2C::encode).decoder(DtoChunkS2C::decode).consumerMainThread(DtoChunkS2C::handle).add();
        CHANNEL.messageBuilder(ServerEventS2C.class, discriminator++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(ServerEventS2C::encode).decoder(ServerEventS2C::decode).consumerMainThread(ServerEventS2C::handle).add();
        CHANNEL.messageBuilder(EditorActionC2S.class, discriminator++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(EditorActionC2S::encode).decoder(EditorActionC2S::decode).consumerMainThread(EditorActionC2S::handle).add();
        CHANNEL.messageBuilder(CloseSessionC2S.class, discriminator++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(CloseSessionC2S::encode).decoder(CloseSessionC2S::decode).consumerMainThread(CloseSessionC2S::handle).add();
    }

    public static void sendStore(ServerPlayer player, StoreViewDto dto, int maxPayload, int maxTransfer, int maxChunks) {
        sendChunks(player, DtoChunkS2C.Kind.STORE, dto.sessionId(), JsonCodec.GSON.toJson(dto), maxPayload, maxTransfer, maxChunks);
    }

    public static void sendEditor(ServerPlayer player, EditorDto dto, int maxPayload, int maxTransfer, int maxChunks) {
        sendChunks(player, DtoChunkS2C.Kind.EDITOR, dto.editorSessionId(), JsonCodec.GSON.toJson(dto), maxPayload, maxTransfer, maxChunks);
    }

    private static void sendChunks(ServerPlayer player, DtoChunkS2C.Kind kind, UUID session, String json,
                                   int maxPayload, int maxTransfer, int maxChunks) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxTransfer) throw new IllegalArgumentException("DTO превышает max_transfer_bytes");
        int payload = Math.max(1024, Math.min(DtoChunkS2C.ABSOLUTE_PAYLOAD_MAX, maxPayload));
        int count = Math.max(1, (bytes.length + payload - 1) / payload);
        if (count > maxChunks) throw new IllegalArgumentException("DTO требует слишком много частей: " + count);
        for (int i = 0; i < count; i++) {
            int from = i * payload, length = Math.min(payload, bytes.length - from);
            byte[] part = new byte[length];
            System.arraycopy(bytes, from, part, 0, length);
            send(player, new DtoChunkS2C(kind, session, i, count, bytes.length, part));
        }
    }

    public static void send(ServerPlayer player, Object message) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
    }

    public static void sendToServer(Object message) { CHANNEL.sendToServer(message); }
}
