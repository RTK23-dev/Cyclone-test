package com.cyclone.mobile.ui.overlay.tracefield

/**
 * AGSL for the Trace Field. Most fragments exit after one rounded-rect SDF; only pixels under the
 * lens, ripple, scan band, rain or edge filament sample the glyph atlas.
 */
internal object TraceFieldShader {
    /** Hex digits carry the page fingerprint; the four marks are texture. Order is the atlas order. */
    const val GLYPHS = "0123456789ABCDEF·:+/"

    val SOURCE = """
uniform shader atlas;
uniform float2 res;
uniform float2 cell;
uniform float glyphCount;
uniform float clock;
uniform float seed;
uniform float4 lens;
uniform float lensCorner;
uniform float lensSoft;
uniform float intensity;
uniform float4 ripple;
uniform float2 scan;
uniform float flow;
uniform float rain;
uniform float scramble;
uniform float edge;
uniform float edgeHead;
uniform float warmth;
uniform float4 excl;
layout(color) uniform half4 tint;
layout(color) uniform half4 hot;
layout(color) uniform half4 warm;

float h21(float2 p) {
    p = fract(p * float2(123.34, 456.21) + float2(seed * 0.0137, seed * 0.0071));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float sdRoundRect(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + r;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

float glyphAt(float2 xy, float2 size, float layer, float shift, float colGate) {
    float col = floor(xy.x / size.x);
    float colRate = 0.6 + 0.8 * h21(float2(col, layer * 17.0 + 3.0));
    float2 p = float2(xy.x, xy.y - shift * colRate);
    float2 c = floor(p / size);
    // Column rhythm plus sparse cells: the field reads as fine streams, never a wall of text.
    if (h21(float2(col, layer * 5.0 + 11.0)) < colGate) return 0.0;
    if (h21(c + layer * 31.0) < 0.5 + layer * 0.2) return 0.0;
    float rate = 5.0 + 9.0 * h21(c + 3.1) + scramble * 30.0;
    float tick = floor(clock * rate + h21(c + 7.7) * 10.0);
    float g = floor(h21(c + tick * 0.618) * glyphCount);
    float2 local = (p - c * size) * (cell / size);
    return float(atlas.eval(float2(g * cell.x + local.x, local.y)).a);
}

half4 main(float2 xy) {
    if (xy.x > excl.x && xy.x < excl.z && xy.y > excl.y && xy.y < excl.w) return half4(0.0);

    float2 q = xy;
    float fade = 1.0;
    if (rain >= 0.0) {
        float col = floor(xy.x / cell.x);
        float delay = h21(float2(col, 91.0)) * 0.35;
        float k = clamp((rain - delay) / 0.65, 0.0, 1.0);
        q = float2(xy.x, xy.y - k * k * res.y * 0.9);
        fade = 1.0 - smoothstep(0.55, 1.0, rain);
    }

    float d = sdRoundRect(q - lens.xy, lens.zw, lensCorner);
    float m = 1.0 - smoothstep(0.0, lensSoft, d);
    m = m * m;
    if (ripple.w > 0.0) {
        float ring = abs(length(xy - ripple.xy) - ripple.z);
        m = max(m, (1.0 - smoothstep(0.0, cell.y * 1.3, ring)) * ripple.w);
    }
    if (scan.y > 0.0) {
        float band = 1.0 - smoothstep(0.0, cell.y * 2.2, abs(xy.y - scan.x));
        m = max(m, band * scan.y);
    }
    m *= intensity * fade;

    float e = 0.0;
    if (edge > 0.0) {
        float dl = xy.x;
        float dr = res.x - xy.x;
        float dt = xy.y;
        float db = res.y - xy.y;
        float de = min(min(dl, dr), min(dt, db));
        if (de < cell.x * 1.25) {
            float perimeter = 2.0 * (res.x + res.y);
            float s = 0.0;
            if (de == dt) { s = xy.x; }
            else if (de == dr) { s = res.x + xy.y; }
            else if (de == db) { s = res.x + res.y + (res.x - xy.x); }
            else { s = 2.0 * res.x + res.y + (res.y - xy.y); }
            float behind = mod(edgeHead * perimeter - s + perimeter, perimeter);
            float tail = res.y * 0.4;
            if (behind < tail) {
                e = (1.0 - behind / tail) * (1.0 - smoothstep(cell.x * 0.35, cell.x * 1.25, de)) * edge * 0.85;
            }
        }
    }

    float mask = max(m, e);
    if (mask < 0.004) return half4(0.0);

    // The edge filament hugs one column per side, so it must not lose that column to thinning.
    float colGate = e > m ? 0.0 : 0.3;
    float a0 = glyphAt(q, cell, 0.0, flow, colGate);
    float a1 = glyphAt(q + float2(cell.x * 0.37, cell.y * 0.21), cell * 0.72, 1.0, flow * 0.6, colGate) * 0.24;
    float twinkle = 0.45 + 0.55 * h21(floor(q / cell) + floor(clock * 2.0));
    float a = max(a0 * twinkle, a1) * mask;
    if (a < 0.002) return half4(0.0);

    float core = 1.0 - smoothstep(0.0, lensSoft * 0.9, max(d, 0.0) + lensSoft * 0.25);
    half4 base = mix(tint, hot, half(core * 0.7));
    half4 c = mix(base, warm, half(warmth));
    float alpha = a * float(c.a);
    return half4(c.rgb * half(alpha), half(alpha));
}
""".trimIndent()
}
