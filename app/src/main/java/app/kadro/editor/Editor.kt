package app.kadro.editor

import android.content.Intent
import android.net.Uri
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.CropRotate
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Gradient
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material.icons.rounded.ZoomOut
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
fun EditorScreen(s: Session, onExit: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var panel by remember { mutableStateOf<Panel?>(null) }
    var exportOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            busy = true
            val clips = withContext(Dispatchers.IO) { uris.mapNotNull { MediaProbe.clip(ctx, it) } }
            busy = false
            if (clips.size < uris.size) s.notice = "Часть файлов не удалось открыть"
            s.addClips(clips)
        }
    }
    val pickMusic = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            val m = withContext(Dispatchers.IO) { MediaProbe.music(ctx, uri) }
            busy = false
            if (m == null) s.notice = "Этот файл не похож на музыку"
            else {
                s.edit { it.copy(music = m) }
                s.pick = Pick.OfMusic
            }
        }
    }
    val addMedia = {
        s.pause()
        runCatching { pickMedia.launch(arrayOf("video/*", "image/*")) }
            .onFailure { s.notice = "На устройстве нет приложения «Файлы» для выбора медиа" }
        Unit
    }
    val addMusic = {
        s.pause()
        runCatching { pickMusic.launch(arrayOf("audio/*")) }
            .onFailure { s.notice = "На устройстве нет приложения «Файлы» для выбора музыки" }
        Unit
    }

    // A panel only makes sense while the thing it edits is still selected.
    val open = panel?.takeIf {
        when (it) {
            Panel.Format -> true
            Panel.Text, Panel.TextTime -> s.selectedText != null
            Panel.Music -> s.pick == Pick.OfMusic && s.project.music != null
            else -> s.selectedClip != null
        }
    }

    BackHandler {
        when {
            exportOpen && s.export !is ExportState.Running -> { exportOpen = false; s.dismissExport() }
            exportOpen -> Unit
            open != null -> { s.finishLive(); panel = null }
            s.pick != Pick.Nothing -> s.pick = Pick.Nothing
            else -> onExit()
        }
    }

    LaunchedEffect(s.notice) {
        if (s.notice != null) {
            delay(3200)
            s.notice = null
        }
    }

    Box(Modifier.fillMaxSize().background(Ink.Base).systemBarsPadding().imePadding()) {
        Column(Modifier.fillMaxSize()) {
            // Header
            Row(
                Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoundIcon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад", onClick = onExit)
                Text(
                    s.project.name,
                    color = Ink.Paper, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { renaming = true }
                        .padding(horizontal = 6.dp, vertical = 8.dp),
                )
                RoundIcon(Icons.AutoMirrored.Rounded.Undo, "Отменить", enabled = s.undoCount > 0) { s.undo() }
                RoundIcon(Icons.AutoMirrored.Rounded.Redo, "Вернуть", enabled = s.redoCount > 0) { s.redo() }
                Spacer(Modifier.width(4.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (s.project.clips.isEmpty()) Ink.Raised else Ink.Ember)
                        .clickable(enabled = s.project.clips.isNotEmpty()) {
                            s.pause(); s.finishLive(); panel = null; exportOpen = true
                        }
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                ) {
                    Text(
                        "Экспорт",
                        color = if (s.project.clips.isEmpty()) Ink.Dim else Ink.Base,
                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.width(8.dp))
            }

            Preview(s, Modifier.weight(1f), onAdd = addMedia, onEditText = { panel = Panel.Text })

            // Transport
            Row(
                Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${clock(s.positionMs)} / ${clock(s.project.totalMs)}",
                    color = Ink.Dim, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Ink.Paper)
                        .clickable(enabled = s.project.clips.isNotEmpty()) { s.togglePlay() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (s.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        if (s.playing) "Пауза" else "Пуск",
                        tint = Ink.Base, modifier = Modifier.size(24.dp),
                    )
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                    RoundIcon(Icons.Rounded.ZoomOut, "Мельче", enabled = s.dpPerSecond > 12f) {
                        s.dpPerSecond = (s.dpPerSecond / 1.5f).coerceAtLeast(10f)
                    }
                    RoundIcon(Icons.Rounded.ZoomIn, "Крупнее", enabled = s.dpPerSecond < 240f) {
                        s.dpPerSecond = (s.dpPerSecond * 1.5f).coerceAtMost(260f)
                    }
                }
            }

            // Panels take the timeline's place so the picture stays large while adjusting.
            if (open == null || open == Panel.TextTime) Timeline(s)

            // Dock: either the open panel or the row of tools for whatever is selected.
            when (open) {
                null -> Dock(s, addMedia, addMusic) { panel = it }
                else -> PanelShell(open.title, onClose = { s.finishLive(); panel = null }) {
                    val c = s.selectedClip
                    val t = s.selectedText
                    when (open) {
                        Panel.Speed -> c?.let { SpeedPanel(s, it) }
                        Panel.Volume -> c?.let { VolumePanel(s, it) }
                        Panel.Look -> c?.let { LookPanel(s, it) }
                        Panel.Adjust -> c?.let { AdjustPanel(s, it) }
                        Panel.Frame -> c?.let { FramePanel(s, it) }
                        Panel.Fade -> c?.let { FadePanel(s, it) }
                        Panel.Format -> FormatPanel(s)
                        Panel.Text -> t?.let { TextPanel(s, it) }
                        Panel.TextTime -> t?.let { TextTimePanel(s, it) }
                        Panel.Music -> s.project.music?.let { MusicPanel(s, it, addMusic) }
                    }
                }
            }
        }

        s.notice?.let { msg ->
            Text(
                msg, color = Ink.Base, fontSize = 13.sp, textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 64.dp, start = 24.dp, end = 24.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Ink.Paper)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }

        if (busy) {
            Box(Modifier.fillMaxSize().background(Color(0x99000000)), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Ink.Ember)
            }
        }

        if (renaming) RenameSheet(s.project.name, onDone = { name -> name?.let(s::rename); renaming = false })

        if (exportOpen) ExportSheet(s) { exportOpen = false; s.dismissExport() }
    }
}

