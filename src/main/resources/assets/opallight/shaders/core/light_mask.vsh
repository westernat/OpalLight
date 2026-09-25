#version 150

#moj_import <fog.glsl>

in vec3 Position;
in vec2 UV0;
in vec4 Color;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform vec3 GroupOffset;
uniform int FogShape;

out vec4 vertexColor;
out vec2 texCoord0;
out float vertexDistance;

void main() {
    vec3 pos = Position + GroupOffset;
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);
    vertexDistance = fog_distance(ModelViewMat, pos, FogShape);
    vertexColor = Color;
    texCoord0 = UV0;
}
