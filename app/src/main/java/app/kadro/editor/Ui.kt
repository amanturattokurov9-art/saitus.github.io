package app.kadro.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Ink {
    val Base = Color(0xFF101114)
    val Panel = Color(0xFF17191E)
    val Raised = Color(0xFF22252C)
    val Line = Color(0xFF30343D)
    val Paper = Color(0xFFF4EFE6)
    val Dim = Color(0xFF9B9CA6)
    val Ember = Color(0xFFFF6B3D)
    val Mint = Color(0xFF58D3AE)
    val Iris = Color(0xFF8F7DFF)
}

@Composable
fun KadroTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Ink.Ember, onPrimary = Ink.Base,
            background = Ink.Base, onBackground = Ink.Paper,
            surface = Ink.Panel, onSurface = Ink.Paper,
            surfaceVariant = Ink.Raised, onSurfaceVariant = Ink.Dim,
            outline = Ink.Line,
        ),
        content = content,
    )
}

fun clock(ms: Long): String {
    val t = ms.coerceAtLeast(0)
    return "%d:%02d.%d".format(t / 60000, t / 1000 % 60, t / 100 % 10)
}

@Composable
fun RoundIcon(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    tint: Color = Ink.Paper,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(22.dp).alpha(if (enabled) 1f else 0.3f))
    }
}

/** A tool on the dock: glyph above, name below. */
@Composable
fun Tool(icon: ImageVector, label: String, tint: Color = Ink.Paper, enabled: Boolean = true, onClick: () -> Unit) {
    Column(
        Modifier
            .width(68.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.35f)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(5.dp))
        Text(label, color = Ink.Dim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun Chip(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Ink.Ember else Ink.Raised)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (selected) Ink.Base else Ink.Paper,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

@Composable
fun Toggle(text: String, on: Boolean, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    Row(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, if (on) Ink.Ember else Ink.Line, RoundedCornerShape(10.dp))
            .clickable { onChange(!on) }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (on) Ink.Ember else Ink.Line))
        Spacer(Modifier.width(8.dp))
        Text(text, color = if (on) Ink.Paper else Ink.Dim, fontSize = 13.sp)
    }
}

/** Labelled slider that reports while dragging and once more when released. */
@Composable
fun Dial(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    readout: String,
    onChange: (Float) -> Unit,
    onDone: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Ink.Dim, fontSize = 12.sp)
            Text(readout, color = Ink.Paper, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            onValueChangeFinished = onDone,
            valueRange = range,
            modifier = Modifier.height(36.dp),
            colors = SliderDefaults.colors(
                thumbColor = Ink.Paper,
                activeTrackColor = Ink.Ember,
                inactiveTrackColor = Ink.Line,
            ),
        )
    }
}
