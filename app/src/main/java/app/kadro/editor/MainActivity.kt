package app.kadro.editor

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var drafts: Drafts
    private var session by mutableStateOf<Session?>(null)
    private var list by mutableStateOf<List<Project>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        val bar = SystemBarStyle.dark(0xFF101114.toInt())
        enableEdgeToEdge(statusBarStyle = bar, navigationBarStyle = bar)
        super.onCreate(savedInstanceState)
        drafts = Drafts(this)
        list = drafts.list()
        setContent {
            KadroTheme {
                val s = session
                if (s == null) {
                    Home(
                        list,
                        onNew = { open(Project(name = "Проект ${list.size + 1}")) },
                        onOpen = ::open,
                        onDelete = { drafts.delete(it.id); list = drafts.list() },
                    )
                } else {
                    EditorScreen(s, onExit = ::leave)
                }
            }
        }
        if (savedInstanceState == null) receive(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receive(intent)
    }

    /** "Open with" and "Share to" from other apps start a fresh project with that footage. */
    private fun receive(intent: Intent?) {
        intent ?: return
        val uris = ArrayList<Uri>()
        when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.let(uris::add)
            Intent.ACTION_SEND ->
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(uris::add)
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(uris::addAll)
        }
        if (uris.isEmpty()) return
        lifecycleScope.launch {
            val clips = withContext(Dispatchers.IO) { uris.mapNotNull { MediaProbe.clip(applicationContext, it) } }
            if (clips.isEmpty()) return@launch
            if (session != null) leave()
            open(Project(name = "Проект ${list.size + 1}", clips = clips))
        }
    }

    private fun open(p: Project) {
        session = Session(applicationContext, p, drafts)
    }

    private fun leave() {
        val s = session ?: return
        session = null
        s.close()
        // A project that never received footage is not worth keeping.
        if (s.project.clips.isEmpty()) drafts.delete(s.project.id)
        list = drafts.list()
    }

    override fun onStop() {
        super.onStop()
        session?.pause()
    }

    override fun onDestroy() {
        session?.close()
        session = null
        super.onDestroy()
    }
}

@Composable
private fun Home(
    drafts: List<Project>,
    onNew: () -> Unit,
    onOpen: (Project) -> Unit,
    onDelete: (Project) -> Unit,
) {
    Column(Modifier.fillMaxSize().background(Ink.Base).systemBarsPadding().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(28.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text("Kadro", color = Ink.Paper, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Box(Modifier.padding(start = 4.dp, bottom = 9.dp).size(8.dp).clip(CircleShape).background(Ink.Ember))
        }
        Text("Монтаж без подписок и водяных знаков", color = Ink.Dim, fontSize = 14.sp)
        Spacer(Modifier.height(24.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Ink.Ember)
                .clickable(onClick = onNew)
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(Ink.Base),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Add, null, tint = Ink.Ember, modifier = Modifier.size(28.dp)) }
            Spacer(Modifier.width(16.dp))
            Column {
                Text("Новый проект", color = Ink.Base, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text("Видео, фото, музыка, текст", color = Ink.Base.copy(alpha = 0.7f), fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(28.dp))
        Text("Черновики", color = Ink.Paper, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        if (drafts.isEmpty()) {
            Text(
                "Здесь появятся ваши проекты. Они сохраняются сами — можно выйти и продолжить позже.",
                color = Ink.Dim, fontSize = 13.sp, lineHeight = 19.sp,
            )
        }
        val date = SimpleDateFormat("d MMM, HH:mm", Locale("ru"))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(drafts, key = { it.id }) { p ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Ink.Panel)
                        .clickable { onOpen(p) }
                        .padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(Ink.Raised),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (p.ratio == "orig") "авто" else p.ratio,
                            color = Ink.Ember, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            p.name, color = Ink.Paper, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${clock(p.totalMs)} · клипов: ${p.clips.size} · ${date.format(Date(p.updatedAt))}",
                            color = Ink.Dim, fontSize = 12.sp,
                        )
                    }
                    RoundIcon(Icons.Rounded.DeleteOutline, "Удалить", tint = Ink.Dim) { onDelete(p) }
                }
            }
        }
    }
}
