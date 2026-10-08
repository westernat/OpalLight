#version 150

#moj_import <fog.glsl>
#moj_import <opallight:light.glsl>

uniform sampler2D Sampler0;

uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;

uniform float TransitionWeight;
uniform float SkyBrightness;
uniform float AmbientLight;
// mask intensity, tint intensity, minimum daylight tint visibility, edge fade strength
uniform vec4 LightSettings;
uniform int TintPass;

in float vertexDistance;
in vec2 texCoord0;
in vec4 modelColor;
in vec3 lightColor;
in vec2 lightUv;

out vec4 fragColor;

void main() {
    float fogFade = mix(1.0, linear_fog_fade(vertexDistance, FogStart, FogEnd), FogColor.a);
    if (fogFade <= 0.0) discard;
    vec4 texColor = texture(Sampler0, texCoord0);
    if (texColor.a < 0.1) discard;
    vec3 contributions = max(lightColor, vec3(0.0));
    float colorStrength = opal_color_strength(contributions);
    vec3 hue = colorStrength > 0.0 ? contributions / colorStrength : vec3(0.0);
    float strength = opal_mapped_strength(colorStrength);
    float visibility = opal_sky_visibility(lightUv.y, SkyBrightness, AmbientLight);
    float edgeOpacity = smoothstep(0.0, LightSettings.w, strength);
    float coverage = modelColor.a * edgeOpacity * texColor.a * fogFade;
    if (TintPass == 1) {
        float tint = clamp(opal_tint_weight(strength, hue, lightUv, visibility, LightSettings) * coverage, 0.0, 1.0);
        vec3 filterColor = mix(vec3(1.0), hue, tint);
        fragColor = vec4(pow(filterColor, vec3(TransitionWeight)), 1.0);
        return;
    }
    float opacity = strength * visibility * LightSettings.x * coverage;
    // Weight opacity so unchanged light stays stable while old/new meshes crossfade.
    opacity = opal_transition_opacity(opacity, TransitionWeight);
    fragColor = vec4(texColor.rgb * modelColor.rgb * hue, opacity);
}
