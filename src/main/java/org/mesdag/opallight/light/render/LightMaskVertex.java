package org.mesdag.opallight.light.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;

import java.nio.ByteBuffer;

// 顶点布局：位置 12 字节、纹理 8 字节、颜色 4 字节。
final class LightMaskVertex {
    static final VertexFormat FORMAT = DefaultVertexFormat.POSITION_TEX_COLOR;

    private LightMaskVertex() {
    }

    static void write(ByteBuffer output, float x, float y, float z, float u, float v,
                      float modelAlpha,
                      float red, float green, float blue) {
        output.putFloat(x).putFloat(y).putFloat(z).putFloat(u).putFloat(v);
        output.put((byte) Math.round(red * 255)).put((byte) Math.round(green * 255)).put((byte) Math.round(blue * 255));
        output.put((byte) Math.round(modelAlpha * 255));
    }
}
