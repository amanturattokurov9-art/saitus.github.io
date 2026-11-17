@file:OptIn(UnstableApi::class)

package app.kadro.editor

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import java.io.File
import java.security.MessageDigest
import java.util.Collections

/**
 * Light editing copies of heavy footage (4K, high frame rate, HDR).
 *
 * Phones usually have one or two hardware decoders that can handle 4K. An editor needs several at
 * once — the preview, the timeline frames, the next clip warming up — and when a vendor codec runs
 * out of them it can take the whole app down. So each heavy clip is converted once, one at a time,
 * into a 720p SDR copy. Editing reads the copy; export always reads the original.
 */
object Proxies {
    private const val TAG = "Kadro"
    private const val SHORT_SIDE = 720

    private lateinit var app: Context
    private val dir by lazy { File(app.filesDir, "proxies").apply { mkdirs() } }
    private val main = Handler(Looper.getMainLooper())

    /** Keys of the copies on disk. Read from any thread. */
    private val done: MutableSet<String> = Collections.synchronizedSet(HashSet())
    private val durations: MutableMap<String, Long> = Collections.synchronizedMap(HashMap())

    /** Percent for the clip being converted right now, keyed by the original URI. */
    val progress = mutableStateMapOf<String, Int>()

    /** Originals that could not be converted; they are edited directly instead. */
    private val failed = mutableStateMapOf<String, Boolean>()

    /** Bumped whenever a copy appears or fails, so readers recompose and the preview rebuilds. */
    var version by mutableIntStateOf(0)
        private set

    private val queue = ArrayDeque<String>()
    private var running: Transformer? = null

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        dir.listFiles { f -> f.extension == "mp4" }?.forEach { done += it.nameWithoutExtension }
        // A conversion cut short by the app being closed leaves a part file; it is useless.
        dir.listFiles { f -> f.extension == "part" }?.forEach { it.delete() }
    }

    private fun key(uri: String): String {
        val d = MessageDigest.getInstance("SHA-1").digest(uri.toByteArray())
        return d.joinToString("") { "%02x".format(it) }.take(24)
    }

    private fun file(uri: String) = File(dir, key(uri) + ".mp4")

    private fun isReady(uri: String) = key(uri) in done

    /**
     * Where the editor should read [c] from: the light copy, the original, or null while the copy
     * is still being made and the original is too heavy to touch.
     */
    fun source(c: Clip): Uri? {
        @Suppress("UNUSED_VARIABLE") val subscribe = version
        return when {
            c.isImage || !c.heavy -> Uri.parse(c.uri)
            isReady(c.uri) -> Uri.fromFile(file(c.uri))
            failed[c.uri] == true -> Uri.parse(c.uri)
            else -> null
        }
    }

    fun usesCopy(c: Clip) = !c.isImage && c.heavy && isReady(c.uri)

    fun hasFailed(c: Clip) = !c.isImage && c.heavy && failed[c.uri] == true

    /** Length of the copy; a re-encode can come out a few milliseconds shorter than the source. */
    fun copyDurationMs(c: Clip): Long {
        val k = key(c.uri)
        durations[k]?.let { return it }
        val measured = probeDuration(file(c.uri)) ?: c.sourceMs
        durations[k] = measured
        return measured
    }

    private fun probeDuration(f: File): Long? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(f.absolutePath)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } catch (e: Exception) {
            null
        } finally {
            runCatching { r.release() }
        }
    }

    /** Clips still waiting for their copy. */
    fun waiting(clips: List<Clip>): List<Clip> =
        clips.filter { source(it) == null }.distinctBy { it.uri }

    /** Queues a copy for every heavy clip that does not have one yet. */
    fun ensure(clips: List<Clip>) {
        clips.filter { !it.isImage && it.heavy }.map { it.uri }.distinct().forEach { uri ->
            if (!isReady(uri) && failed[uri] != true && uri !in queue && uri !in progress) {
                queue.addLast(uri)
            }
        }
        pump()
    }

    private fun pump() {
        if (running != null) return
        val uri = queue.removeFirstOrNull() ?: return
        progress[uri] = 0
        build(uri, 0)
    }

    // Tone-mapping HDR on the GPU looks best but not every phone supports it; then try the
    // decoder's own tone-mapping, and as a last resort read the pixels as if they were SDR.
    private val hdrModes = intArrayOf(
        Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL,
        Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_MEDIACODEC,
        Composition.HDR_MODE_EXPERIMENTAL_FORCE_INTERPRET_HDR_AS_SDR,
    )

    private fun build(uri: String, attempt: Int) {
        val part = File(dir, key(uri) + ".part").apply { delete() }
        val item = EditedMediaItem.Builder(MediaItem.fromUri(uri))
            .setFrameRate(30)
            .setEffects(Effects(emptyList(), listOf(Presentation.createForShortSide(SHORT_SIDE))))
            .build()
        val composition = Composition.Builder(EditedMediaItemSequence.withAudioAndVideoFrom(listOf(item)))
            .setHdrMode(hdrModes[attempt])
            .build()
        val t = Transformer.Builder(app)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setMaxDelayBetweenMuxerSamplesMs(120_000)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    running = null
                    val target = file(uri)
                    if (part.renameTo(target)) {
                        done += key(uri)
                        durations.remove(key(uri))
                    } else {
                        failed[uri] = true
                    }
                    finish(uri)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException,
                ) {
                    running = null
                    part.delete()
                    Log.w(TAG, "proxy attempt $attempt failed for $uri", exportException)
                    if (attempt + 1 < hdrModes.size) {
                        // Give the failed run's codecs a moment to be released before the next try.
                        main.postDelayed({ build(uri, attempt + 1) }, 800)
                    } else {
                        failed[uri] = true
                        finish(uri)
                    }
                }
            })
            .build()
        running = t
        CrashLog.breadcrumb = "подготовка 4K-клипа (попытка ${attempt + 1})"
        try {
            t.start(composition, part.absolutePath)
        } catch (e: Exception) {
            running = null
            Log.w(TAG, "proxy start failed for $uri", e)
            failed[uri] = true
            finish(uri)
            return
        }
        val holder = ProgressHolder()
        val tick = object : Runnable {
            override fun run() {
                if (running !== t) return
                if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    progress[uri] = holder.progress
                }
                main.postDelayed(this, 300)
            }
        }
        main.postDelayed(tick, 300)
    }

    private fun finish(uri: String) {
        progress.remove(uri)
        version++
        pump()
    }

    /** Deletes copies that no draft refers to any more. */
    fun cleanup(keepUris: Collection<String>) {
        val keep = keepUris.map(::key).toSet()
        dir.listFiles { f -> f.extension == "mp4" }?.forEach { f ->
            if (f.nameWithoutExtension !in keep && running == null) {
                f.delete()
                done -= f.nameWithoutExtension
            }
        }
    }
}
