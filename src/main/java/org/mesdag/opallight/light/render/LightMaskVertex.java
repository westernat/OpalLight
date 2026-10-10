package org.mesdag.opallight.light.render;

import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;

import java.nio.ByteBuffer;

// 顶点布局：位置 12 字节、纹理 8 字节、颜色 4 字节。
final class LightMaskVertex {
    static final VertexFormat FORMAT = VertexFormat.builder()
            .add("Position", VertexFormatElement.POSITION)
            .add("UV0", VertexFormatElement.UV0)
            .add("Color", VertexFormatElement.COLOR)
            .build();

    private LightMaskVertex() {}

    static void write(ByteBuffer output, float x, float y, float z, float u, float v,
                      float modelAlpha,
                      float red, float green, float blue) {
        output.putFloat(x).putFloat(y).putFloat(z).putFloat(u).putFloat(v);
        output.put((byte) Math.round(red * 255)).put((byte) Math.round(green * 255)).put((byte) Math.round(blue * 255));
        output.put((byte) Math.round(modelAlpha * 255));
    }
}