@Composable
private fun Preview(s: Session, modifier: Modifier, onAdd: () -> Unit, onEditText: () -> Unit) {
    val density = LocalDensity.current
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .background(Color(0xFF08090B))
            .padding(vertical = 6.dp)
            .pointerInput(Unit) { detectTapGestures { s.pick = Pick.Nothing } },
        contentAlignment = Alignment.Center,
    ) {
        if (s.project.clips.isEmpty()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(Ink.Ember)
                        .clickable(onClick = onAdd),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Add, "Добавить", tint = Ink.Base, modifier = Modifier.size(34.dp)) }
                Spacer(Modifier.height(14.dp))
                Text("Добавьте видео или фото", color = Ink.Paper, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Text("Можно выбрать сразу несколько", color = Ink.Dim, fontSize = 13.sp)
            }
            return@BoxWithConstraints
        }
        val (cw, ch) = s.project.canvas(Session.PREVIEW_SIDE)
        val ar = cw.toFloat() / ch
        val maxW = constraints.maxWidth.toFloat()
        val maxH = constraints.maxHeight.toFloat()
        val boxW = if (maxW / maxH > ar) (maxH * ar).roundToInt() else maxW.roundToInt()
        val boxH = if (maxW / maxH > ar) maxH.roundToInt() else (maxW / ar).roundToInt()

        Box(
            Modifier
                .size(with(density) { boxW.toDp() }, with(density) { boxH.toDp() })
                .background(Color.Black)
                .border(1.dp, Ink.Line),
        ) {
            if (s.export !is ExportState.Running) {
                AndroidView(
                    factory = { c -> SurfaceView(c).also { s.player.setVideoSurfaceView(it) } },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // Text lives above the video while editing so it can be dragged without re-rendering.
            val pickedId = (s.pick as? Pick.OfText)?.id
            s.project.texts.forEach { t ->
                val visible = s.positionMs >= t.startMs && s.positionMs < t.endMs
                if ((visible || t.id == pickedId) && t.text.isNotBlank()) key(t.id) {
                    val bmp = remember(t.text, t.size, t.color, t.plate, t.bold, t.outline, boxW, boxH) {
                        Engine.renderText(t, boxW, boxH).asImageBitmap()
                    }
                    val picked = t.id == pickedId
                    Image(
                        bmp, null,
                        alpha = if (visible) 1f else 0.4f,
                        modifier = Modifier
                            .offset {
                                IntOffset(
                                    ((t.x + 1f) / 2f * boxW - bmp.width / 2f).roundToInt(),
                                    ((1f - t.y) / 2f * boxH - bmp.height / 2f).roundToInt(),
                                )
                            }
                            .size(with(density) { bmp.width.toDp() }, with(density) { bmp.height.toDp() })
                            .then(
                                if (picked) Modifier.border(1.5.dp, Ink.Mint, RoundedCornerShape(4.dp)) else Modifier,
                            )
                            .pointerInput(t.id) {
                                detectTapGestures(
                                    onDoubleTap = { s.pick = Pick.OfText(t.id); onEditText() },
                                    onTap = { s.pick = Pick.OfText(t.id) },
                                )
                            }
                            .pointerInput(t.id, boxW, boxH) {
                                detectDragGestures(
                                    onDragStart = { s.pick = Pick.OfText(t.id) },
                                    onDragEnd = { s.finishLive() },
                                    onDragCancel = { s.finishLive() },
                                ) { change, d ->
                                    change.consume()
                                    s.updateText(t.id, live = true) {
                                        it.copy(
                                            x = (it.x + d.x / boxW * 2f).coerceIn(-1f, 1f),
                                            y = (it.y - d.y / boxH * 2f).coerceIn(-1f, 1f),
                                        )
                                    }
                                }
                            },
                    )
                }
            }
        }
    }
}

