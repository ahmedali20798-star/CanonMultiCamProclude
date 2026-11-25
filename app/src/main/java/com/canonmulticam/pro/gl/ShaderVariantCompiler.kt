package com.canonmulticam.pro.gl

object ShaderToolFlags {
    const val LUT = 1 shl 0
    const val PEAKING = 1 shl 1
    const val FALSE_COLOR = 1 shl 2
    const val ZEBRA = 1 shl 3
    const val GUIDE_THIRDS = 1 shl 4
    const val GUIDE_9_16 = 1 shl 5
    const val GUIDE_2_39 = 1 shl 6
}

object ShaderVariantCompiler {

    val VERTEX_SHADER = """
        #version 300 es
        layout(location = 0) in vec2 aPos;
        layout(location = 1) in vec2 aTexCoord;
        out vec2 vTexCoord;
        void main() {
            gl_Position = vec4(aPos, 0.0, 1.0);
            vTexCoord = aTexCoord;
        }
    """.trimIndent()

    fun buildFragmentShader(flags: Int): String {
        val defines = buildString {
            if ((flags and ShaderToolFlags.LUT) != 0) appendLine("#define USE_LUT")
            if ((flags and ShaderToolFlags.PEAKING) != 0) appendLine("#define USE_PEAKING")
            if ((flags and ShaderToolFlags.FALSE_COLOR) != 0) appendLine("#define USE_FALSE_COLOR")
            if ((flags and ShaderToolFlags.ZEBRA) != 0) appendLine("#define USE_ZEBRA")
            if ((flags and ShaderToolFlags.GUIDE_THIRDS) != 0) appendLine("#define USE_GUIDE_THIRDS")
            if ((flags and ShaderToolFlags.GUIDE_9_16) != 0) appendLine("#define USE_GUIDE_9_16")
            if ((flags and ShaderToolFlags.GUIDE_2_39) != 0) appendLine("#define USE_GUIDE_2_39")
        }

        return """
            #version 300 es
            precision highp float;
            precision highp sampler3D;
            $defines
            in vec2 vTexCoord;
            out vec4 fragColor;

            uniform sampler2D uFrameTex;
            uniform sampler3D uLutTex;
            uniform vec2 uTexelSize;
            uniform float uTimeSec;
            uniform int uBypassLut;
            uniform vec3 uPeakingColor;
            uniform float uAnamorphicSqueeze;
            uniform float uZebraThreshold;

            float calcLuma(vec3 c) {
                return dot(c, vec3(0.2126, 0.7152, 0.0722));
            }

            vec3 mapFalseColor(float luma) {
                if (luma < 0.05) return vec3(0.35, 0.0, 0.55);
                if (luma < 0.15) return vec3(0.0, 0.25, 0.90);
                if (luma < 0.35) return vec3(0.0, 0.75, 0.85);
                if (luma < 0.52) return vec3(0.10, 0.80, 0.25);
                if (luma < 0.65) return vec3(0.95, 0.45, 0.65);
                if (luma < 0.82) return vec3(0.95, 0.85, 0.10);
                if (luma < 0.94) return vec3(0.98, 0.45, 0.05);
                return vec3(0.95, 0.05, 0.05);
            }

            void main() {
                vec2 uv = vTexCoord;
                if (uAnamorphicSqueeze > 1.01) {
                    float scaleX = 1.0 / uAnamorphicSqueeze;
                    uv.x = (uv.x - 0.5) * scaleX + 0.5;
                    if (uv.x < 0.0 || uv.x > 1.0) {
                        fragColor = vec4(0.04, 0.04, 0.04, 1.0);
                        return;
                    }
                }

                vec3 rgb = texture(uFrameTex, uv).rgb;
                float rawLuma = calcLuma(rgb);

                #ifdef USE_LUT
                if (uBypassLut == 0) {
                    rgb = texture(uLutTex, clamp(rgb, 0.0, 1.0)).rgb;
                }
                #endif

                #ifdef USE_FALSE_COLOR
                rgb = mapFalseColor(rawLuma);
                #endif

                #ifdef USE_ZEBRA
                if (rawLuma >= uZebraThreshold) {
                    float stripe = mod(gl_FragCoord.x + gl_FragCoord.y + uTimeSec * 45.0, 12.0);
                    if (stripe < 5.0) {
                        rgb = mix(rgb, vec3(1.0, 0.15, 0.15), 0.78);
                    }
                }
                #endif

                #ifdef USE_PEAKING
                float lL = calcLuma(texture(uFrameTex, uv - vec2(uTexelSize.x, 0.0)).rgb);
                float lR = calcLuma(texture(uFrameTex, uv + vec2(uTexelSize.x, 0.0)).rgb);
                float lU = calcLuma(texture(uFrameTex, uv - vec2(0.0, uTexelSize.y)).rgb);
                float lD = calcLuma(texture(uFrameTex, uv + vec2(0.0, uTexelSize.y)).rgb);
                float edge = abs(lL - lR) + abs(lU - lD);
                if (edge > 0.22) {
                    rgb = mix(rgb, uPeakingColor, clamp((edge - 0.22) * 3.0, 0.0, 0.92));
                }
                #endif

                #ifdef USE_GUIDE_THIRDS
                vec2 gridDist = abs(fract(uv * 3.0 + 0.5) - 0.5);
                if (uv.x > 0.02 && uv.x < 0.98 && uv.y > 0.02 && uv.y < 0.98) {
                    if (gridDist.x < 0.004 || gridDist.y < 0.006) {
                        rgb = mix(rgb, vec3(0.96, 0.95, 0.93), 0.45);
                    }
                }
                #endif

                #ifdef USE_GUIDE_9_16
                float halfW = (9.0 / 16.0) / (16.0 / 9.0) * 0.5;
                float distTo916 = abs(abs(uv.x - 0.5) - halfW);
                if (abs(uv.x - 0.5) > halfW) {
                    rgb *= 0.35;
                }
                if (distTo916 < 0.0025) {
                    rgb = vec3(0.65, 0.55, 0.38);
                }
                #endif

                #ifdef USE_GUIDE_2_39
                float activeH = (16.0 / 9.0) / 2.39 * 0.5;
                float distTo239 = abs(abs(uv.y - 0.5) - activeH);
                if (abs(uv.y - 0.5) > activeH) {
                    rgb *= 0.22;
                }
                if (distTo239 < 0.003) {
                    rgb = vec3(0.65, 0.55, 0.38);
                }
                #endif

                fragColor = vec4(rgb, 1.0);
            }
        """.trimIndent()
    }
}
