#ifndef OPALLIGHT_LIGHT_GLSL
#define OPALLIGHT_LIGHT_GLSL

float opal_peak(vec3 color) {
    return max(color.r, max(color.g, color.b));
}

float opal_color_strength(vec3 color) {
    float peak = opal_peak(color);
    float neutral = min(color.r, min(color.g, color.b));
    vec3 chroma = color - vec3(neutral);
    float chromaSum = chroma.r + chroma.g + chroma.b;
    float width = 0.25 * chromaSum;
    if (width <= 0.0) return peak;
    float gap = max(2.0 * (peak - neutral) - chromaSum, 0.0);
    float overlap = max(width - gap, 0.0) / width;
    // Round the dominant-channel switch without lifting weak RGB channels.
    // A symmetric width keeps both value and slope continuous at equal contributions.
    // Neutral white and one dominant chromatic channel keep their original strength.
    return peak + 0.25 * width * overlap * overlap;
}

float opal_mapped_strength(float strength) {
    return strength <= 0.9 ? strength : 0.9 + 0.1 * (strength - 0.9) / (strength - 0.8);
}

float opal_sky_visibility(float skyUv, float skyBrightness, float ambientLight) {
    float sky = clamp(skyUv / 240.0, 0.0, 1.0);
    float skyLight = mix(sky / (4.0 - 3.0 * sky), 1.0, ambientLight) * skyBrightness;
    return 1.0 - clamp(skyLight, 0.0, 1.0);
}

float opal_tint_weight(float strength, vec3 hue, vec2 lightUv, float skyVisibility, vec4 settings) {
    float saturation = 1.0 - min(hue.r, min(hue.g, hue.b));
    float tintVisibility = skyVisibility + (1.0 - skyVisibility) * settings.z * saturation;
    float blockLight = clamp(lightUv.x / 240.0, 0.0, 1.0);
    float share = clamp(strength / max(1.0 / 15.0, max(blockLight, 1.0 - skyVisibility)), 0.0, 1.0);
    return settings.y * share * tintVisibility;
}

float opal_transition_opacity(float opacity, float weight) {
    return 1.0 - pow(1.0 - clamp(opacity, 0.0, 1.0), weight);
}

#endif
