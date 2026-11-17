@file:OptIn(UnstableApi::class)

package app.kadro.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.OverlaySettings
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.GainProcessor
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbMatrix
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Constant playback rate for a whole clip. */
private data class FixedSpeed(val speed: Float) : SpeedProvider {
    override fun getSpeed(timeUs: Long): Float = speed
    override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = C.TIME_UNSET
}

/** Constant gain. */
private class FixedGain(val gain: Float) : GainProcessor.GainProvider {
    override fun getGainFactorAtSamplePosition(samplePosition: Long, sampleRate: Int): Float = gain
    override fun isUnityUntil(samplePosition: Long, sampleRate: Int): Long =
        if (gain == 1f) C.TIME_END_OF_SOURCE else C.TIME_UNSET
}

/** A fixed colour matrix. */
private class LookMatrix(private val m: FloatArray) : RgbMatrix {
    override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray = m
}

/**
 * Dips a clip from/to black at its edges. Times are composition time, which is what both the
 * preview player and the exporter hand to per-clip effects.
 */
private class EdgeFade(
    private val startUs: Long,
    private val endUs: Long,
    private val inUs: Long,
    private val outUs: Long,
) : RgbMatrix {
    private val m = FloatArray(16)
    override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray {
        var k = 1f
        if (inUs > 0) k = min(k, (presentationTimeUs - startUs).toFloat() / inUs)
        if (outUs > 0) k = min(k, (endUs - presentationTimeUs).toFloat() / outUs)
        k = k.coerceIn(0f, 1f)
        // Frames are processed in linear light; square the ramp so the dip looks even.
        k *= k
        java.util.Arrays.fill(m, 0f)
        m[0] = k; m[5] = k; m[10] = k; m[15] = 1f
        return m
    }
}

/** Uniform zoom around the canvas centre; the canvas size is left untouched. */
private class Zoom(private val z: Float) : MatrixTransformation {
    override fun getMatrix(presentationTimeUs: Long): Matrix = Matrix().apply { setScale(z, z) }
}

/** A text bitmap that is only shown inside its time window. */
private class TimedText(
    private val bitmap: Bitmap,
    private val startUs: Long,
    private val endUs: Long,
    x: Float,
    y: Float,
) : BitmapOverlay() {
    private val shown: OverlaySettings =
        StaticOverlaySettings.Builder().setBackgroundFrameAnchor(x, y).build()
    private val hidden: OverlaySettings =
        StaticOverlaySettings.Builder().setBackgroundFrameAnchor(x, y).setAlphaScale(0f).build()

    override fun getBitmap(presentationTimeUs: Long): Bitmap = bitmap
    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings =
        if (presentationTimeUs in startUs until endUs) shown else hidden
}

object Engine {