@Composable
private fun Dock(s: Session, addMedia: () -> Unit, addMusic: () -> Unit, openPanel: (Panel) -> Unit) {
    val clip = s.selectedClip
    val text = s.selectedText
    val hasClips = s.project.clips.isNotEmpty()
    Row(
        Modifier
            .fillMaxWidth()
            .background(Ink.Panel)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 6.dp),
    ) {
        when {
            clip != null -> {
                val i = s.project.clips.indexOfFirst { it.id == clip.id }
                Tool(Icons.Rounded.ContentCut, "Разрезать", tint = Ink.Ember) { s.split() }
                if (!clip.isImage) {
                    Tool(Icons.Rounded.Speed, "Скорость") { openPanel(Panel.Speed) }
                    Tool(Icons.AutoMirrored.Rounded.VolumeUp, "Громкость") { openPanel(Panel.Volume) }
                }
                Tool(Icons.Rounded.Palette, "Фильтры") { openPanel(Panel.Look) }
                Tool(Icons.Rounded.Tune, "Коррекция") { openPanel(Panel.Adjust) }
                Tool(Icons.Rounded.CropRotate, "Кадр") { openPanel(Panel.Frame) }
                Tool(Icons.Rounded.Gradient, "Переходы") { openPanel(Panel.Fade) }
                Tool(Icons.Rounded.ContentCopy, "Дубль") { s.duplicate(clip.id) }
                Tool(Icons.AutoMirrored.Rounded.ArrowBack, "Раньше", enabled = i > 0) { s.move(clip.id, -1) }
                Tool(Icons.AutoMirrored.Rounded.ArrowForward, "Позже", enabled = i < s.project.clips.lastIndex) { s.move(clip.id, 1) }
                Tool(Icons.Rounded.DeleteOutline, "Удалить") { s.removeClip(clip.id) }
            }
            text != null -> {
                Tool(Icons.Rounded.Edit, "Изменить", tint = Ink.Mint) { openPanel(Panel.Text) }
                Tool(Icons.Rounded.Schedule, "Время") { openPanel(Panel.TextTime) }
                Tool(Icons.Rounded.ContentCopy, "Дубль") {
                    val copy = text.copy(id = newId(), y = (text.y - 0.2f).coerceAtLeast(-1f))
                    s.edit { it.copy(texts = it.texts + copy) }
                    s.pick = Pick.OfText(copy.id)
                }
                Tool(Icons.Rounded.DeleteOutline, "Удалить") { s.removeText(text.id) }
            }
            s.pick == Pick.OfMusic && s.project.music != null -> {
                Tool(Icons.Rounded.Tune, "Настроить", tint = Ink.Iris) { openPanel(Panel.Music) }
                Tool(Icons.Rounded.MusicNote, "Заменить", onClick = addMusic)
                Tool(Icons.Rounded.DeleteOutline, "Удалить") {
                    s.edit { it.copy(music = null) }
                    s.pick = Pick.Nothing
                }
            }
            else -> {
                Tool(Icons.Rounded.Add, "Медиа", tint = Ink.Ember, onClick = addMedia)
                Tool(Icons.Rounded.ContentCut, "Разрезать", enabled = hasClips) { s.split() }
                Tool(Icons.Rounded.TextFields, "Текст", enabled = hasClips) {
                    s.pause()
                    s.addText("Ваш текст")
                    openPanel(Panel.Text)
                }
                Tool(Icons.Rounded.MusicNote, "Музыка", enabled = hasClips) {
                    if (s.project.music == null) addMusic() else { s.pick = Pick.OfMusic; openPanel(Panel.Music) }
                }
                Tool(Icons.Rounded.AspectRatio, "Формат") { openPanel(Panel.Format) }
            }
        }
    }
}

