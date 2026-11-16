package app.kadro.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

private val RulerH = 20.dp
private val TextLaneH = 24.dp
private val ClipH = 58.dp
private val MusicH = 24.dp
private val Gap = 6.dp

/** Puts overlapping text layers on separate lanes (at most three). */
private fun lanes(texts: List<TextLayer>): Map<String, Int> {
    val ends = LongArray(3)
    val out = HashMap<String, Int>()
    texts.sortedBy { it.startMs }.forEach { t ->
        val lane = (0 until 3).firstOrNull { ends[it] <= t.startMs } ?: 2
        ends[lane] = t.endMs
        out[t.id] = lane
    }
    return out
}

fun timelineHeight(p: Project): Dp {
    val textLanes = if (p.texts.isEmpty()) 0 else (lanes(p.texts).values.maxOrNull() ?: 0) + 1
    return RulerH + Gap + TextLaneH * textLanes + (if (textLanes > 0) Gap else 0.dp) + ClipH + Gap + MusicH + Gap
}

/**
 * The strip of footage. The playhead is pinned to the middle of the screen and the film slides
 * underneath it, so dragging anywhere scrubs through the project.
 */
@Composable
fun Timeline(s: Session, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val pxPerMs = with(density) { s.dpPerSecond.dp.toPx() } / 1000f
    val p = s.project
    val laneOf = remember(p.texts) { lanes(p.texts) }
    val textLanes = if (p.texts.isEmpty()) 0 else (laneOf.values.maxOrNull() ?: 0) + 1
    val carry = remember { floatArrayOf(0f) }

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(timelineHeight(p))
            .background(Ink.Panel)
            .clipToBounds()
            .pointerInput(Unit) { detectTapGestures { s.pick = Pick.Nothing } }
            .pointerInput(pxPerMs) {
                detectHorizontalDragGestures(
                    onDragStart = { carry[0] = 0f },
                    onDragEnd = { s.scrub(s.positionMs, false) },
                    onDragCancel = { s.scrub(s.positionMs, false) },
                ) { change, dx ->
                    change.consume()
                    carry[0] += -dx / pxPerMs
                    val whole = carry[0].toLong()
                    if (whole != 0L) {
                        carry[0] -= whole
                        s.scrub(s.positionMs + whole, true)
                    }
                }
            },
    ) {
        val wPx = constraints.maxWidth.toFloat()
        val half = wPx / 2f
        val pos = s.positionMs
        fun xOf(t: Long) = half + (t - pos) * pxPerMs

        val textTop = RulerH + Gap
        val clipTop = textTop + TextLaneH * textLanes + (if (textLanes > 0) Gap else 0.dp)
        val musicTop = clipTop + ClipH + Gap

        Ruler(pos, pxPerMs, p.totalMs, Modifier.fillMaxWidth().height(RulerH))

        // Text layers
        p.texts.forEach { t ->
            val x = xOf(t.startMs)
            val w = (t.endMs - t.startMs) * pxPerMs
            if (x + w > 0 && x < wPx) key(t.id) {
                val picked = s.pick == Pick.OfText(t.id)
                Box(
                    Modifier
                        .offset { IntOffset(x.roundToInt(), (textTop + TextLaneH * (laneOf[t.id] ?: 0)).roundToPx()) }
                        .width(with(density) { w.toDp() })
                        .height(TextLaneH - 3.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (picked) Ink.Mint else Ink.Mint.copy(alpha = 0.28f))
                        .pointerInput(t.id) { detectTapGestures { s.pick = Pick.OfText(t.id) } }
                        .padding(horizontal = 6.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        t.text.replace('\n', ' '),
                        color = if (picked) Ink.Base else Ink.Paper,
                        fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Clip,
                    )
                }
            }
        }

        // Footage
        var start = 0L
        p.clips.forEach { c ->
            val x = xOf(start)
            val w = c.durMs * pxPerMs
            start += c.durMs
            if (x + w > -80 && x < wPx + 80) key(c.id) {
                ClipTile(s, c, x, w, wPx, pxPerMs, clipTop)
            }
        }

        // Music
        p.music?.let { m ->
            val x = xOf(0)
            val w = p.totalMs * pxPerMs
            val picked = s.pick == Pick.OfMusic
            Row(
                Modifier
                    .offset { IntOffset(x.roundToInt(), musicTop.roundToPx()) }
                    .width(with(density) { w.coerceAtLeast(1f).toDp() })
                    .height(MusicH)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (picked) Ink.Iris else Ink.Iris.copy(alpha = 0.3f))
                    .pointerInput(Unit) { detectTapGestures { s.pick = Pick.OfMusic } }
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Keep the title in view while the bar slides under the screen edge.
                val inset = with(density) { (-x).coerceAtLeast(0f).toDp() }
                Icon(
                    Icons.Rounded.MusicNote, null,
                    tint = if (picked) Ink.Base else Ink.Paper,
                    modifier = Modifier.padding(start = inset).size(14.dp),
                )
                Text(
                    m.title, color = if (picked) Ink.Base else Ink.Paper, fontSize = 11.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }

        // Playhead
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = RulerH - 4.dp)
                .width(2.dp)
                .fillMaxHeight()
                .background(Ink.Paper),
        )
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = RulerH - 8.dp)
                .size(width = 10.dp, height = 6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Ink.Paper),
        )
    }
}

