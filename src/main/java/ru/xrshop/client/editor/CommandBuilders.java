package ru.xrshop.client.editor;

import java.util.regex.Pattern;

public final class CommandBuilders {
    private static final Pattern RESOURCE = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final Pattern TARGET = Pattern.compile("\\S+");

    private CommandBuilders() {}

    public static String give(String target, String item, String count, String nbt) {
        String safeTarget = target(target);
        String safeItem = resource(item, "предмет");
        int safeCount = integer(count, 1, 9999, "Количество");
        String suffix = nbt == null ? "" : nbt.trim();
        if (!suffix.isEmpty() && (!suffix.startsWith("{") || !suffix.endsWith("}")))
            throw new IllegalArgumentException("NBT должен начинаться с { и заканчиваться }");
        return "give " + safeTarget + " " + safeItem + suffix + " " + safeCount;
    }

    public static String effect(String target, String effect, String seconds, String level, boolean hideParticles) {
        String safeTarget = target(target);
        String safeEffect = resource(effect, "эффект");
        int safeSeconds = integer(seconds, 1, 1_000_000, "Длительность");
        int safeLevel = integer(level, 1, 256, "Уровень");
        String base = "effect give " + safeTarget + " " + safeEffect + " " + safeSeconds;
        if (safeLevel == 1 && !hideParticles) return base;
        return base + " " + (safeLevel - 1) + " " + hideParticles;
    }

    private static String target(String value) {
        String target = value == null ? "" : value.trim();
        if (!TARGET.matcher(target).matches()) throw new IllegalArgumentException("Цель должна быть одним аргументом, например ${player}");
        return target;
    }

    private static String resource(String value, String label) {
        String id = value == null ? "" : value.trim();
        if (!RESOURCE.matcher(id).matches()) throw new IllegalArgumentException("Некорректный ID: " + label);
        return id;
    }

    private static int integer(String value, int min, int max, String label) {
        try {
            int parsed = Integer.parseInt(value == null ? "" : value.trim());
            if (parsed < min || parsed > max) throw new IllegalArgumentException(label + ": допустимо " + min + ".." + max);
            return parsed;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(label + ": ожидается целое число");
        }
    }
}
