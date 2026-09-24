package org.mesdag.opallight.light;

/// 传播器和光源采集器共用的不可变彩光定义。
record LightSource(long pos, OpalColor color, int emission) {}
