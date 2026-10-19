package com.alananasss.kittytune.ui.player.cover

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Shader
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** A restrained camera orbit: sixteen seconds, with matching position and velocity at the seam. */
internal fun depthCoverMotion(seconds: Float): Pair<Float, Float> {
    val phase = (seconds % 16f) * (2.0 * PI / 16.0)
    return (sin(phase) * 0.011).toFloat() to (cos(phase) * 0.006).toFloat()
}

internal const val DEPTH_ARTWORK_SKSL = """
    uniform shader artwork;
    uniform shader depthMap;
    uniform float2 artworkSize;
    uniform float2 depthSize;
    uniform float2 viewport;
    uniform float2 uvScale;
    uniform float2 motion;
    half4 main(float2 pixel) {
        float2 uv = (pixel / viewport - 0.5) * uvScale + 0.5;
        if (uv.x < 0.0 || uv.y < 0.0 || uv.x > 1.0 || uv.y > 1.0) return half4(0.0);
        // Overscan leaves room for the camera without exposing an empty frame edge.
        uv = (uv - 0.5) / 1.04 + 0.5;
        float nearPlane = clamp(float(depthMap.eval(uv * depthSize).r), 0.0, 1.0);
        float2 displaced = uv + motion * (nearPlane - 0.5);
        return artwork.eval(clamp(displaced, float2(0.0), float2(1.0)) * artworkSize);
    }
"""

/** Scoped to one visible viewer. Every native shader made for a frame is released after drawing. */
internal class DepthArtworkRenderer private constructor(
    private val artwork: Image,
    private val depth: Image,
    private val effect: RuntimeEffect,
    private val builder: RuntimeShaderBuilder,
    private val paint: Paint,
    private val artShader: Shader,
    private val depthShader: Shader,
) : AutoCloseable {
    private var closed = false

    init {
        builder.child("artwork", artShader)
        builder.child("depthMap", depthShader)
        builder.uniform("artworkSize", artwork.width.toFloat(), artwork.height.toFloat())
        builder.uniform("depthSize", depth.width.toFloat(), depth.height.toFloat())
    }

    fun draw(canvas: Canvas, width: Float, height: Float, seconds: Float, contentScale: ContentScale) {
        if (closed || width <= 0f || height <= 0f) return
        val scale = contentScale.computeScaleFactor(Size(artwork.width.toFloat(), artwork.height.toFloat()), Size(width, height))
        val (x, y) = depthCoverMotion(seconds)
        builder.uniform("viewport", width, height)
        builder.uniform("uvScale", width / (artwork.width * scale.scaleX), height / (artwork.height * scale.scaleY))
        builder.uniform("motion", x, y)
        builder.makeShader(null).use { shader ->
            paint.shader = shader
            try { canvas.drawRect(Rect.makeWH(width, height), paint) }
            finally { paint.shader = null }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        paint.close()
        builder.close()
        artShader.close()
        depthShader.close()
        artwork.close()
        depth.close()
        effect.close()
    }

    companion object {
        /** Images transfer to the renderer only on success; failed construction releases every partial allocation. */
        fun create(artwork: Image, depth: Image): DepthArtworkRenderer {
            val allocated = mutableListOf<AutoCloseable>()
            try {
                val effect = RuntimeEffect.makeForShader(DEPTH_ARTWORK_SKSL).also(allocated::add)
                val builder = RuntimeShaderBuilder(effect).also(allocated::add)
                val paint = Paint().also(allocated::add)
                val artShader = artwork.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, SamplingMode.LINEAR).also(allocated::add)
                val depthShader = depth.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, SamplingMode.LINEAR).also(allocated::add)
                return DepthArtworkRenderer(artwork, depth, effect, builder, paint, artShader, depthShader)
            } catch (failure: Throwable) {
                allocated.asReversed().forEach { resource ->
                    try { resource.close() } catch (closeFailure: Throwable) { failure.addSuppressed(closeFailure) }
                }
                throw failure
            }
        }
    }
}
