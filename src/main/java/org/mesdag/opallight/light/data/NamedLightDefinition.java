package org.mesdag.opallight.light.data;

/// 命名光源的光照配置和亮度；位置与启停由采集器提供。
public record NamedLightDefinition(LightProfile profile, int lightLevel) {
    public NamedLightDefinition {
        if (profile == null) throw new IllegalArgumentException("Light profile is required");
        if (lightLevel < 0 || lightLevel > 15)
            throw new IllegalArgumentException("Light level must be between 0 and 15");
    }
}
