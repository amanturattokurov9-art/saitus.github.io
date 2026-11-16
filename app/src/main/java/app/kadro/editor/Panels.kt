package app.kadro.editor

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlin.math.roundToLong

enum class Panel(val title: String) {
    Speed("Скорость"),
    Volume("Громкость"),
    Look("Фильтры"),
    Adjust("Коррекция"),
    Frame("Кадр"),
    Fade("Переходы"),
    Format("Формат"),
    Text("Текст"),
    TextTime("Время показа"),
    Music("Музыка"),
}

/** Frame around every tool panel: its name on the left, a done button on the right. */
@Composable
fun PanelShell(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Ink.Panel)
            .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Ink.Paper, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            RoundIcon(Icons.Rounded.Check, "Готово", tint = Ink.Ember, onClick = onClose)
        }
        Column(Modifier.padding(end = 8.dp)) { content() }
    }
}

@Composable
fun SpeedPanel(s: Session, c: Clip) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(0.25f, 0.5f, 0.75f, 1f, 1.5f, 2f, 3f, 4f).forEach { v ->
            Chip("${trimZeros(v)}×", c.speed == v) { s.updateClip(c.id) { it.copy(speed = v) } }
        }
    }
    Spacer(Modifier.height(10.dp))
    Dial(
        "Точная настройка", c.speed, 0.25f..4f, "${trimZeros(c.speed)}×",
        onChange = { v -> s.updateClip(c.id, live = true) { it.copy(speed = (v * 20).roundToInt() / 20f) } },
        onDone = { s.finishLive() },
    )
    Text(
        "Длительность клипа: ${clock(c.durMs)}",
        color = Ink.Dim, fontSize = 12.sp,
    )
}

@Composable
fun VolumePanel(s: Session, c: Clip) {
    if (!c.hasAudio) {
        Text("В этом клипе нет звуковой дорожки.", color = Ink.Dim, fontSize = 13.sp, modifier = Modifier.padding(vertical = 14.dp))
        return
    }
    Dial(
        "Громкость клипа", c.volume, 0f..2f, "${(c.volume * 100).roundToInt()}%",
        onChange = { v -> s.updateClip(c.id, live = true) { it.copy(volume = (v * 20).roundToInt() / 20f) } },
        onDone = { s.finishLive() },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("Без звука", c.volume == 0f) { s.updateClip(c.id) { it.copy(volume = 0f) } }
        Chip("100%", c.volume == 1f) { s.updateClip(c.id) { it.copy(volume = 1f) } }
        Chip("Громче ×2", c.volume == 2f) { s.updateClip(c.id) { it.copy(volume = 2f) } }
    }
}

