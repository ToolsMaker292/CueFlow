package com.cueflow.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptCodecTest {
    private fun script(
        id: String = "id-1",
        title: String = "Demo",
        content: String = "Hello world",
        created: Long = 1_000L,
        updated: Long = 2_000L,
    ) = Script(id = id, title = title, content = content, createdAt = created, updatedAt = updated)

    @Test
    fun `library round trips without losing unicode or order`() {
        val scripts = listOf(
            script(id = "a", title = "中文标题", content = "第一段\n\n第二段 🎬", updated = 3_000L),
            script(id = "b", title = "Second", content = "Line one\nLine two", updated = 2_000L),
        )
        val decoded = ScriptCodec.decodeLibrary(ScriptCodec.encodeLibrary(scripts))
        assertEquals(2, decoded.size)
        assertEquals("中文标题", decoded[0].title)
        assertEquals("第一段\n\n第二段 🎬", decoded[0].content)
        assertEquals("a", decoded[0].id)
        assertEquals("b", decoded[1].id)
    }

    @Test
    fun `sanitize replaces duplicate and missing ids`() {
        val decoded = ScriptCodec.sanitize(
            listOf(
                script(id = "same"),
                script(id = "same"),
                script(id = ""),
            ),
            now = 5_000L,
        )
        assertEquals(3, decoded.size)
        assertEquals(3, decoded.map { it.id }.toSet().size)
        assertTrue(decoded.all { it.id.isNotBlank() })
    }

    @Test
    fun `sanitize clamps oversized fields and normalizes newlines`() {
        val decoded = ScriptCodec.sanitize(
            listOf(
                Script(
                    id = "x",
                    title = "t".repeat(ScriptLimits.MAX_TITLE_LENGTH + 30),
                    content = "a\r\nb\rc" + "z".repeat(ScriptLimits.MAX_CONTENT_LENGTH),
                    createdAt = 10L,
                    updatedAt = 5L,
                ),
            ),
            now = 100L,
        ).single()
        assertEquals(ScriptLimits.MAX_TITLE_LENGTH, decoded.title.length)
        assertEquals(ScriptLimits.MAX_CONTENT_LENGTH, decoded.content.length)
        assertTrue(decoded.content.startsWith("a\nb\nc"))
        assertTrue(decoded.updatedAt >= decoded.createdAt)
    }

    @Test
    fun `decode rejects malformed and future schema`() {
        assertThrows(IllegalArgumentException::class.java) {
            ScriptCodec.decodeLibrary("not json")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ScriptCodec.decodeLibrary("""{"schemaVersion":2,"scripts":[]}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ScriptCodec.decodeLibrary("   ")
        }
    }

    @Test
    fun `duplicate creates a new identity and timestamp`() {
        val original = script()
        val copy = ScriptCodec.duplicate(original, " (copy)", 9_000L, "new-id")
        assertNotEquals(original.id, copy.id)
        assertEquals("Demo (copy)", copy.title)
        assertEquals(9_000L, copy.createdAt)
        assertEquals(9_000L, copy.updatedAt)
        assertEquals(original.content, copy.content)
    }

    @Test
    fun `plain text export contains title and body`() {
        val text = ScriptCodec.encodeText(script(title = "Title", content = "Body"))
        assertEquals("Title\n\nBody\n", text)
    }
}