    /** Draws a text layer exactly as it will be burnt into a canvas [canvasH] pixels tall. */
    fun renderText(t: TextLayer, canvasW: Int, canvasH: Int): Bitmap {
        val px = (t.size * canvasH).coerceAtLeast(8f)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = px
            color = t.color
            typeface = Typeface.create(Typeface.SANS_SERIF, if (t.bold) Typeface.BOLD else Typeface.NORMAL)
        }
        val text = t.text.ifBlank { " " }
        val maxW = (canvasW * 0.92f).toInt().coerceAtLeast(16)
        val wanted = text.lines().maxOf { ceil(paint.measureText(it)).toInt() } + 2
        val w = min(wanted, maxW)
        fun layout(p: TextPaint) = StaticLayout.Builder.obtain(text, 0, text.length, p, w)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(true)
            .build()
        val body = layout(paint)
        val pad = (px * 0.32f).toInt()
        val bmp = Bitmap.createBitmap(
            max(2, w + pad * 2), max(2, body.height + pad * 2), Bitmap.Config.ARGB_8888,
        )
        val c = Canvas(bmp)
        if (t.plate) {
            val bright = (android.graphics.Color.red(t.color) * 3 +
                android.graphics.Color.green(t.color) * 6 +
                android.graphics.Color.blue(t.color)) / 10 > 140
            val plate = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (bright) 0xE6101114.toInt() else 0xE6F4EFE6.toInt()
            }
            c.drawRoundRect(
                RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat()), px * 0.28f, px * 0.28f, plate,
            )
        }
        c.translate(pad.toFloat(), pad.toFloat())
        if (t.outline && !t.plate) {
            val stroke = TextPaint(paint).apply {
                style = Paint.Style.STROKE
                strokeWidth = px * 0.14f
                strokeJoin = Paint.Join.ROUND
                color = 0xD9000000.toInt()
            }
            layout(stroke).draw(c)
        }
        body.draw(c)
        return bmp
    }

    private fun videoEffects(
        p: Project, clip: Clip, startMs: Long, w: Int, h: Int, burnText: Boolean,
    ): List<Effect> {
        val fx = ArrayList<Effect>()
        // Geometry goes first. Media3 folds neighbouring matrix steps into a single GPU pass, so
        // turning, fitting to the canvas and zooming cost one draw, and every later step (colour,
        // text) works on the small canvas instead of the full 4K source frame.
        if (clip.rotation % 360 != 0 || clip.mirror) {
            fx += ScaleAndRotateTransformation.Builder()
                .setScale(if (clip.mirror) -1f else 1f, 1f)
                // Positive angles turn counter-clockwise; the UI turns clockwise.
                .setRotationDegrees(-clip.rotation.toFloat())
                .build()
        }
        fx += Presentation.createForWidthAndHeight(
            w, h,
            if (clip.fill) Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP else Presentation.LAYOUT_SCALE_TO_FIT,
        )
        if (clip.zoom != 1f) fx += Zoom(clip.zoom)
        Looks.matrix(clip.look)?.let { fx += LookMatrix(it) }
        if (clip.brightness != 0f) fx += Brightness(clip.brightness.coerceIn(-1f, 1f))
        if (clip.contrast != 0f) fx += Contrast(clip.contrast.coerceIn(-0.99f, 0.99f))
        if (clip.saturation != 0f) {
            fx += HslAdjustment.Builder().adjustSaturation(clip.saturation.coerceIn(-100f, 100f)).build()
        }
        val endMs = startMs + clip.durMs
        if (clip.fadeInMs > 0 || clip.fadeOutMs > 0) {
            fx += EdgeFade(startMs * 1000, endMs * 1000, clip.fadeInMs * 1000, clip.fadeOutMs * 1000)
        }
        if (burnText) {
            val overlays: List<TextureOverlay> = p.texts
                .filter { it.startMs < endMs && it.endMs > startMs && it.text.isNotBlank() }
                .map { TimedText(renderText(it, w, h), it.startMs * 1000, it.endMs * 1000, it.x, it.y) }
            if (overlays.isNotEmpty()) fx += OverlayEffect(overlays)
        }
        return fx
    }

    /** Where a clip's media is read from, and how long that file is. */
    class Source(val uri: Uri, val durationMs: Long, val mime: String)

    private fun original(c: Clip) = Source(Uri.parse(c.uri), c.sourceMs, c.mime)

    private fun clipItem(
        p: Project, clip: Clip, src: Source, startMs: Long, w: Int, h: Int, burnText: Boolean, maxFps: Int,
    ): EditedMediaItem {
        val media = MediaItem.Builder().setUri(src.uri).setMediaId(clip.id)
        if (src.mime.isNotBlank()) media.setMimeType(src.mime)
        val audio = ArrayList<AudioProcessor>()
        if (clip.isImage) {
            media.setImageDurationMs(clip.outMs - clip.inMs)
        } else {
            // Never ask for more than the file holds; the player waits forever for missing frames.
            val end = clip.outMs.coerceAtMost(src.durationMs)
            media.setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(clip.inMs.coerceAtMost((end - 1).coerceAtLeast(0)))
                    .setEndPositionMs(end)
                    .build(),
            )
            if (clip.volume != 1f) audio += GainProcessor(FixedGain(clip.volume))
        }
        val item = EditedMediaItem.Builder(media.build())
            .setEffects(Effects(audio, videoEffects(p, clip, startMs, w, h, burnText)))
        if (clip.isImage) {
            item.setDurationUs((clip.outMs - clip.inMs) * 1000).setFrameRate(maxFps)
        } else {
            item.setDurationUs(src.durationMs * 1000).setFrameRate(maxFps)
            if (clip.speed != 1f) item.setSpeed(FixedSpeed(clip.speed))
        }
        return item.build()
    }

    /**
     * Turns a project into a Media3 composition. The same function feeds the live preview and the
     * exporter, so what is seen while editing is what gets written to the file. Text is drawn by
     * the editor UI during preview ([burnText] = false) and burnt into frames on export.
     */
    fun compose(
        p: Project,
        shortSide: Int,
        burnText: Boolean,
        maxFps: Int = 30,
        hdrMode: Int = Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL,
        source: (Clip) -> Source = ::original,
    ): Composition {
        val (w, h) = p.canvas(shortSide)
        var at = 0L
        val items = p.clips.map { c ->
            val item = clipItem(p, c, source(c), at, w, h, burnText, maxFps)
            at += c.durMs
            item
        }
        val sequences = ArrayList<EditedMediaItemSequence>()
        // Asking for both track types makes the engine pad photos and silent clips with silence.
        sequences += EditedMediaItemSequence.withAudioAndVideoFrom(items)

        val total = p.totalMs
        p.music?.let { m ->
            val available = m.sourceMs - m.inMs
            if (available > 200 && m.volume > 0f && total > 0) {
                val loops = m.loop && available < total
                val endMs = if (loops) m.sourceMs else min(m.sourceMs, m.inMs + total)
                val media = MediaItem.Builder().setUri(Uri.parse(m.uri))
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(m.inMs)
                            .setEndPositionMs(endMs)
                            .build(),
                    ).build()
                val audio = ArrayList<AudioProcessor>()
                if (m.volume != 1f) audio += GainProcessor(FixedGain(m.volume))
                val item = EditedMediaItem.Builder(media)
                    .setDurationUs(m.sourceMs * 1000)
                    .setEffects(Effects(audio, emptyList()))
                    .build()
                sequences += EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO))
                    .addItem(item)
                    .setIsLooping(loops)
                    .build()
            }
        }
        return Composition.Builder(sequences)
            // Phones often shoot 4K in HDR. The output is always SDR H.264, so bring HDR footage
            // down to SDR on the GPU instead of asking for an HDR pipeline end to end.
            .setHdrMode(hdrMode)
            .build()
    }
}
