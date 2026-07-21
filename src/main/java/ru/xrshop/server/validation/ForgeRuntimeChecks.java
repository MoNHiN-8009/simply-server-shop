package ru.xrshop.server.validation;

import com.mojang.brigadier.ParseResults;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.registries.ForgeRegistries;
import ru.xrshop.common.validation.ShopValidator;

public final class ForgeRuntimeChecks implements ShopValidator.RuntimeChecks {
    private final MinecraftServer server;

    public ForgeRuntimeChecks(MinecraftServer server) { this.server = server; }

    @Override public boolean itemExists(String id) {
        ResourceLocation location = ResourceLocation.tryParse(id);
        return location != null && ForgeRegistries.ITEMS.containsKey(location);
    }

    @Override public String validateDisplayNbt(String nbt) {
        try { TagParser.parseTag(nbt); return null; }
        catch (Exception ex) { return "NBT не разбирается: " + ex.getMessage(); }
    }

    @Override public String validateCommand(String command) {
        try {
            CommandSourceStack source = server.createCommandSourceStack().withPermission(4).withSuppressedOutput();
            ParseResults<CommandSourceStack> parsed = server.getCommands().getDispatcher().parse(command, source);
            if (parsed.getReader().canRead() || parsed.getContext().getCommand() == null)
                return "команда не разбирается CommandDispatcher в позиции " + parsed.getReader().getCursor();
            return null;
        } catch (RuntimeException ex) { return "ошибка разбора команды: " + ex.getMessage(); }
    }
}
