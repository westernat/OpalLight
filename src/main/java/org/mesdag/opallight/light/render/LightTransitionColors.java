package org.mesdag.opallight.light.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.vertex.VertexBuffer;
import org.jetbrains.annotations.Nullable;

import static org.lwjgl.opengl.GL31.*;

final class LightTransitionColors implements AutoCloseable {
    private final int texture;

    private LightTransitionColors(int texture) {
        this.texture = texture;
    }

    int texture() {
        return texture;
    }

    static @Nullable LightTransitionColors create(VertexBuffer previous, int vertexCount) {
        int vao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
        try {
            previous.bind();
            return create(glGetVertexAttribi(0, GL_VERTEX_ATTRIB_ARRAY_BUFFER_BINDING), vertexCount);
        } finally {
            GlStateManager._glBindVertexArray(vao);
        }
    }

    static @Nullable LightTransitionColors create(int vertexBuffer, int vertexCount) {
        if (vertexBuffer == 0 || (long) vertexCount * 6 > glGetInteger(GL_MAX_TEXTURE_BUFFER_SIZE)) return null;
        int bound = glGetInteger(GL_TEXTURE_BINDING_BUFFER);
        int texture = glGenTextures();
        try {
            glBindTexture(GL_TEXTURE_BUFFER, texture);
            // 直接读取旧 VBO 的颜色字节，无须复制或重新上传旧顶点。
            glTexBuffer(GL_TEXTURE_BUFFER, GL_RGBA8, vertexBuffer);
            return new LightTransitionColors(texture);
        } catch (RuntimeException error) {
            glDeleteTextures(texture);
            throw error;
        } finally {
            glBindTexture(GL_TEXTURE_BUFFER, bound);
        }
    }

    @Override
    public void close() {
        glDeleteTextures(texture);
    }
}
