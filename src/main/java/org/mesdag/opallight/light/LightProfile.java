package org.mesdag.opallight.light;

import org.jetbrains.annotations.Nullable;

/// 光源定义在主线程捕获后不可变，供传播工作线程读取。
record LightProfile(OpalColor color, @Nullable LightDataLoader.CyclePattern cycle) {
    boolean animated() {
        return cycle != null;
    }
}
