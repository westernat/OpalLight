package org.mesdag.opallight;

import net.minecraftforge.common.ForgeConfigSpec;

public final class OpalLightConfig {
    public static final ForgeConfigSpec SPEC;
    private static final ForgeConfigSpec.BooleanValue ENABLED;

    static {
        var builder = new ForgeConfigSpec.Builder();
        ENABLED = builder.translation("opallight.configuration.enabled").define("enabled", true);
        SPEC = builder.build();
    }

    private OpalLightConfig() {
    }

    public static boolean isEnabled() {
        return !SPEC.isLoaded() || ENABLED.get();
    }
}
