package ru.xrshop.server.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import ru.xrshop.TestFixtures;
import ru.xrshop.common.config.JsonCodec;
import ru.xrshop.common.validation.ShopValidator;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ConfigManagerTest {
    @TempDir Path temp;

    @Test void firstStartCreatesEmptyWorkingStore() throws Exception {
        try (ConfigManager manager = manager()) {
            manager.initialize(); assertTrue(manager.snapshot().orderedCategories().isEmpty());
            assertTrue(Files.readString(temp.resolve("shop.json")).contains("\"categories\": []"));
        }
    }

    @Test void corruptedReloadKeepsLastKnownGoodSnapshot() throws Exception {
        try (ConfigManager manager = manager()) {
            manager.initialize(); long revision = manager.snapshot().revision();
            Files.writeString(temp.resolve("shop.json"), "{broken", StandardCharsets.UTF_8);
            assertFalse(manager.reloadAsync().get().success()); assertEquals(revision, manager.snapshot().revision());
        }
    }

    @Test void editorSaveIncrementsRevisionCreatesBackupAndDetectsConflict() throws Exception {
        try (ConfigManager manager = manager()) {
            manager.initialize(); long base = manager.snapshot().revision();
            var saved = manager.saveAsync(TestFixtures.shop(), base).get();
            assertTrue(saved.success()); assertEquals(base + 1, saved.revision());
            try (var backups = Files.list(temp.resolve("backups"))) { assertEquals(1, backups.count()); }
            assertTrue(manager.saveAsync(TestFixtures.shop(), base).get().conflict());
        }
    }

    @Test void startupRestoresNewestValidBackupInMemory() throws Exception {
        try (ConfigManager first = manager()) {
            first.initialize(); first.saveAsync(TestFixtures.shop(), 1).get(); first.backupAsync().get();
        }
        Files.writeString(temp.resolve("shop.json"), "{broken", StandardCharsets.UTF_8);
        try (ConfigManager recovered = manager()) {
            recovered.initialize(); assertFalse(recovered.snapshot().orderedCategories().isEmpty());
        }
    }

    private ConfigManager manager() { return new ConfigManager(temp, LoggerFactory.getLogger("ConfigManagerTest"), TestFixtures.runtimeChecks()); }
}