@Composable
fun LookPanel(s: Session, c: Clip) {
    val sample = s.thumbs.get(c, c.inMs)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Looks.names.forEach { (id, title) ->
            val on = c.look == id
            Column(
                Modifier.clickable { s.updateClip(c.id) { it.copy(look = id) } },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .size(62.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Ink.Raised)
                        .then(if (on) Modifier.border(2.dp, Ink.Ember, RoundedCornerShape(10.dp)) else Modifier),
                ) {
                    if (sample != null) {
                        Image(
                            sample.asImageBitmap(), null,
                            contentScale = ContentScale.Crop,
                            colorFilter = Looks.colorMatrix(id)?.let { ColorFilter.colorMatrix(ColorMatrix(it)) },
                            modifier = Modifier.size(62.dp),
                        )
                    }
                }
                Spacer(Modifier.height(5.dp))
                Text(title, color = if (on) Ink.Ember else Ink.Dim, fontSize = 11.sp)
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(
        "Применить ко всем клипам",
        color = Ink.Paper, fontSize = 13.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable { s.edit { p -> p.copy(clips = p.clips.map { it.copy(look = c.look) }) } }
            .padding(vertical = 6.dp, horizontal = 2.dp),
    )
}

@Composable
fun AdjustPanel(s: Session, c: Clip) {
    Dial(
        "Яркость", c.brightness, -0.5f..0.5f, signed(c.brightness * 200),
        onChange = { v -> s.updateClip(c.id, live = true) { it.copy(brightness = snap(v, 0.02f)) } },
        onDone = { s.finishLive() },
    )
    Dial(
        "Контраст", c.contrast, -0.6f..0.6f, signed(c.contrast * 166),
        onChange = { v -> s.updateClip(c.id, live = true) { it.copy(contrast = snap(v, 0.02f)) } },
        onDone = { s.finishLive() },
    )
    Dial(
        "Насыщенность", c.saturation, -100f..100f, signed(c.saturation),
        onChange = { v -> s.updateClip(c.id, live = true) { it.copy(saturation = snap(v, 2f)) } },
        onDone = { s.finishLive() },
    )
    Chip("Сбросить", false) { s.updateClip(c.id) { it.copy(brightness = 0f, contrast = 0f, saturation = 0f) } }
}

private fun snap(v: Float, step: Float): Float {
    val r = (v / step).roundToInt() * step
    return if (kotlin.math.abs(r) < step / 2) 0f else r
}

private fun signed(v: Float): String {
    val i = v.roundToInt()
    return if (i > 0) "+$i" else "$i"
}

@Composable
fun FramePanel(s: Session, c: Clip) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("Вписать", !c.fill) { s.updateClip(c.id) { it.copy(fill = false) } }
        Chip("Заполнить", c.fill) { s.updateClip(c.id) { it.copy(fill = true) } }
        Chip("Повернуть 90°", false) { s.updateClip(c.id) { it.copy(rotation = (it.rotation + 90) % 360) } }
        Chip("Зеркало", c.mirror) { s.updateClip(c.id) { it.copy(mirror = !it.mirror) } }
    }
    Spacer(Modifier.height(10.dp))
    Dial(
        "Приближение", c.zoom, 1f..3f, "${trimZeros(c.zoom)}×",
        onChange = { v -> s.updateClip(c.id, live = true) { it.copy(zoom = (v * 20).roundToInt() / 20f) } },
        onDone = { s.finishLive() },
    )
}

@Composable
fun FadePanel(s: Session, c: Clip) {
    val cap = (c.durMs / 2f).coerceIn(100f, 2000f)
    Dial(
        "Появление из чёрного", c.fadeInMs.toFloat(), 0f..cap, "%.1f с".format(c.fadeInMs / 1000f),
        onChange = { v -> s.updateClip(c.id, live = true) { it.copy(fadeInMs = (v / 100).roundToLong() * 100) } },
        onDone = { s.finishLive() },
    )
    Dial(
        "Затухание в чёрный", c.fadeOutMs.toFloat(), 0f..cap, "%.1f с".format(c.fadeOutMs / 1000f),
        onChange = { v -> s.updateClip(c.id, live = true) { it.copy(fadeOutMs = (v / 100).roundToLong() * 100) } },
        onDone = { s.finishLive() },
    )
    Text(
        "Плавный стык: затухание на этом клипе и появление на следующем.",
        color = Ink.Dim, fontSize = 12.sp,
    )
}

@Composable
fun FormatPanel(s: Session) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            "9:16" to "9:16  вертикаль", "16:9" to "16:9  широкий", "1:1" to "1:1  квадрат",
            "4:5" to "4:5  лента", "3:4" to "3:4", "orig" to "Как в первом клипе",
        ).forEach { (id, label) ->
            Chip(label, s.project.ratio == id) { s.edit { it.copy(ratio = id) } }
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(
        "Клипы другой формы вписываются с полями. Чтобы убрать поля, выберите клип → «Кадр» → «Заполнить».",
        color = Ink.Dim, fontSize = 12.sp,
    )
}

private val Palette = listOf(
    0xFFFFFFFF, 0xFF101114, 0xFFFF6B3D, 0xFFFFD23F, 0xFF58D3AE, 0xFF4DA3FF, 0xFF8F7DFF, 0xFFFF5C8A,
).map { it.toInt() }

