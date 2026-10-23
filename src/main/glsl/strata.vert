#version 450

// Every Strata draw goes through this one pipeline. Positions are device pixels from the top left of the viewport.

layout(push_constant) uniform Frame {
    vec2 viewport;
    // Bit 0: the target encodes to sRGB, so colors are converted to linear before blending
    uint flags;
} frame;

layout(location = 0) in vec2 inPosition;
layout(location = 1) in vec2 inUv;
layout(location = 2) in vec4 inColor;
layout(location = 3) in uint inMode;
layout(location = 4) in vec4 inParams;
layout(location = 5) in vec4 inClip;
layout(location = 6) in float inClipRadius;

layout(location = 0) out vec2 position;
layout(location = 1) out vec2 uv;
layout(location = 2) out vec4 color;
layout(location = 3) flat out uint mode;
layout(location = 4) flat out vec4 params;
layout(location = 5) flat out vec4 clip;
layout(location = 6) flat out float clipRadius;

vec3 toLinear(vec3 c) {
    return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), greaterThan(c, vec3(0.04045)));
}

void main() {
    position = inPosition;
    uv = inUv;
    color = (frame.flags & 1u) != 0u ? vec4(toLinear(inColor.rgb), inColor.a) : inColor;
    mode = inMode;
    params = inParams;
    clip = inClip;
    clipRadius = inClipRadius;
    gl_Position = vec4(inPosition / frame.viewport * 2.0 - 1.0, 0.0, 1.0);
}
