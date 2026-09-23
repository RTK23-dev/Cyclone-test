package com.cyclone.mobile.ui.overlay.tracefield

/**
 * AGSL for the Trace Field. Most fragments exit after one rounded-rect SDF; only pixels under the
 * lens, ripple, scan band, rain or edge filament sample the glyph atlas.
 *
 * Atlas rows: 0 = glyph core, 1 = outline halo, 2 = soft (blurred) glyph for depth of field.
 * The backdrop child is a coarse colour grid averaged from Cyclone's own observation screenshots;
 * overlays cannot blend against other apps' pixels, so this is how the field adapts to them.
 */
internal object TraceFieldShader {
    /** Hex digits carry the page fingerprint; the four marks are texture. Order is the atlas order. */
    const val GLYPHS = "0123456789ABCDEF·:+/"
    const val ATLAS_ROWS = 3

    val SOURCE = """
uniform shader atlas;
uniform shader backdrop;
uniform float2 res;
uniform float2 cell;
uniform float2 gridSize;
uniform float backdropOn;
uniform float style;
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
uniform float opacity;
uniform float focus;
uniform float flowTime;
uniform float4 excl;
layout(color) uniform half4 tint;
layout(color) uniform half4 hot;
layout(color) uniform half4 warm;
layout(color) uniform half4 ink;
layout(color) uniform half4 accent;

float h21(float2 p) {
    p = fract(p * float2(123.34, 456.21) + float2(seed * 0.0137, seed * 0.0071));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float sdRoundRect(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + r;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

float atlasA(float g, float2 local, float row) {
    float2 l = clamp(local, float2(0.5), cell - 0.5);
    return float(atlas.eval(float2(g * cell.x + l.x, row * cell.y + l.y)).a);
}

// Resolves the glyph cell under xy. Returns false for empty cells.
bool cellAt(float2 xy, float2 size, float layer, float shift, float colGate,
            out float2 local, out float g, out float gPrev, out float age) {
    float col = floor(xy.x / size.x);
    float colRate = 0.6 + 0.8 * h21(float2(col, layer * 17.0 + 3.0));
    float2 p = float2(xy.x, xy.y - shift * colRate);
    float2 c = floor(p / size);
    local = (p - c * size) * (cell / size);
    g = 0.0; gPrev = 0.0; age = 0.0;
    // Column rhythm plus sparse cells: the field reads as fine streams, never a wall of text.
    if (h21(float2(col, layer * 5.0 + 11.0)) < colGate) return false;
    if (h21(c + layer * 31.0) < 0.5 + layer * 0.2) return false;
    float rate = 5.0 + 9.0 * h21(c + 3.1) + scramble * 30.0;
    float phase = clock * rate + h21(c + 7.7) * 10.0;
    float tick = floor(phase);
    age = phase - tick;
    g = floor(h21(c + tick * 0.618) * glyphCount);
    gPrev = floor(h21(c + (tick - 1.0) * 0.618) * glyphCount);
    return true;
}

// Seed-free hash: the aurora must not jump when a new page fingerprint reseeds the digits.
float n21(float2 p) {
    p = fract(p * float2(233.34, 851.73));
    p += dot(p, p + 23.45);
    return fract(p.x * p.y);
}

float vnoise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = n21(i);
    float b = n21(i + float2(1.0, 0.0));
    float c = n21(i + float2(0.0, 1.0));
    float d = n21(i + float2(1.0, 1.0));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

// Domain-warped noise shaped into slow curtains, like an aurora or a tide line.
float aurora(float2 p, float t) {
    float2 uv = p / res.y;
    float2 w = float2(vnoise(uv * 1.1 + float2(t * 0.025, -t * 0.018)),
                      vnoise(uv * 1.1 + float2(-t * 0.02, t * 0.03) + 7.1));
    float n = 0.6 * vnoise(uv * float2(1.4, 2.6) + w * 1.8 + float2(0.0, t * 0.035))
            + 0.4 * vnoise(uv * float2(2.8, 5.0) + w * 1.2 - float2(t * 0.03, 0.0));
    float curtain = 0.5 + 0.5 * sin(uv.y * 5.0 + n * 4.0 - t * 0.15);
    // A slow diagonal swell that travels over the whole screen, so the unfocused field waves
    // everywhere instead of pooling in one spot.
    float swell = 0.5 + 0.5 * sin(uv.x * 1.7 + uv.y * 1.1 - t * 0.22 + n * 2.2);
    return smoothstep(0.38, 0.8, n) * (0.35 + 0.65 * curtain) * (0.25 + 0.75 * swell);
}

float3 iridescent(float2 xy) {
    float hue = fract(xy.y / res.y * 0.8 + xy.x / res.x * 0.3 + clock * 0.15);
    return 0.55 + 0.45 * cos(6.28318 * (hue + float3(0.0, 0.33, 0.67)));
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

    // Edge filament zone (cheap). It hugs one column per side, so it must not lose that column to thinning.
    float de = min(min(xy.x, res.x - xy.x), min(xy.y, res.y - xy.y));
    bool edgeZone = edge > 0.0 && de < cell.x * 2.5;
    float colGate = edgeZone ? 0.0 : 0.3;

    // Glyphs first: most pixels are not inside a digit, and they leave here before any field maths.
    float2 local; float g; float gPrev; float age;
    float core = 0.0;
    float halo = 0.0;
    float ghost = 0.0;
    float coreR = 0.0;
    float coreB = 0.0;
    bool hasCell = cellAt(q, cell, 0.0, flow, colGate, local, g, gPrev, age);
    if (hasCell) {
        core = atlasA(g, local, 0.0);
        halo = atlasA(g, local, 1.0);
    }
    float far = 0.0;
    float2 localF; float gF; float gPrevF; float ageF;
    bool chameleon = style > 1.5 && style < 2.5;
    if (cellAt(q + float2(cell.x * 0.37, cell.y * 0.21), cell * 0.72, 1.0, flow * 0.6, colGate, localF, gF, gPrevF, ageF)) {
        // Chameleon uses the blurred row: real depth of field.
        far = atlasA(gF, localF, chameleon ? 2.0 : 0.0) * (chameleon ? 0.4 : 0.24);
    }
    if (hasCell && style > 0.5 && style < 1.5) {
        // Forge: the previous digit lingers as a cooling afterglow.
        ghost = atlasA(gPrev, local, 0.0) * 0.4 * (1.0 - smoothstep(0.0, 0.3, age));
    }
    if (core + halo + far + ghost < 0.002) return half4(0.0);

    // Sharp attention: the rounded-rect lens that morphs onto the target.
    float d = sdRoundRect(q - lens.xy, lens.zw, lensCorner);
    float lensCoreMask = 1.0 - smoothstep(0.0, lensSoft, d);
    lensCoreMask *= lensCoreMask;
    // Two broad gradients so the field never reads as a cut-out: a natural oval around the lens
    // and a soft vertical band that reaches about half the screen height.
    float2 rel = (q - lens.xy) / (lens.zw + float2(res.x * 0.3, res.y * 0.16));
    float oval = exp(-2.2 * dot(rel, rel));
    float vy = (q.y - lens.y) / (res.y * 0.3);
    float band = exp(-1.6 * vy * vy);
    float2 relWide = (q - lens.xy) / (lens.zw + float2(res.x * 0.45, res.y * 0.26));
    float ovalWide = exp(-1.8 * dot(relWide, relWide));
    float focused = max(lensCoreMask, max(oval * 0.38, band * 0.13));
    float diffuse = max(ovalWide * 0.09, band * 0.04);
    float m = mix(diffuse, focused, focus);

    // Ambient aurora: flowing, never-repeating curtains across the whole screen. Low when
    // Cyclone is focused, the main motion while it thinks, opens apps or clicks through the UI tree.
    float amb = aurora(q, flowTime);
    // alpha.7: quieter at rest (faint floor 2 %, patches up to ~20 %), the lens carries focus.
    m = max(m, (0.02 + 0.2 * amb) * (1.0 - 0.6 * focus));

    if (ripple.w > 0.0) {
        float ring = abs(length(xy - ripple.xy) - ripple.z);
        m = max(m, (1.0 - smoothstep(0.0, lensSoft * 0.35, ring)) * ripple.w);
    }
    if (scan.y > 0.0) {
        float scanBand = 1.0 - smoothstep(0.0, lensSoft * 0.6, abs(xy.y - scan.x));
        m = max(m, scanBand * scan.y);
    }
    m *= intensity * fade;

    float e = 0.0;
    if (edgeZone) {
        float perimeter = 2.0 * (res.x + res.y);
        float s = 0.0;
        if (de == xy.y) { s = xy.x; }
        else if (de == res.x - xy.x) { s = res.x + xy.y; }
        else if (de == res.y - xy.y) { s = res.x + res.y + (res.x - xy.x); }
        else { s = 2.0 * res.x + res.y + (res.y - xy.y); }
        float behind = mod(edgeHead * perimeter - s + perimeter, perimeter);
        float tail = res.y * 0.4;
        if (behind < tail) {
            e = (1.0 - behind / tail) * (1.0 - smoothstep(cell.x * 0.7, cell.x * 2.5, de)) * edge * 0.85;
        }
    }

    float mask = max(m, e);
    if (mask < 0.004) return half4(0.0);

    float twinkle = 0.45 + 0.55 * h21(floor(q / cell) + floor(clock * 2.0));
    coreR = core;
    coreB = core;
    if (hasCell && style < 0.5) {
        // Obsidian: a 1.5 px red/blue split only on the lens rim, like real glass.
        float rim = focus * smoothstep(-lensSoft * 0.1, lensSoft * 0.15, d) * (1.0 - smoothstep(lensSoft * 0.15, lensSoft * 0.6, d));
        if (rim > 0.01) {
            coreR = mix(core, atlasA(g, local + float2(1.5, 0.0), 0.0), rim);
            coreB = mix(core, atlasA(g, local - float2(1.5, 0.0), 0.0), rim);
        }
    }
    float light = 0.0;
    float mid = 0.0;
    if (backdropOn > 0.5) {
        half4 bg = backdrop.eval(xy / res * gridSize);
        float lum = dot(float3(bg.rgb), float3(0.2126, 0.7152, 0.0722));
        // Only clearly light content flips to ink; colourful mid-tones keep glow plus a dark halo,
        // which stays crisp over photos where a half-way colour would turn muddy.
        light = smoothstep(0.6, 0.7, lum);
        mid = smoothstep(0.2, 0.45, lum) * (1.0 - light);
    }

    float lensCore = (0.35 + 0.65 * focus) * (1.0 - smoothstep(0.0, lensSoft * 0.9, max(d, 0.0) + lensSoft * 0.25));
    // Over mid-tone content (photos, video) the core runs whiter so it never greys into the image.
    float3 glow = float3(mix(tint.rgb, hot.rgb, half(max(lensCore * 0.7, mid * 0.8))));
    float3 inkRgb = float3(ink.rgb);
    float3 coreCol = glow;
    float3 haloCol = float3(0.02, 0.03, 0.06);
    float haloStrength = 0.5;

    if (style < 0.5) {
        // Obsidian: glowing digits on dark content, ink digits on light content (a live Difference).
        coreCol = mix(glow, inkRgb, light);
        haloCol = mix(float3(0.02, 0.03, 0.06), float3(0.97, 0.98, 1.0), light);
        haloStrength = 0.7;
    } else if (style < 1.5) {
        // Forge: born white-hot, cools to Cyclone blue, dies as an ember.
        float3 whiteHot = float3(1.0, 0.97, 0.9);
        float3 blue = float3(tint.rgb);
        float3 ember = float3(1.0, 0.42, 0.16);
        float3 heat = mix(whiteHot, blue, smoothstep(0.0, 0.4, age));
        heat = mix(heat, ember, smoothstep(0.55, 0.95, age));
        coreCol = mix(heat, heat * 0.55, light);
        core *= 1.0 - 0.55 * smoothstep(0.5, 1.0, age);
        haloStrength = 0.4;
    } else if (style < 2.5) {
        // Chameleon: takes on the colour of the app it is working in.
        float3 acc = float3(accent.rgb);
        coreCol = mix(mix(acc, float3(1.0), lensCore * 0.45), acc * 0.4, light);
        haloCol = mix(float3(0.02, 0.03, 0.06), float3(0.97, 0.98, 1.0), light);
        haloStrength = 0.65;
    } else {
        // Signal: print-style halftone dots toward the lens edge; iridescent finale.
        float dist01 = clamp(d / lensSoft + 0.35, 0.0, 1.0);
        float radius = 0.5 * (1.0 - dist01) + 0.12;
        float f = length(fract(xy / 3.0) - 0.5);
        float dots = 1.0 - smoothstep(radius - 0.1, radius, f);
        float amount = smoothstep(0.1, 0.45, dist01);
        core = mix(core, core * dots, amount);
        coreR = core;
        coreB = core;
        far *= mix(1.0, dots, amount);
        coreCol = mix(glow, inkRgb, light);
        if (rain >= 0.0) coreCol = mix(coreCol, iridescent(xy), 0.85);
        haloStrength = 0.65 * (1.0 - amount);
    }
    coreCol = mix(coreCol, float3(warm.rgb), warmth);

    float a = mask * opacity;
    float cA = max(core * twinkle, far) * a;
    float rA = max(coreR * twinkle, far) * a;
    float bA = max(coreB * twinkle, far) * a;
    float gA = ghost * a;
    float hA = halo * haloStrength * a * (1.0 - cA);
    float alpha = clamp(max(max(rA, bA), cA) + gA * (1.0 - cA) + hA, 0.0, 1.0);
    if (alpha < 0.002) return half4(0.0);
    float3 ember = float3(1.0, 0.42, 0.16);
    float3 rgb = float3(coreCol.r * rA, coreCol.g * cA, coreCol.b * bA) + ember * gA * (1.0 - cA) + haloCol * hA;
    return half4(half3(rgb), half(alpha));
}
""".trimIndent()
}
