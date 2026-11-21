package com.termpilot.prompt;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;

/** 本轮运行环境与检索结果。单独保存，发送给模型时才放到用户消息前面。 */
public record TurnReminder(String date, String zone, String memory) {
    public static final String RUNTIME_DATE_PROPERTY = "termpilot.prompt.runtime.date";
    public static final String RUNTIME_ZONE_PROPERTY = "termpilot.prompt.runtime.zone";

    public TurnReminder {
        LocalDate.parse(date);
        ZoneId.of(zone);
        memory = memory == null ? "" : memory;
    }

    public static TurnReminder current(String memory) {
        ZoneId zone = configuredRuntimeZone();
        return new TurnReminder(configuredRuntimeDate(zone).toString(), zone.getId(), memory);
    }

    public String prefix() {
        String context = memory.isBlank() ? "" : "\n" + memory.replace("&", "&amp;")
                .replace("<", "&lt;").replace(">", "&gt;");
        return "<termpilot-turn-context>\n当前日期: " + date + "\n当前时区: " + zone
                + context + "\n</termpilot-turn-context>\n\n";
    }

    private static ZoneId configuredRuntimeZone() {
        String configured = System.getProperty(RUNTIME_ZONE_PROPERTY);
        if (configured == null || configured.isBlank()) {
            return ZoneId.systemDefault();
        }
        try {
            return ZoneId.of(configured.trim());
        } catch (DateTimeException error) {
            throw new IllegalArgumentException(
                    "invalid " + RUNTIME_ZONE_PROPERTY + ": " + configured, error);
        }
    }

    private static LocalDate configuredRuntimeDate(ZoneId zone) {
        String configured = System.getProperty(RUNTIME_DATE_PROPERTY);
        if (configured == null || configured.isBlank()) {
            return LocalDate.now(zone);
        }
        try {
            return LocalDate.parse(configured.trim());
        } catch (DateTimeException error) {
            throw new IllegalArgumentException(
                    "invalid " + RUNTIME_DATE_PROPERTY + ": " + configured, error);
        }
    }
}
