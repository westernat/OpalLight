package org.mesdag.opallight.light.data;

import org.jetbrains.annotations.Nullable;
import org.mesdag.opallight.light.color.OpalColor;

// 光源定义在主线程捕获后不可变，供传播工作线程读取。
public record LightProfile(OpalColor color, @Nullable LightDataLoader.CyclePattern cycle) {
    public boolean animated() {
        return cycle != null;
    }
}
