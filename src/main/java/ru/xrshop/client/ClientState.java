package ru.xrshop.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import ru.xrshop.common.config.JsonCodec;
import ru.xrshop.common.dto.EditorDto;
import ru.xrshop.common.dto.EditorMode;
import ru.xrshop.common.dto.StoreViewDto;
import ru.xrshop.common.network.CloseSessionC2S;
import ru.xrshop.common.network.DtoChunkS2C;
import ru.xrshop.common.network.NetworkHandler;
import ru.xrshop.common.network.ServerEventS2C;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class ClientState {
    private static final Map<Key, Assembly> TRANSFERS = new LinkedHashMap<>();
    private static StoreViewDto store;
    private static EditorDto editor;

    private ClientState() {}

    public static synchronized void acceptChunk(DtoChunkS2C chunk) {
        pruneTransfers();
        Key key = new Key(chunk.kind(), chunk.sessionId());
        Assembly assembly = TRANSFERS.computeIfAbsent(key, ignored -> new Assembly(chunk.count(), chunk.totalBytes()));
        if (!assembly.compatible(chunk) || !assembly.add(chunk.index(), chunk.payload())) { TRANSFERS.remove(key); networkError(); return; }
        if (!assembly.complete()) return;
        TRANSFERS.remove(key);
        byte[] bytes = assembly.join();
        if (bytes == null) { networkError(); return; }
        String json = new String(bytes, StandardCharsets.UTF_8);
        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (chunk.kind() == DtoChunkS2C.Kind.STORE) {
                StoreViewDto dto = JsonCodec.GSON.fromJson(json, StoreViewDto.class);
                clearStoreMemory(); installStore(dto);
                if (minecraft.screen instanceof StoreScreen screen && screen.sessionId().equals(dto.sessionId())) screen.update(dto);
                else minecraft.setScreen(new StoreScreen(dto));
            } else {
                EditorDto dto = JsonCodec.GSON.fromJson(json, EditorDto.class);
                clearEditorMemory(); installEditor(dto);
                if (minecraft.screen instanceof EditorSessionScreen screen && screen.sessionId().equals(dto.editorSessionId())) screen.update(dto);
                else minecraft.setScreen(dto.mode() == EditorMode.EXTENDED ? new AdvancedEditorScreen(dto) : new EditorScreen(dto));
            }
        } catch (RuntimeException ex) { networkError(); }
    }

    public static synchronized void acceptEvent(ServerEventS2C event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (event.type() == ServerEventS2C.Type.STORE_UPDATED) {
            if (store != null && (event.sessionId() == null || event.sessionId().equals(store.sessionId()))) {
                clearStoreMemory();
                minecraft.setScreen(null);
                if (minecraft.getConnection() != null) minecraft.getConnection().sendCommand("ms open");
            }
            return;
        }
        if (event.type() == ServerEventS2C.Type.BALANCE && store != null && event.balance() >= 0) {
            store = new StoreViewDto(store.sessionId(), store.revision(), store.ui(), store.style(), store.categories(), event.balance());
        }
        if (minecraft.screen instanceof StoreScreen screen) screen.serverEvent(event);
        if (minecraft.screen instanceof EditorSessionScreen screen) screen.serverEvent(event);
    }

    public static synchronized void closeStore(UUID session) {
        if (store != null && store.sessionId().equals(session)) clearStoreMemory();
        NetworkHandler.sendToServer(new CloseSessionC2S(CloseSessionC2S.Kind.STORE, session));
    }
    public static synchronized void closeEditor(UUID session) {
        if (editor != null && editor.editorSessionId().equals(session)) clearEditorMemory();
        NetworkHandler.sendToServer(new CloseSessionC2S(CloseSessionC2S.Kind.EDITOR, session));
    }
    public static synchronized void disconnect() {
        store = null; editor = null; TRANSFERS.clear();
    }
    public static synchronized boolean hasStore() { return store != null; }
    public static synchronized boolean hasEditor() { return editor != null; }
    static void installStore(StoreViewDto value) { store = value; }
    static void installEditor(EditorDto value) { editor = value; }
    static void clearStoreMemory() { store = null; }
    static void clearEditorMemory() { editor = null; }
    private static void networkError() {
        Minecraft minecraft = Minecraft.getInstance(); disconnect(); minecraft.setScreen(null);
        if (minecraft.player != null) minecraft.player.displayClientMessage(Component.translatable("xrshop.message.network_error"), false);
    }
    private static void pruneTransfers() {
        long cutoff = System.currentTimeMillis() - 30_000;
        TRANSFERS.entrySet().removeIf(e -> e.getValue().created < cutoff);
        while (TRANSFERS.size() >= 4) TRANSFERS.remove(TRANSFERS.keySet().iterator().next());
    }
    private record Key(DtoChunkS2C.Kind kind, UUID session) {}
    private static final class Assembly {
        final byte[][] parts; final int total; final long created = System.currentTimeMillis(); int received; int bytes;
        Assembly(int count, int total) { this.parts = new byte[count][]; this.total = total; }
        boolean compatible(DtoChunkS2C chunk) { return chunk.count() == parts.length && chunk.totalBytes() == total; }
        boolean add(int index, byte[] value) {
            if (parts[index] != null) return Arrays.equals(parts[index], value);
            if ((long) bytes + value.length > total) return false;
            parts[index] = value.clone(); received++; bytes += value.length; return true;
        }
        boolean complete() { return received == parts.length && bytes == total; }
        byte[] join() {
            if (!complete()) return null; ByteArrayOutputStream out = new ByteArrayOutputStream(total);
            for (byte[] part : parts) out.writeBytes(part); return out.toByteArray();
        }
    }
}