@Composable
private fun Ruler(pos: Long, pxPerMs: Float, totalMs: Long, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val style = remember { TextStyle(color = Ink.Dim, fontSize = 9.sp) }
    Canvas(modifier) {
        val half = size.width / 2f
        val steps = longArrayOf(500, 1000, 2000, 5000, 10000, 30000, 60000)
        val step = steps.firstOrNull { it * pxPerMs >= 64.dp.toPx() } ?: 120000
        val from = ((pos - half / pxPerMs).toLong().coerceAtLeast(0) / step) * step
        val to = (pos + half / pxPerMs).toLong().coerceAtMost(totalMs)
        var t = from
        while (t <= to) {
            val x = half + (t - pos) * pxPerMs
            drawLine(Ink.Line, Offset(x, size.height - 5.dp.toPx()), Offset(x, size.height), 1.dp.toPx())
            val label = if (step < 1000) "%.1f".format(t / 1000f) else "%d:%02d".format(t / 60000, t / 1000 % 60)
            drawText(measurer, label, Offset(x + 3.dp.toPx(), 2.dp.toPx()), style)
            t += step
        }
    }
}

@Composable
private fun ClipTile(
    s: Session, c: Clip, x: Float, w: Float, viewW: Float, pxPerMs: Float, top: Dp,
) {
    val density = LocalDensity.current
    val picked = s.pick == Pick.OfClip(c.id)
    val shape = RoundedCornerShape(8.dp)
    val gapPx = with(density) { 2.dp.toPx() }
    Box(
        Modifier
            .offset { IntOffset(x.roundToInt(), top.roundToPx()) }
            .width(with(density) { (w - gapPx).coerceAtLeast(2f).toDp() })
            .height(ClipH)
            .clip(shape)
            .background(Ink.Raised)
            .drawBehind {
                // Film frames: only the tiles that are on screen get decoded and drawn.
                val aspect = (c.width.toFloat() / c.height.coerceAtLeast(1)).coerceIn(0.6f, 1.78f)
                val tileW = size.height * aspect
                val first = floor(((-x).coerceAtLeast(0f)) / tileW).toInt()
                val last = ceil(((viewW - x).coerceAtMost(size.width)) / tileW).toInt()
                for (k in first until last) {
                    val srcMs = if (c.isImage) 0L else c.inMs + ((k * tileW / pxPerMs) * c.speed).toLong()
                    val bmp = s.thumbs.get(c, srcMs) ?: continue
                    val bw = bmp.width.toFloat()
                    val bh = bmp.height.toFloat()
                    // Centre-crop the frame into its tile.
                    val scale = maxOf(tileW / bw, size.height / bh)
                    val sw = (tileW / scale).coerceAtMost(bw)
                    val sh = (size.height / scale).coerceAtMost(bh)
                    drawImage(
                        bmp.asImageBitmap(),
                        srcOffset = IntOffset(((bw - sw) / 2).toInt(), ((bh - sh) / 2).toInt()),
                        srcSize = IntSize(sw.toInt().coerceAtLeast(1), sh.toInt().coerceAtLeast(1)),
                        dstOffset = IntOffset((k * tileW).roundToInt(), 0),
                        dstSize = IntSize(ceil(tileW).toInt(), size.height.roundToInt()),
                    )
                }
                if (c.fadeInMs > 0) drawFade(0f, c.fadeInMs * pxPerMs, true)
                if (c.fadeOutMs > 0) drawFade(size.width - c.fadeOutMs * pxPerMs, c.fadeOutMs * pxPerMs, false)
            }
            .then(if (picked) Modifier.border(2.dp, Ink.Ember, shape) else Modifier)
            .pointerInput(c.id) { detectTapGestures { s.pick = Pick.OfClip(c.id) } },
    ) {
        val badge = buildString {
            append("%.1f с".format(c.durMs / 1000f))
            if (c.speed != 1f) append("  ${trimZeros(c.speed)}×")
            if (!c.isImage && c.volume == 0f) append("  без звука")
        }
        if (w > with(density) { 54.dp.toPx() }) {
            val inset = with(density) { (-x).coerceAtLeast(0f).toDp() }
            Text(
                badge, color = Ink.Paper, fontSize = 10.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = inset + 5.dp, bottom = 4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0x99000000))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
    }
    if (picked) {
        TrimHandle(s, c, pxPerMs, left = true, x = x, top = top)
        TrimHandle(s, c, pxPerMs, left = false, x = x + w - gapPx, top = top)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFade(x: Float, w: Float, rising: Boolean) {
    val path = androidx.compose.ui.graphics.Path().apply {
        if (rising) {
            moveTo(x, 0f); lineTo(x + w, 0f); lineTo(x, size.height)
        } else {
            moveTo(x + w, 0f); lineTo(x, 0f); lineTo(x + w, size.height)
        }
        close()
    }
    drawPath(path, Color(0xB3000000))
}

/** Grip on a selected clip's edge: drag it to trim (or, for photos, to set how long they stay). */
@Composable
private fun TrimHandle(s: Session, c: Clip, pxPerMs: Float, left: Boolean, x: Float, top: Dp) {
    val handleW = 18.dp
    val carry = remember { floatArrayOf(0f) }
    Box(
        Modifier
            .offset {
                IntOffset(
                    (if (left) x - handleW.toPx() + 4.dp.toPx() else x - 4.dp.toPx()).roundToInt(),
                    top.roundToPx(),
                )
            }
            .size(handleW, ClipH)
            .clip(
                if (left) RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp)
                else RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp),
            )
            .background(Ink.Ember)
            .pointerInput(c.id, left, pxPerMs) {
                detectHorizontalDragGestures(
                    onDragStart = { carry[0] = 0f; s.pause() },
                    onDragEnd = { s.finishLive(); s.seek(s.positionMs) },
                    onDragCancel = { s.finishLive() },
                ) { change, dx ->
                    change.consume()
                    carry[0] += dx / pxPerMs
                    val whole = carry[0].toLong()
                    if (whole != 0L) {
                        carry[0] -= whole
                        s.updateClip(c.id, live = true) { k ->
                            val minLen = (250 * k.speed).toLong().coerceAtLeast(100)
                            val d = (whole * k.speed).toLong()
                            when {
                                k.isImage && left ->
                                    k.copy(outMs = (k.outMs - d).coerceIn(300, k.sourceMs))
                                k.isImage ->
                                    k.copy(outMs = (k.outMs + d).coerceIn(300, k.sourceMs))
                                left ->
                                    k.copy(inMs = (k.inMs + d).coerceIn(0, (k.outMs - minLen).coerceAtLeast(0)))
                                else ->
                                    k.copy(outMs = (k.outMs + d).coerceIn(k.inMs + minLen, k.sourceMs.coerceAtLeast(k.inMs + minLen)))
                            }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(3.dp, 18.dp).clip(RoundedCornerShape(2.dp)).background(Ink.Base))
    }
}

fun trimZeros(v: Float): String {
    val s = "%.2f".format(java.util.Locale.US, v)
    return s.trimEnd('0').trimEnd('.')
}
