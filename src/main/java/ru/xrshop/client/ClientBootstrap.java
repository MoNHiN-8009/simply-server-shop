package ru.xrshop.client;

import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;

public final class ClientBootstrap {
    private ClientBootstrap() {}
    public static void register() { MinecraftForge.EVENT_BUS.register(new ClientBootstrap()); }
    @SubscribeEvent public void logout(ClientPlayerNetworkEvent.LoggingOut event) { ClientState.disconnect(); }
}
