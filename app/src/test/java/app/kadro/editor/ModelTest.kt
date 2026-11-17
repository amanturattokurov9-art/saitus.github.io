package app.kadro.editor

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelTest {
    private fun video(id: String, inMs: Long, outMs: Long, speed: Float = 1f) = Clip(
        id = id, uri = "content://v/$id", mime = "video/mp4", isImage = false, sourceMs = 60_000,
        hasAudio = true, width = 3840, height = 2160, inMs = inMs, outMs = outMs, speed = speed,
        heavy = true,
    )

    @Test fun durationFollowsTrimAndSpeed() {
        assertEquals(4_000, video("a", 1_000, 5_000).durMs)
        assertEquals(2_000, video("a", 1_000, 5_000, speed = 2f).durMs)
        assertEquals(8_000, video("a", 1_000, 5_000, speed = 0.5f).durMs)
    }

    @Test fun locateFindsClipAndOffset() {
        val p = Project(clips = listOf(video("a", 0, 3_000), video("b", 0, 2_000, speed = 2f)))
        assertEquals(4_000, p.totalMs)
        assertEquals(0 to 0L, p.locate(0))
        assertEquals(0 to 2_999L, p.locate(2_999))
        assertEquals(1 to 0L, p.locate(3_000))
        assertEquals(1 to 500L, p.locate(3_500))
        // Past the end sticks to the last clip's end, so the playhead never falls off.
        assertEquals(1 to 1_000L, p.locate(10_000))
        assertNull(Project().locate(0))
        assertEquals(3_000, p.startOf(1))
    }

    @Test fun canvasKeepsRatioAndEvenSides() {
        assertEquals(1080 to 1920, Project(ratio = "9:16").canvas(1080))
        assertEquals(3840 to 2160, Project(ratio = "16:9").canvas(2160))
        assertEquals(540 to 540, Project(ratio = "1:1").canvas(540))
        assertEquals(1080 to 1350, Project(ratio = "4:5").canvas(1080))
        val orig = Project(ratio = "orig", clips = listOf(video("a", 0, 1_000)))
        assertEquals(960 to 540, orig.canvas(540))
        // A turned clip swaps the sides.
        val turned = Project(ratio = "orig", clips = listOf(video("a", 0, 1_000).copy(rotation = 90)))
        assertEquals(540 to 960, turned.canvas(540))
        val (w, h) = Project(ratio = "orig", clips = listOf(video("a", 0, 1).copy(width = 1001, height = 563))).canvas(540)
        assertTrue(w % 2 == 0 && h % 2 == 0)
    }

    @Test fun draftSurvivesSaveAndLoad() {
        val p = Project(
            name = "Тест", ratio = "1:1",
            clips = listOf(
                video("a", 500, 4_000, speed = 1.5f).copy(look = "noir", zoom = 1.4f, fadeOutMs = 600, mirror = true),
            ),
            texts = listOf(TextLayer(text = "Привет\nмир", startMs = 0, endMs = 2_000, x = 0.2f, plate = true)),
            music = Music(uri = "content://m/1", title = "Трек", sourceMs = 90_000, inMs = 10_000, volume = 0.5f),
        )
        assertEquals(p, projectFromJson(JSONObject(p.toJson().toString())))
    }

    @Test fun oldDraftsWithout4kFlagAreDetectedBySize() {
        val o = Project(clips = listOf(video("a", 0, 1_000))).toJson()
        val clip = o.getJSONArray("clips").getJSONObject(0)
        clip.remove("heavy")
        assertTrue(projectFromJson(o).clips[0].heavy)
        clip.put("width", 1920); clip.put("height", 1080)
        assertTrue(!projectFromJson(o).clips[0].heavy)
    }

    @Test fun everyLookHasAValidMatrix() {
        Looks.names.forEach { (id, _) ->
            if (id == "none") return@forEach
            val m = Looks.matrix(id)!!
            assertEquals(16, m.size)
            assertEquals(1f, m[15])
            assertEquals(20, Looks.colorMatrix(id)!!.size)
        }
    }
}
