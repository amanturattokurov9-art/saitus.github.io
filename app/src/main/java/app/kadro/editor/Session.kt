@file:OptIn(UnstableApi::class)

package app.kadro.editor

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.CompositionPlayer
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface Pick {
    data object Nothing : Pick
    data class OfClip(val id: String) : Pick
    data class OfText(val id: String) : Pick
    data object OfMusic : Pick
}

sealed interface ExportState {
    data object Idle : ExportState
    data class Running(val percent: Int) : ExportState
    data class Done(val uri: Uri) : ExportState
    data class Failed(val reason: String) : ExportState
}

/** Everything that is alive while one project is open: the document, history, player, export. */
class Session(private val ctx: Context, initial: Project, private val drafts: Drafts) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    var project by mutableStateOf(initial)
        private set
    var pick by mutableStateOf<Pick>(Pick.Nothing)
    var positionMs by mutableLongStateOf(0L)
        private set
    var playing by mutableStateOf(false)
        private set
    var notice by mutableStateOf<String?>(null)
    var export by mutableStateOf<ExportState>(ExportState.Idle)
        private set
    var undoCount by mutableIntStateOf(0)
        private set
    var redoCount by mutableIntStateOf(0)
        private set
    /** Timeline zoom, in dp per second. */
    var dpPerSecond by mutableFloatStateOf(56f)

    val thumbs = Thumbs(ctx)

    private val undo = ArrayDeque<Project>()
    private val redo = ArrayDeque<Project>()
    private var liveBase: Project? = null
    private var saveJob: Job? = null
    private var transformer: Transformer? = null
    private var scrubbing = false
    private var lastRecovery = 0L

    val player: CompositionPlayer = CompositionPlayer.Builder(ctx).build().also { p ->
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    p.pause()
                    positionMs = project.totalMs
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // One quiet retry: most failures here are a decoder being slow to let go.
                val now = SystemClock.elapsedRealtime()
                if (now - lastRecovery > 5000) {
                    lastRecovery = now
                    scope.launch {
                        delay(200)
                        rebuild()
                    }
                } else {
                    notice = "Не удалось показать превью: ${error.errorCodeName}"
                }
            }
        })
    }

    init {
        rebuild()
        scope.launch {
            while (isActive) {
                if (playing) positionMs = player.currentPosition.coerceIn(0, project.totalMs)
                delay(33)
            }
        }
    }

    val selectedClip: Clip?
        get() = (pick as? Pick.OfClip)?.let { s -> project.clips.firstOrNull { it.id == s.id } }
    val selectedText: TextLayer?
        get() = (pick as? Pick.OfText)?.let { s -> project.texts.firstOrNull { it.id == s.id } }

    // ----- editing -----

    private fun mediaChanged(a: Project, b: Project) =
        a.clips != b.clips || a.music != b.music || a.ratio != b.ratio

    private fun counts() {
        undoCount = undo.size
        redoCount = redo.size
    }

    private fun saveSoon() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(600)
            val snapshot = project.copy(updatedAt = System.currentTimeMillis())
            withContext(Dispatchers.IO) { drafts.save(snapshot) }
        }
    }

    /** One undoable change. */
    fun edit(change: (Project) -> Project) {
        finishLive()
        val before = project
        val after = change(before)
        if (after == before) return
        undo.addLast(before)
        if (undo.size > 60) undo.removeFirst()
        redo.clear()
        project = after
        counts()
        if (mediaChanged(before, after)) rebuild()
        saveSoon()
    }

    /** A change that is still being dragged: shown at once, recorded when [finishLive] is called. */
    fun live(change: (Project) -> Project) {
        if (liveBase == null) liveBase = project
        project = change(project)
    }

    fun finishLive() {
        val before = liveBase ?: return
        liveBase = null
        if (before == project) return
        undo.addLast(before)
        if (undo.size > 60) undo.removeFirst()
        redo.clear()
        counts()
        if (mediaChanged(before, project)) rebuild()
        saveSoon()
    }

    fun undo() {
        finishLive()
        val prev = undo.removeLastOrNull() ?: return
        redo.addLast(project)
        swap(prev)
    }

    fun redo() {
        finishLive()
        val next = redo.removeLastOrNull() ?: return
        undo.addLast(project)
        swap(next)
    }

    private fun swap(to: Project) {
        val from = project
        project = to
        counts()
        positionMs = positionMs.coerceIn(0, to.totalMs)
        if (mediaChanged(from, to)) rebuild()
        saveSoon()
    }

    fun updateClip(id: String, live: Boolean = false, f: (Clip) -> Clip) {
        val change = { p: Project -> p.copy(clips = p.clips.map { if (it.id == id) f(it) else it }) }
        if (live) live(change) else edit(change)
    }

    fun updateText(id: String, live: Boolean = false, f: (TextLayer) -> TextLayer) {
        val change = { p: Project -> p.copy(texts = p.texts.map { if (it.id == id) f(it) else it }) }
        if (live) live(change) else edit(change)
    }

    fun updateMusic(live: Boolean = false, f: (Music) -> Music) {
        val change = { p: Project -> p.copy(music = p.music?.let(f)) }
        if (live) live(change) else edit(change)
    }

    fun addClips(clips: List<Clip>) {
        if (clips.isEmpty()) return
        val wasEmpty = project.clips.isEmpty()
        // New footage lands after the clip under the playhead.
        val at = if (wasEmpty) 0 else (project.locate(positionMs)?.first ?: project.clips.lastIndex) + 1
        edit { p ->
            p.copy(clips = p.clips.take(at) + clips + p.clips.drop(at))
        }
        if (!wasEmpty) seek(project.startOf(at))
        pick = Pick.OfClip(clips.first().id)
    }

    /** Cuts the clip under the playhead in two. */
    fun split(): Boolean {
        val (i, offset) = project.locate(positionMs) ?: return false
        val c = project.clips[i]
        if (offset < 100 || c.durMs - offset < 100) {
            notice = "Поставьте бегунок внутрь клипа — слишком близко к краю"
            return false
        }
        val cut = c.inMs + (offset * c.speed).toLong()
        val left = c.copy(outMs = cut, fadeOutMs = 0)
        val right = c.copy(id = newId(), inMs = cut, fadeInMs = 0)
        edit { p -> p.copy(clips = p.clips.take(i) + left + right + p.clips.drop(i + 1)) }
        pick = Pick.OfClip(right.id)
        return true
    }

    fun duplicate(id: String) {
        val i = project.clips.indexOfFirst { it.id == id }
        if (i < 0) return
        val copy = project.clips[i].copy(id = newId())
        edit { p -> p.copy(clips = p.clips.take(i + 1) + copy + p.clips.drop(i + 1)) }
        pick = Pick.OfClip(copy.id)
    }

    fun move(id: String, by: Int) {
        val i = project.clips.indexOfFirst { it.id == id }
        val j = i + by
        if (i < 0 || j !in project.clips.indices) return
        edit { p ->
            val list = p.clips.toMutableList()
            list.add(j, list.removeAt(i))
            p.copy(clips = list)
        }
        seek(project.startOf(j))
    }

    fun removeClip(id: String) {
        edit { p -> p.copy(clips = p.clips.filterNot { it.id == id }) }
        pick = Pick.Nothing
        positionMs = positionMs.coerceIn(0, project.totalMs)
    }

    fun addText(text: String) {
        val total = project.totalMs
        val start = positionMs.coerceIn(0, (total - 500).coerceAtLeast(0))
        val layer = TextLayer(text = text, startMs = start, endMs = (start + 3000).coerceAtMost(total.coerceAtLeast(500)))
        edit { p -> p.copy(texts = p.texts + layer) }
        pick = Pick.OfText(layer.id)
    }

    fun removeText(id: String) {
        edit { p -> p.copy(texts = p.texts.filterNot { it.id == id }) }
        pick = Pick.Nothing
    }

    // ----- playback -----

    private fun rebuild() {
        if (export is ExportState.Running) return
        if (project.clips.isEmpty()) {
            player.stop()
            positionMs = 0
            return
        }
        val at = positionMs.coerceIn(0, (project.totalMs - 1).coerceAtLeast(0))
        try {
            player.setComposition(Engine.compose(project, PREVIEW_SIDE, burnText = false), at)
            player.prepare()
        } catch (e: Exception) {
            notice = "Не удалось собрать превью: ${e.message}"
        }
    }

    fun togglePlay() {
        if (project.clips.isEmpty()) return
        if (player.isPlaying) {
            player.pause()
        } else {
            if (positionMs >= project.totalMs - 50) seek(0)
            if (player.playbackState == Player.STATE_IDLE) rebuild()
            player.play()
        }
    }

    fun pause() {
        player.pause()
    }

    fun seek(ms: Long) {
        val total = project.totalMs
        positionMs = ms.coerceIn(0, total)
        if (total > 0) player.seekTo(positionMs.coerceAtMost(total - 1))
    }

    /** Called while a finger drags the timeline. */
    fun scrub(ms: Long, active: Boolean) {
        if (active && !scrubbing) {
            scrubbing = true
            player.pause()
            runCatching { player.setScrubbingModeEnabled(true) }
        }
        seek(ms)
        if (!active && scrubbing) {
            scrubbing = false
            runCatching { player.setScrubbingModeEnabled(false) }
            player.pause()
        }
    }

    // ----- export -----

    fun startExport(shortSide: Int, fps: Int) {
        if (project.clips.isEmpty() || export is ExportState.Running) return
        finishLive()
        player.stop()
        export = ExportState.Running(0)
        val out = File(ctx.cacheDir, "export-${project.id}.mp4").apply { delete() }
        val composition = try {
            Engine.compose(project, shortSide, burnText = true, maxFps = fps)
        } catch (e: Exception) {
            export = ExportState.Failed(e.message ?: "ошибка сборки")
            return
        }
        val t = Transformer.Builder(ctx)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            // Slow phones can take a while between frames at 1080p; do not give up on them early.
            .setMaxDelayBetweenMuxerSamplesMs(120_000)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    transformer = null
                    scope.launch {
                        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                        val uri = withContext(Dispatchers.IO) {
                            Gallery.publish(ctx, out, "Kadro-$stamp.mp4").also { out.delete() }
                        }
                        export = if (uri != null) ExportState.Done(uri)
                        else ExportState.Failed("не удалось сохранить в галерею")
                    }
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException,
                ) {
                    transformer = null
                    out.delete()
                    export = ExportState.Failed(exportException.errorCodeName)
                }
            })
            .build()
        transformer = t
        try {
            t.start(composition, out.absolutePath)
        } catch (e: Exception) {
            transformer = null
            export = ExportState.Failed(e.message ?: "ошибка запуска")
            return
        }
        scope.launch {
            val holder = ProgressHolder()
            while (isActive && transformer === t) {
                if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    export = ExportState.Running(holder.progress)
                }
                delay(200)
            }
        }
    }

    fun cancelExport() {
        transformer?.cancel()
        transformer = null
        export = ExportState.Idle
        rebuild()
    }

    fun dismissExport() {
        if (export is ExportState.Running || export == ExportState.Idle) return
        export = ExportState.Idle
        rebuild()
    }

    fun rename(name: String) = edit { it.copy(name = name.ifBlank { "Без названия" }) }

    fun close() {
        finishLive()
        saveJob?.cancel()
        transformer?.cancel()
        val snapshot = project
        runCatching { drafts.save(snapshot) }
        scope.cancel()
        player.release()
        thumbs.release()
    }

    companion object {
        const val PREVIEW_SIDE = 540
    }
}
