package com.tim.reader.reader

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import androidx.compose.ui.graphics.asComposeRenderEffect

/** Rolls the page around a cylinder that sweeps across it, showing the next page underneath. */
private const val CURL_SHADER = """
    uniform shader content;
    uniform float2 size;
    uniform float curlX;
    uniform float radius;
    uniform float mirror;

    const float PI = 3.14159265;

    half4 sampleAt(float x, float y) {
        float sourceX = mirror > 0.5 ? size.x - x : x;
        return content.eval(float2(sourceX, y));
    }

    half4 main(float2 position) {
        float x = mirror > 0.5 ? size.x - position.x : position.x;
        float distance = x - curlX;
        if (distance < 0.0) {
            return sampleAt(x, position.y);
        }
        if (distance > radius) {
            return half4(0.0);
        }
        float angle = asin(distance / radius);
        float backX = curlX + radius * (PI - angle);
        if (backX < size.x) {
            half4 back = sampleAt(backX, position.y);
            float light = 0.72 + 0.28 * cos(angle);
            return half4(mix(back.rgb, half3(0.95), 0.75) * light, 1.0);
        }
        float frontX = curlX + radius * angle;
        if (frontX < size.x) {
            half4 front = sampleAt(frontX, position.y);
            float light = 0.55 + 0.45 * cos(angle);
            return half4(front.rgb * light, front.a);
        }
        return half4(0.0);
    }
"""

class CurlEffect private constructor(private val shader: RuntimeShader) {

    /** The effect at this stage of the turn, from 0 at rest to 1 fully turned. */
    fun at(width: Float, height: Float, progress: Float, rightToLeft: Boolean) = run {
        val radius = width * 0.14f
        shader.setFloatUniform("size", width, height)
        shader.setFloatUniform("radius", radius)
        shader.setFloatUniform("curlX", width + radius - progress * (width + radius * 3f))
        shader.setFloatUniform("mirror", if (rightToLeft) 1f else 0f)
        RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
    }

    companion object {
        /** Returns null if this device can't compile the shader, and pages slide instead. */
        fun create(): CurlEffect? = try {
            CurlEffect(RuntimeShader(CURL_SHADER))
        } catch (_: RuntimeException) {
            null
        }
    }
}
