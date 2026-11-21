/*
 * Copyright 2026 Nyandex contributors.
 *
 * Nyandex: Yandex Music patches for Morphe Patcher.
 * Licensed under the GNU General Public License v3.0; see LICENSE and NOTICE.
 */

package app.nyandex.patches.yandexmusic

import java.io.File
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import javax.tools.ToolProvider
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests of the pure Java part of the LRCLIB extension. The source is compiled here from
 * the extension module, so the tests always run against the code that is bundled.
 */
class LrcLibCoreTest {
    private val core: Class<*> by lazy { compileCore() }

    private fun call(name: String, vararg arguments: Any?): Any? {
        val method = core.declaredMethods.single { it.name == name && it.parameterCount == arguments.size }
        method.isAccessible = true
        return try {
            method.invoke(null, *arguments)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun json(text: String): Any? = call("parseJson", text)

    @Suppress("UNCHECKED_CAST")
    private fun choose(record: String, format: String) =
        call("chooseLyrics", json(record) as Map<String, Any?>, format) as Array<String>?

    @Test
    fun `urls are encoded with percent escapes`() {
        assertEquals(
            "https://lrclib.net/api/get?artist_name=Imagine%20Dragons&track_name=Believer&duration=204",
            call("buildGetUrl", "Imagine Dragons", "Believer", 204L),
        )
        assertEquals(
            "https://lrclib.net/api/get?artist_name=%D0%9A%D0%B8%D0%BD%D0%BE" +
                "&track_name=%D0%93%D1%80%D1%83%D0%BF%D0%BF%D0%B0%20%D0%BA%D1%80%D0%BE%D0%B2%D0%B8" +
                "&duration=285",
            call("buildGetUrl", "Кино", "Группа крови", 285L),
        )
        assertEquals(
            "https://lrclib.net/api/get?artist_name=AC%2FDC&track_name=T.N.T.%20%26%20more%3F",
            call("buildGetUrl", "AC/DC", "T.N.T. & more?", 0L),
        )
        assertEquals(
            "https://lrclib.net/api/search?track_name=Believer&artist_name=Imagine%20Dragons",
            call("buildSearchUrl", "Imagine Dragons", "Believer"),
        )
        assertEquals("https://lrclib.net/api/search?track_name=Believer", call("buildSearchUrl", null, "Believer"))
        assertEquals(204L, call("secondsFromMillis", 204_347L))
        assertEquals(0L, call("secondsFromMillis", 0L))
    }

    @Test
    fun `artist candidates are the joined list and then the main artist`() {
        assertEquals(
            listOf("Imagine Dragons, Lil Wayne", "Imagine Dragons"),
            call("artistCandidates", listOf("Imagine Dragons", " Lil Wayne ")),
        )
        assertEquals(listOf("Кино"), call("artistCandidates", listOf("Кино")))
        assertEquals(emptyList<String>(), call("artistCandidates", emptyList<String>()))
    }

    @Test
    fun `synced lyrics are preferred and normalized`() {
        val record = """{"id":1,"trackName":"Believer","duration":204.0,"instrumental":false,
            "plainLyrics":"First things first\nI'ma say all the words",
            "syncedLyrics":"[00:01.234] First things first\n[00:03.5]I'ma say all the words"}"""
        val lyrics = choose(record, "LRC")!!
        assertEquals("LRC", lyrics[0])
        assertEquals("[00:01.23]First things first\n[00:03.50]I'ma say all the words", lyrics[1])
    }

    @Test
    fun `plain lyrics are used as TEXT when synced lyrics are missing`() {
        val lyrics = choose("""{"plainLyrics":"  line one\nline two ","syncedLyrics":null}""", "LRC")!!
        assertContentEquals(arrayOf("TEXT", "line one\nline two"), lyrics)

        val empty = choose("""{"plainLyrics":"text","syncedLyrics":"   "}""", "LRC")!!
        assertEquals("TEXT", empty[0])
    }

    @Test
    fun `TEXT requests get plain lyrics or synced lyrics without timestamps`() {
        assertContentEquals(
            arrayOf("TEXT", "plain"),
            choose("""{"plainLyrics":"plain","syncedLyrics":"[00:01.00]synced"}""", "TEXT"),
        )
        assertContentEquals(
            arrayOf("TEXT", "a\nb"),
            choose("""{"plainLyrics":null,"syncedLyrics":"[00:01.00]a\n[00:02.00]b"}""", "TEXT"),
        )
    }

    @Test
    fun `instrumental and empty records have no lyrics`() {
        assertNull(choose("""{"instrumental":true,"plainLyrics":null,"syncedLyrics":null}""", "LRC"))
        assertNull(choose("""{"plainLyrics":"","syncedLyrics":""}""", "LRC"))
        assertNull(call("chooseLyrics", null, "LRC"))
    }

    @Test
    fun `lrc timestamps are normalized for the app parser`() {
        val appParser = Regex("""\[(\d{2,}):(\d\d)\.(\d\d)](.*)""")
        val normalized = call(
            "normalizeLrc",
            "[ar:Artist]\r\n[ti:Title]\n[1:02.345]late\n[00:05]no fraction\n" +
                "[00:10.1][00:30.99]chorus\nuntimed line\n[00:00.00]",
        ) as String

        assertEquals(
            listOf(
                "[00:00.00]",
                "[00:05.00]no fraction",
                "[00:10.10]chorus",
                "[00:30.99]chorus",
                "[01:02.34]late",
            ),
            normalized.lines(),
        )
        normalized.lines().forEach { line ->
            assertTrue(appParser.matches(line), "App parser rejects: $line")
        }
        assertEquals("[100:00.00]long", (call("normalizeLrc", "[100:00.00]long") as String))
    }

    @Test
    fun `search picks the closest duration and prefers synced lyrics`() {
        @Suppress("UNCHECKED_CAST")
        val results = json(
            """[
                {"id":1,"duration":180,"plainLyrics":"wrong length","syncedLyrics":"[00:01.00]x"},
                {"id":2,"duration":205,"plainLyrics":"plain only","syncedLyrics":null},
                {"id":3,"duration":206,"plainLyrics":"p","syncedLyrics":"[00:01.00]synced"},
                {"id":4,"duration":204,"instrumental":true,"plainLyrics":null,"syncedLyrics":null}
            ]""",
        ) as List<Any?>

        val best = call("pickSearchResult", results, 204L) as Map<*, *>
        assertEquals(2L, best["id"]) // 1 s away, plain: score 3; id 3 is 2 s away, synced: score 4

        val exact = call("pickSearchResult", results, 206L) as Map<*, *>
        assertEquals(3L, exact["id"])

        assertNull(call("pickSearchResult", results, 300L))
        assertNull(call("pickSearchResult", null, 204L))
    }

    @Test
    fun `json reader handles escapes, unicode and nesting`() {
        val value = json("""{"a":"line\n\"quoted\" Кино \/","b":[1,2.5,true,null],"c":{}}""")
            as Map<*, *>
        assertEquals("line\n\"quoted\" Кино /", value["a"])
        assertEquals(listOf(1L, 2.5, true, null), value["b"])
        assertEquals(emptyMap<String, Any>(), value["c"])
    }

    private fun compileCore(): Class<*> {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "extensions/lrclib-lyrics").isDirectory }
        val source = File(
            root,
            "extensions/lrclib-lyrics/src/main/java/app/nyandex/extension/yandexmusic/lyrics/LrcLibCore.java",
        )
        val output = createTempDirectory("lrclib-core").toFile()
        val compiler = ToolProvider.getSystemJavaCompiler()
        val result = compiler.run(null, null, null, "-d", output.path, "--release", "17", source.path)
        assertEquals(0, result, "LrcLibCore.java did not compile")

        val loader = URLClassLoader(arrayOf(output.toURI().toURL()), javaClass.classLoader)
        return loader.loadClass("app.nyandex.extension.yandexmusic.lyrics.LrcLibCore")
    }
}
