package org.adde0109.ambassador;

import com.electronwill.nightconfig.core.conversion.InvalidValueException;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.google.gson.annotations.Expose;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class AmbassadorConfig {

    @Expose
    private int serverSwitchCancellationTime = 30;

    @Expose
    private boolean silenceWarnings = false;
    @Expose
    private boolean bypassRegistryCheck = false;
    @Expose
    private boolean bypassModCheck = false;
    @Expose
    private List<String> ignoredServerMods = List.of("mohist", "ftbsync", "antiportals");

    @Expose
    private boolean debugMode = false;

    @Expose
    private boolean enableKickReset = false;

    @Expose
    private String kickReconnectMessageString = "<red>Please reconnect.</red>";

    private AmbassadorConfig(boolean silenceWarnings, boolean bypassRegistryCheck, boolean bypassModCheck,
                             List<String> ignoredServerMods, boolean debugMode, boolean enableKickReset,
                             String kickReconnectMessageString) {
        this.silenceWarnings = silenceWarnings;
        this.bypassRegistryCheck = bypassRegistryCheck;
        this.bypassModCheck = bypassModCheck;
        this.ignoredServerMods = ignoredServerMods;
        this.debugMode = debugMode;
        this.enableKickReset = enableKickReset;
        this.kickReconnectMessageString = kickReconnectMessageString;
    }

    public static AmbassadorConfig read(Path path) throws IOException {
        URL defaultConfigLocation = AmbassadorConfig.class.getClassLoader()
                .getResource("default-ambassador.toml");
        if (defaultConfigLocation == null) {
            throw new RuntimeException("Default configuration file does not exist.");
        }

        CommentedFileConfig config = CommentedFileConfig.builder(path)
                .defaultData(defaultConfigLocation)
                .autosave()
                .preserveInsertionOrder()
                .sync()
                .build();
        config.load();

        double configVersion;
        try {
            configVersion = Double.parseDouble(config.getOrElse("config-version", "1.0"));
        } catch (NumberFormatException e) {
            configVersion = 1.0;
        }

        boolean silenceWarnings = config.getOrElse("silence-warnings", false);

        int serverSwitchCancellationTime = config.getOrElse("serverRedirectTimeout", 30);

        boolean bypassRegistryCheck = config.getOrElse("bypass-registry-checks", false);

        boolean bypassModCheck = config.getOrElse("bypass-mod-checks", false);

        List<String> ignoredServerMods = readStringList(config.get("ignored-server-mods"),
                List.of("mohist", "ftbsync", "antiportals"));
        if (!config.contains("ignored-server-mods")) {
            config.set("ignored-server-mods", ignoredServerMods);
        }

        boolean debugMode = config.getOrElse("debug-mode", false);

        String kickReconnectMessageString = config.getOrElse("disconnect-reset-message",
                config.getOrElse("reconnect-message", "<red>Please reconnect.</red>"));

        //Upgrade config
        if (configVersion <= 2.0) {
            Files.delete(path);
            config = CommentedFileConfig.builder(path)
                    .defaultData(defaultConfigLocation)
                    .autosave()
                    .preserveInsertionOrder()
                    .sync()
                    .build();
            config.load();
            config.set("silence-warnings", silenceWarnings);
            config.set("serverRedirectTimeout", serverSwitchCancellationTime);
            config.set("bypass-registry-checks", bypassRegistryCheck);
            config.set("bypass-mod-checks", bypassModCheck);
            config.set("ignored-server-mods", ignoredServerMods);
            config.set("debug-mode", debugMode);
            config.set("reconnect-message", kickReconnectMessageString);
        }

        boolean enableKickReset = config.getOrElse("enable-kick-reset", false);

        return new AmbassadorConfig(silenceWarnings, bypassRegistryCheck, bypassModCheck, ignoredServerMods,
                debugMode, enableKickReset, kickReconnectMessageString);
    }

    private static List<String> readStringList(Object value, List<String> defaultValue) {
        Object source = value == null ? defaultValue : value;
        if (!(source instanceof List<?> values)) {
            return defaultValue;
        }

        List<String> result = new ArrayList<>();
        for (Object entry : values) {
            if (entry == null) {
                continue;
            }
            String normalized = entry.toString().trim().toLowerCase(Locale.ROOT);
            if (!normalized.isEmpty() && !result.contains(normalized)) {
                result.add(normalized);
            }
        }
        return result;
    }

    public int getServerSwitchCancellationTime() {
        return serverSwitchCancellationTime;
    }

    public boolean isSilenceWarnings() {
        return silenceWarnings;
    }

    public boolean isBypassRegistryCheck() {
        return bypassRegistryCheck;
    }

    public boolean isBypassModCheck() {
        return bypassModCheck;
    }

    public List<String> getIgnoredServerMods() {
        return ignoredServerMods;
    }

    public boolean isDebugMode() {
        return debugMode;
    }

    public boolean isEnableKickReset() {
        return enableKickReset;
    }

    public String getKickReconnectMessageString() {
        return kickReconnectMessageString;
    }
}
