package ru.xrshop;

import com.mojang.logging.LogUtils;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.server.permission.events.PermissionGatherEvent;
import org.slf4j.Logger;
import ru.xrshop.client.ClientBootstrap;
import ru.xrshop.common.network.NetworkHandler;
import ru.xrshop.server.ServerRuntime;
import ru.xrshop.server.command.ModCommands;
import ru.xrshop.server.security.ModPermissions;

import java.nio.file.Path;
import java.util.Optional;

@Mod(XRShopMod.MOD_ID)
public final class XRShopMod {
    public static final String MOD_ID = "xrshop";
    public static final Logger LOGGER = LogUtils.getLogger();
    private static volatile ServerRuntime runtime;
    private int tickCounter;

    public XRShopMod() {
        NetworkHandler.register();
        MinecraftForge.EVENT_BUS.register(this);
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> ClientBootstrap::register);
    }

    public static Optional<ServerRuntime> runtime() { return Optional.ofNullable(runtime); }

    @SubscribeEvent public void permissions(PermissionGatherEvent.Nodes event) { ModPermissions.register(event); }
    @SubscribeEvent public void commands(RegisterCommandsEvent event) { ModCommands.register(event.getDispatcher()); }

    @SubscribeEvent public void serverStarting(ServerStartingEvent event) {
        try {
            Path config = FMLPaths.CONFIGDIR.get().resolve("xrshop");
            Path database = FMLPaths.GAMEDIR.get().resolve("data").resolve("xrshop").resolve("xrshop.db");
            runtime = ServerRuntime.start(event.getServer(), config, database, LOGGER);
            LOGGER.info("XR Shop запущен, revision={}", runtime.config().snapshot().revision());
        } catch (Exception ex) {
            LOGGER.error("XR Shop не может запуститься", ex);
            throw new IllegalStateException("Ошибка запуска XR Shop", ex);
        }
    }

    @SubscribeEvent public void serverStopping(ServerStoppingEvent event) {
        ServerRuntime value = runtime; runtime = null;
        if (value != null) value.close();
    }

    @SubscribeEvent public void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        ServerRuntime value = runtime;
        if (value != null) value.removePlayer(event.getEntity().getUUID());
    }

    @SubscribeEvent public void serverTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END && ++tickCounter >= 200) {
            tickCounter = 0; ServerRuntime value = runtime; if (value != null) value.prune();
        }
    }
}
