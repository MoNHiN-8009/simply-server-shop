package ru.xrshop.client.editor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CommandBuildersTest {
    @Test void buildsGiveWithDefaultPlayerAndOptionalNbt() {
        assertEquals("give ${player} minecraft:diamond 16", CommandBuilders.give("${player}", "minecraft:diamond", "16", ""));
        assertEquals("give @p minecraft:diamond{CustomModelData:1} 1",
                CommandBuilders.give("@p", "minecraft:diamond", "1", "{CustomModelData:1}"));
    }

    @Test void buildsFriendlyEffectLevels() {
        assertEquals("effect give ${player} minecraft:absorption 30",
                CommandBuilders.effect("${player}", "minecraft:absorption", "30", "1", false));
        assertEquals("effect give ${player} minecraft:speed 60 1 true",
                CommandBuilders.effect("${player}", "minecraft:speed", "60", "2", true));
    }

    @Test void rejectsInvalidBuilderInputBeforeNetworkRequest() {
        assertThrows(IllegalArgumentException.class, () -> CommandBuilders.give("${player}", "Diamond", "1", ""));
        assertThrows(IllegalArgumentException.class, () -> CommandBuilders.effect("${player}", "minecraft:speed", "0", "1", false));
    }
}
