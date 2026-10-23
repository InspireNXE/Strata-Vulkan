#version 450

// What each draw is, in the low byte of its mode. Shapes are signed distance fields in device pixels, measured from
// the shape's center, which the vertices pass as their uv.
const uint SOLID = 0u;
const uint TEXTURE = 1u;
const uint GLYPH = 2u;
const uint ROUND_RECT = 3u;
const uint ROUND_RECT_STROKE = 4u;
const uint ELLIPSE = 5u;
const uint ELLIPSE_STROKE = 6u;
const uint SHADOW = 7u;
const uint MASK = 8u;
// Drawn with hard edges: coverage is all or nothing
const uint HARD = 0x100u;

layout(push_constant) uniform Frame {
    vec2 viewport;
    uint flags;
} frame;

layout(set = 0, binding = 0) uniform sampler2D image;
// Coverage of the paths the canvas rasterized this frame, one byte per pixel
layout(set = 1, binding = 0) readonly buffer Masks {
    uint data[];
} masks;

layout(location = 0) in vec2 position;
layout(location = 1) in vec2 uv;
layout(location = 2) in vec4 color;
layout(location = 3) flat in uint mode;
layout(location = 4) flat in vec4 params;
layout(location = 5) flat in vec4 clip;
layout(location = 6) flat in float clipRadius;

layout(location = 0) out vec4 outColor;

const float PI = 3.14159265358979;

vec3 toLinear(vec3 c) {
    return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), greaterThan(c, vec3(0.04045)));
}

float roundBox(vec2 p, vec2 extent, float radius) {
    vec2 q = abs(p) - extent + radius;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
}

float coverage(float distance) {
    return clamp(0.5 - distance, 0.0, 1.0);
}

float roundBoxCoverage(vec2 p, vec2 extent, float radius) {
    if (extent.x <= 0.0 || extent.y <= 0.0) {
        return 0.0;
    }
    return coverage(roundBox(p, extent, min(radius, min(extent.x, extent.y))));
}

float ellipseCoverage(vec2 p, vec2 radii) {
    if (radii.x <= 0.0 || radii.y <= 0.0) {
        return 0.0;
    }
    float k0 = length(p / radii);
    float k1 = length(p / (radii * radii));
    if (k1 == 0.0) {
        return 1.0;
    }
    return coverage(k0 * (k0 - 1.0) / k1);
}

// The blurred rounded rectangle, after Evan Wallace's closed form: exact across, sampled four times along
float gaussian(float x, float sigma) {
    return exp(-(x * x) / (2.0 * sigma * sigma)) / (sqrt(2.0 * PI) * sigma);
}

vec2 erf(vec2 x) {
    vec2 s = sign(x);
    vec2 a = abs(x);
    x = 1.0 + (0.278393 + (0.230389 + 0.078108 * (a * a)) * a) * a;
    x *= x;
    return s - s / (x * x);
}

float shadowAcross(float x, float y, float sigma, float corner, vec2 extent) {
    float delta = min(extent.y - corner - abs(y), 0.0);
    float curved = extent.x - corner + sqrt(max(0.0, corner * corner - delta * delta));
    vec2 integral = 0.5 + 0.5 * erf((x + vec2(-curved, curved)) * (sqrt(0.5) / sigma));
    return integral.y - integral.x;
}

float shadowCoverage(vec2 p, vec2 extent, float corner, float sigma) {
    float low = p.y - extent.y;
    float high = p.y + extent.y;
    float start = clamp(-3.0 * sigma, low, high);
    float end = clamp(3.0 * sigma, low, high);
    float step = (end - start) / 4.0;
    float y = start + step * 0.5;
    float value = 0.0;
    for (int i = 0; i < 4; i++) {
        value += shadowAcross(p.x, p.y - y, sigma, corner, extent) * gaussian(y, sigma) * step;
        y += step;
    }
    return clamp(value, 0.0, 1.0);
}

float maskCoverage() {
    ivec2 pixel = ivec2(floor(position)) - ivec2(params.xy);
    uint width = uint(params.z);
    uint x = uint(clamp(pixel.x, 0, int(width) - 1));
    // The offset comes in 4-byte words, exact as a float up to 64 MB of coverage
    uint index = uint(params.w) * 4u + uint(max(pixel.y, 0)) * width + x;
    uint word = masks.data[index >> 2u];
    return float((word >> ((index & 3u) * 8u)) & 0xFFu) / 255.0;
}

void main() {
    uint kind = mode & 0xFFu;
    vec4 c = color;
    float cover = 1.0;
    if (kind == TEXTURE) {
        vec4 texel = texture(image, uv);
        if ((frame.flags & 1u) != 0u) {
            texel.rgb = toLinear(texel.rgb);
        }
        c *= texel;
    } else if (kind == GLYPH) {
        cover = texture(image, uv).r;
    } else if (kind == ROUND_RECT) {
        cover = roundBoxCoverage(uv, params.xy, params.z);
    } else if (kind == ROUND_RECT_STROKE) {
        float inner = params.z - params.w;
        cover = max(roundBoxCoverage(uv, params.xy, params.z)
                - roundBoxCoverage(uv, params.xy - params.w, max(inner, 0.0)), 0.0);
    } else if (kind == ELLIPSE) {
        cover = ellipseCoverage(uv, params.xy);
    } else if (kind == ELLIPSE_STROKE) {
        cover = max(ellipseCoverage(uv, params.xy) - ellipseCoverage(uv, params.xy - params.z), 0.0);
    } else if (kind == SHADOW) {
        cover = shadowCoverage(uv, params.xy, params.z, params.w);
    } else if (kind == MASK) {
        cover = maskCoverage();
    }
    if (clipRadius >= 0.0) {
        cover *= roundBoxCoverage(position - clip.xy, clip.zw, clipRadius);
    }
    if ((mode & HARD) != 0u) {
        cover = cover >= 0.5 ? 1.0 : 0.0;
    }
    float alpha = c.a * cover;
    outColor = vec4(c.rgb * alpha, alpha);
}