@Composable
fun TextPanel(s: Session, t: TextLayer) {
    var draft by remember(t.id) { mutableStateOf(t.text) }
    BasicTextField(
        value = draft,
        onValueChange = { v ->
            draft = v
            s.updateText(t.id, live = true) { it.copy(text = v) }
        },
        textStyle = TextStyle(color = Ink.Paper, fontSize = 16.sp),
        cursorBrush = SolidColor(Ink.Ember),
        maxLines = 3,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Ink.Raised)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        decorationBox = { inner ->
            if (draft.isEmpty()) Text("Введите текст", color = Ink.Dim, fontSize = 16.sp)
            inner()
        },
    )
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Palette.forEach { argb ->
            Box(
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color(argb))
                    .border(2.dp, if (t.color == argb) Ink.Ember else Ink.Line, CircleShape)
                    .clickable { s.updateText(t.id) { it.copy(color = argb) } },
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    Dial(
        "Размер", t.size, 0.025f..0.22f, "${(t.size * 1000).roundToInt()}",
        onChange = { v -> s.updateText(t.id, live = true) { it.copy(size = v) } },
        onDone = { s.finishLive() },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Toggle("Жирный", t.bold) { v -> s.updateText(t.id) { it.copy(bold = v) } }
        Toggle("Обводка", t.outline && !t.plate) { v -> s.updateText(t.id) { it.copy(outline = v, plate = false) } }
        Toggle("Плашка", t.plate) { v -> s.updateText(t.id) { it.copy(plate = v) } }
    }
}

@Composable
fun TextTimePanel(s: Session, t: TextLayer) {
    val total = s.project.totalMs.coerceAtLeast(500).toFloat()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("с ${clock(t.startMs)}", color = Ink.Paper, fontSize = 12.sp)
        Text("по ${clock(t.endMs)}", color = Ink.Paper, fontSize = 12.sp)
    }
    RangeSlider(
        value = t.startMs.toFloat().coerceIn(0f, total)..t.endMs.toFloat().coerceIn(0f, total),
        onValueChange = { r ->
            s.updateText(t.id, live = true) {
                val a = r.start.toLong()
                it.copy(startMs = a, endMs = r.endInclusive.toLong().coerceAtLeast(a + 200))
            }
        },
        onValueChangeFinished = { s.finishLive() },
        valueRange = 0f..total,
        colors = SliderDefaults.colors(thumbColor = Ink.Paper, activeTrackColor = Ink.Mint, inactiveTrackColor = Ink.Line),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("Начать с бегунка", false) {
            s.updateText(t.id) {
                val a = s.positionMs.coerceAtMost((total - 200).toLong())
                it.copy(startMs = a, endMs = maxOf(it.endMs, a + 200))
            }
        }
        Chip("Закончить на бегунке", false) {
            s.updateText(t.id) {
                val b = s.positionMs.coerceAtLeast(200)
                it.copy(endMs = b, startMs = minOf(it.startMs, b - 200))
            }
        }
        Chip("На весь ролик", false) { s.updateText(t.id) { it.copy(startMs = 0, endMs = total.toLong()) } }
    }
}

@Composable
fun MusicPanel(s: Session, m: Music, onReplace: () -> Unit) {
    Dial(
        "Громкость музыки", m.volume, 0f..1.5f, "${(m.volume * 100).roundToInt()}%",
        onChange = { v -> s.updateMusic(live = true) { it.copy(volume = (v * 20).roundToInt() / 20f) } },
        onDone = { s.finishLive() },
    )
    Dial(
        "Начать трек с", m.inMs.toFloat(), 0f..(m.sourceMs - 1000).coerceAtLeast(1).toFloat(), clock(m.inMs),
        onChange = { v -> s.updateMusic(live = true) { it.copy(inMs = (v / 100).roundToLong() * 100) } },
        onDone = { s.finishLive() },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Toggle("Повторять по кругу", m.loop) { v -> s.updateMusic { it.copy(loop = v) } }
        Chip("Заменить", false, onClick = onReplace)
        Chip("Приглушить видео", false) {
            s.edit { p -> p.copy(clips = p.clips.map { it.copy(volume = 0f) }) }
        }
    }
}
