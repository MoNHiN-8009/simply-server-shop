package ru.xrshop.client;

import ru.xrshop.common.dto.EditorDto;
import ru.xrshop.common.network.ServerEventS2C;

import java.util.UUID;

/** Small bridge shared by the standard and extended editor UIs. */
public interface EditorSessionScreen {
    UUID sessionId();
    void update(EditorDto updated);
    void serverEvent(ServerEventS2C event);
}
