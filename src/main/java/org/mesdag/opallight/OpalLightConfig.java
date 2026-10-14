package org.mesdag.opallight;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class OpalLightConfig {
    public static final ModConfigSpec SPEC;
    private static final ModConfigSpec.BooleanValue ENABLED;

    static {
        var builder = new ModConfigSpec.Builder();
        ENABLED = builder.define("enabled", true);
        SPEC = builder.build();
    }

    private OpalLightConfig() {}

    public static boolean isEnabled() {
        return !SPEC.isLoaded() || ENABLED.get();
    }
}
