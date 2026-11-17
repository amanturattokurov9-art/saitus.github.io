package app.kadro.editor

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

object MediaProbe {

    private fun keep(ctx: Context, uri: Uri) {
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun displayName(ctx: Context, uri: Uri): String? = runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull()

    /** Reads what the editor needs to know about a picked file. Null if it cannot be opened. */
    fun clip(ctx: Context, uri: Uri): Clip? {
        keep(ctx, uri)
        val mime = ctx.contentResolver.getType(uri)
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                uri.toString().substringAfterLast('.', "").lowercase(),
            ).orEmpty()
        if (mime.startsWith("image/")) {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            runCatching {
                ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            }
            if (opts.outWidth <= 0) return null
            return Clip(
                uri = uri.toString(), mime = mime, isImage = true, sourceMs = 30_000,
                hasAudio = false, width = opts.outWidth, height = opts.outHeight,
                inMs = 0, outMs = 3_000,
            )
        }
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, uri)
            fun num(key: Int) = r.extractMetadata(key)?.toLongOrNull()
            val dur = num(MediaMetadataRetriever.METADATA_KEY_DURATION) ?: return null
            if (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == null) return null
            var w = (num(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH) ?: 1080).toInt()
            var h = (num(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT) ?: 1920).toInt()
            val rot = (num(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) ?: 0).toInt()
            if (rot % 180 != 0) { val t = w; w = h; h = t }
            val frames = num(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
            val fps = if (frames != null && dur > 0) frames * 1000f / dur else 30f
            // 6 = SMPTE ST 2084 (HDR10), 7 = HLG: what phones record when HDR video is on.
            val transfer = if (Build.VERSION.SDK_INT >= 30) num(MediaMetadataRetriever.METADATA_KEY_COLOR_TRANSFER) else null
            val hdr = transfer == 6L || transfer == 7L
            Clip(
                uri = uri.toString(), mime = mime.ifBlank { "video/mp4" }, isImage = false,
                sourceMs = dur,
                hasAudio = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) != null,
                width = w, height = h, inMs = 0, outMs = dur,
                heavy = maxOf(w, h) > HEAVY_SIDE || fps > 61f || hdr,
            )
        } catch (e: Exception) {
            Log.w("Kadro", "cannot read $uri", e)
            null
        } finally {
            runCatching { r.release() }
        }
    }

    fun music(ctx: Context, uri: Uri): Music? {
        keep(ctx, uri)
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, uri)
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: return null
            if (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == null) return null
            val title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?: displayName(ctx, uri)?.substringBeforeLast('.')
                ?: "Музыка"
            Music(uri = uri.toString(), title = title, sourceMs = dur)
        } catch (e: Exception) {
            Log.w("Kadro", "cannot read $uri", e)
            null
        } finally {
            runCatching { r.release() }
        }
    }
}

/** Small frame previews for the timeline, decoded off the main thread and cached. */
class Thumbs(private val ctx: Context) {
    private val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + worker)
    private val pending = HashSet<String>()
    /** Frames that could not be decoded; asking again on every redraw would only burn CPU. */
    private val failed = HashSet<String>()
    // Each open retriever holds native resources; keep only the few most recently used.
    private val retrievers = object : LinkedHashMap<String, MediaMetadataRetriever>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MediaMetadataRetriever>): Boolean {
            if (size <= 3) return false
            runCatching { eldest.value.release() }
            return true
        }
    }

    /** Compose reads this map; a new entry redraws the tiles that were waiting for it. */
    val ready = mutableStateMapOf<String, Bitmap>()

    fun get(clip: Clip, sourceMs: Long): Bitmap? {
        val bucket = if (clip.isImage) 0 else sourceMs / 500 * 500
        val key = "${clip.uri}@$bucket"
        ready[key]?.let { return it }
        // Heavy footage is never decoded here; its frames come from the light copy once it exists.
        val source = Proxies.source(clip) ?: return null
        if (!clip.isImage && clip.heavy && !Proxies.usesCopy(clip)) return null
        if (key in failed) return null
        if (pending.add(key)) {
            scope.launch {
                val bmp = runCatching { decode(clip, source, bucket) }.getOrNull()
                withContext(Dispatchers.Main) {
                    pending.remove(key)
                    if (bmp == null) failed += key
                    if (bmp != null) {
                        if (ready.size > 300) ready.clear()
                        ready[key] = bmp
                    }
                }
            }
        }
        return null
    }

    private fun decode(clip: Clip, uri: Uri, ms: Long): Bitmap? {
        if (clip.isImage) {
            val src = ImageDecoder.createSource(ctx.contentResolver, uri)
            return ImageDecoder.decodeBitmap(src) { d, info, _ ->
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val s = 160f / maxOf(info.size.width, info.size.height)
                if (s < 1f) d.setTargetSize(
                    (info.size.width * s).toInt().coerceAtLeast(1),
                    (info.size.height * s).toInt().coerceAtLeast(1),
                )
            }
        }
        val r = retrievers.getOrPut(uri.toString()) {
            MediaMetadataRetriever().apply { setDataSource(ctx, uri) }
        }
        val land = clip.width >= clip.height
        return r.getScaledFrameAtTime(
            ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
            if (land) 160 else 90, if (land) 90 else 160,
        )
    }

    fun release() {
        scope.launch {
            retrievers.values.forEach { runCatching { it.release() } }
            retrievers.clear()
            worker.close()
        }
    }
}

/** Drafts live as one JSON file each in the app's private storage. */
class Drafts(ctx: Context) {
    private val dir = File(ctx.filesDir, "drafts").apply { mkdirs() }

    fun list(): List<Project> = dir.listFiles { f -> f.extension == "json" }.orEmpty()
        .mapNotNull { f -> runCatching { projectFromJson(JSONObject(f.readText())) }.getOrNull() }
        .sortedByDescending { it.updatedAt }

    fun save(p: Project) {
        val tmp = File(dir, "${p.id}.tmp")
        tmp.writeText(p.toJson().toString())
        tmp.renameTo(File(dir, "${p.id}.json"))
    }

    fun delete(id: String) {
        File(dir, "$id.json").delete()
    }
}

object Gallery {
    /** Copies a finished file into Movies/Kadro and returns its content URI. */
    fun publish(ctx: Context, file: File, name: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Kadro")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: return null
        return try {
            resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    }
}
