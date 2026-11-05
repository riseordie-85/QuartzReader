package com.tim.reader.reader

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import androidx.compose.ui.graphics.asComposeRenderEffect
import com.tim.reader.ResultsLog
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

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

/**
 * Turns a leaf around the spine of an open book. The right half of the layer is the leaf's front and
 * the left half is its back, so the page landing on the far side is the correct one. Mirrored, it
 * turns the other way for right-to-left books.
 */
private const val BOOK_SHADER = """
    uniform shader content;
    uniform float2 size;
    uniform float curlAt;
    uniform float radius;
    uniform float tilt;
    uniform float lift;
    uniform float mirror;

    const float PI = 3.14159265;

    float2 toLayer(float2 point) {
        return mirror > 0.5 ? float2(size.x - point.x, point.y) : point;
    }

    bool onLeaf(float2 point, float spine) {
        return point.x >= spine && point.x <= size.x && point.y >= 0.0 && point.y <= size.y;
    }

    half4 shaded(float2 point, float light) {
        half4 color = content.eval(toLayer(point));
        return half4(color.rgb * half(light), color.a);
    }

    half4 main(float2 position) {
        float spine = size.x * 0.5;
        float2 point = toLayer(position);
        float2 normal = float2(cos(tilt), sin(tilt));
        float2 origin = float2(spine + curlAt, size.y * 0.5);
        float along = dot(point - origin, normal);

        if (along < 0.0) {
            float2 source = point + normal * (PI * radius - 2.0 * along);
            if (onLeaf(source, spine)) {
                return shaded(float2(2.0 * spine - source.x, source.y), 1.0 - 0.04 * lift);
            }
            if (point.x >= spine) {
                return content.eval(position);
            }
            float past = source.x - size.x;
            return half4(0.0, 0.0, 0.0, half(0.3 * lift * clamp(1.0 - past / (size.x * 0.05), 0.0, 1.0)));
        }

        if (along <= radius) {
            float angle = asin(along / radius);
            float2 upper = point + normal * (radius * (PI - angle) - along);
            if (onLeaf(upper, spine)) {
                return shaded(float2(2.0 * spine - upper.x, upper.y), 0.72 + 0.24 * cos(angle));
            }
            float2 lower = point + normal * (radius * angle - along);
            if (onLeaf(lower, spine)) {
                return shaded(lower, 0.6 + 0.4 * cos(angle));
            }
        }

        return half4(0.0, 0.0, 0.0, half(0.35 * lift * clamp(1.0 - (along - radius) / (size.x * 0.08), 0.0, 1.0)));
    }
"""

class CurlEffect private constructor(private val shader: RuntimeShader, private val bookShader: RuntimeShader?) {
    val canTurnLikeBook get() = bookShader != null


    /** The effect at this stage of the turn, from 0 at rest to 1 fully turned. */
    fun at(width: Float, height: Float, progress: Float, rightToLeft: Boolean) = run {
        val radius = width * 0.14f
        shader.setFloatUniform("size", width, height)
        shader.setFloatUniform("radius", radius)
        shader.setFloatUniform("curlX", width + radius - progress * (width + radius * 3f))
        shader.setFloatUniform("mirror", if (rightToLeft) 1f else 0f)
        RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
    }

    /** One leaf of an open book at this stage of the turn, from 0 at rest to 1 landed. */
    fun book(width: Float, height: Float, progress: Float, rightToLeft: Boolean): androidx.compose.ui.graphics.RenderEffect {
        val book = checkNotNull(bookShader)
        val lift = sin(PI.toFloat() * progress)
        val leaf = width / 2f
        book.setFloatUniform("size", width, height)
        book.setFloatUniform("curlAt", leaf * (1f - progress))
        book.setFloatUniform("radius", max(leaf * 0.16f * lift, 0.5f))
        book.setFloatUniform("tilt", 0.14f * lift)
        book.setFloatUniform("lift", lift)
        book.setFloatUniform("mirror", if (rightToLeft) 1f else 0f)
        return RenderEffect.createRuntimeShaderEffect(book, "content").asComposeRenderEffect()
    }

    companion object {
        /** Returns null if this device can't compile the page curl, and pages slide instead. */
        fun create(): CurlEffect? {
            val page = compile(CURL_SHADER) ?: return null
            return CurlEffect(page, compile(BOOK_SHADER))
        }

        private fun compile(code: String): RuntimeShader? = try {
            RuntimeShader(code)
        } catch (error: RuntimeException) {
            ResultsLog.add("Page curl unavailable: ${error.message}")
            null
        }
    }
}