@Composable
private fun Scrim(onDismiss: (() -> Unit)?, content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xD9000000))
            .pointerInput(onDismiss) { detectTapGestures { onDismiss?.invoke() } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(24.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Ink.Panel)
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(20.dp),
        ) { content() }
    }
}

@Composable
private fun RenameSheet(current: String, onDone: (String?) -> Unit) {
    var name by remember { mutableStateOf(current) }
    Scrim(onDismiss = { onDone(null) }) {
        Text("Название проекта", color = Ink.Paper, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        BasicTextField(
            value = name, onValueChange = { name = it.take(40) }, singleLine = true,
            textStyle = TextStyle(color = Ink.Paper, fontSize = 16.sp),
            cursorBrush = SolidColor(Ink.Ember),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Ink.Raised)
                .padding(horizontal = 12.dp, vertical = 12.dp),
        )
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Chip("Отмена", false) { onDone(null) }
            Spacer(Modifier.width(8.dp))
            Chip("Сохранить", true) { onDone(name.trim()) }
        }
    }
}

@Composable
private fun ExportSheet(s: Session, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val view = LocalView.current
    var side by remember { mutableIntStateOf(1080) }
    var fps by remember { mutableIntStateOf(30) }
    val state = s.export

    DisposableEffect(state is ExportState.Running) {
        view.keepScreenOn = state is ExportState.Running
        onDispose { view.keepScreenOn = false }
    }

    Scrim(onDismiss = if (state is ExportState.Running) null else onClose) {
        when (state) {
            ExportState.Idle -> {
                Text("Экспорт видео", color = Ink.Paper, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(16.dp))
                Text("Качество", color = Ink.Dim, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(480 to "480p", 720 to "720p HD", 1080 to "1080p").forEach { (v, l) ->
                        Chip(l, side == v) { side = v }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text("Кадров в секунду, не больше", color = Ink.Dim, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(24, 30, 60).forEach { v -> Chip("$v", fps == v) { fps = v } }
                }
                Spacer(Modifier.height(16.dp))
                val (w, h) = s.project.canvas(side)
                Text(
                    "$w × $h · ${clock(s.project.totalMs)} · MP4 (H.264 + AAC)\nБез водяных знаков. Файл появится в галерее, в папке Movies/Kadro.",
                    color = Ink.Dim, fontSize = 12.sp, lineHeight = 17.sp,
                )
                Spacer(Modifier.height(20.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Ink.Ember)
                        .clickable { s.startExport(side, fps) }
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("Сохранить видео", color = Ink.Base, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
            }
            is ExportState.Running -> {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            progress = { state.percent / 100f },
                            color = Ink.Ember, trackColor = Ink.Line, strokeWidth = 6.dp,
                            modifier = Modifier.size(120.dp),
                        )
                        Text("${state.percent}%", color = Ink.Paper, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("Собираем ролик…", color = Ink.Paper, fontSize = 15.sp)
                    Text("Не сворачивайте приложение", color = Ink.Dim, fontSize = 12.sp)
                    Spacer(Modifier.height(16.dp))
                    Chip("Отменить", false) { s.cancelExport() }
                }
            }
            is ExportState.Done -> {
                Text("Готово", color = Ink.Paper, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text("Видео сохранено в галерею: Movies/Kadro", color = Ink.Dim, fontSize = 13.sp)
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Смотреть", true) { open(ctx, state.uri, Intent.ACTION_VIEW) }
                    Chip("Поделиться", false) { open(ctx, state.uri, Intent.ACTION_SEND) }
                }
                Spacer(Modifier.height(8.dp))
                Chip("Вернуться к монтажу", false, onClick = onClose)
            }
            is ExportState.Failed -> {
                Text("Не получилось", color = Ink.Paper, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Причина: ${state.reason}. Попробуйте качество пониже — не каждый телефон кодирует 1080p.",
                    color = Ink.Dim, fontSize = 13.sp,
                )
                Spacer(Modifier.height(18.dp))
                Chip("Понятно", true, onClick = onClose)
            }
        }
    }
}

private fun open(ctx: android.content.Context, uri: Uri, action: String) {
    val intent = Intent(action).apply {
        if (action == Intent.ACTION_SEND) {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
        } else {
            setDataAndType(uri, "video/mp4")
        }
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { ctx.startActivity(Intent.createChooser(intent, null)) }
}
