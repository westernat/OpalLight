package org.mesdag.opallight.light;

/// 按原版 RGB 系数累加到达强度，仅输出数据；地形显示转换由 GLSL 负责。
final class LightColorMixer {
    private LightColorMixer() {}

    /// 色相与亮度分开：单灯的颜色、衰减和中心白色保持不变。
    static double contribution(float channel, float sourcePeak, float white, float strength) {
        if (sourcePeak <= 0 || strength <= 0) return 0;
        double hue = channel / (double) sourcePeak;
        hue += (1 - hue) * white;
        return hue * strength;
    }
}
