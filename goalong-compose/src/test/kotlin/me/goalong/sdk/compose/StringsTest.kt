package me.goalong.sdk.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** 4 言語の strings.xml が同じキー・同じ差し込み(%1$s)を持つこと(iOS の LocalizationTests に相当) */
class StringsTest {
    private val res = File("src/main/res")

    private fun load(dir: String): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(res, "$dir/goalong_strings.xml"))
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).associate { i ->
            val node = nodes.item(i)
            node.attributes.getNamedItem("name").nodeValue to node.textContent
        }
    }

    @Test fun allLanguagesHaveTheSameKeysAndPlaceholders() {
        val base = load("values")
        assertEquals(22, base.size)
        for (dir in listOf("values-ja", "values-ko", "values-b+zh+Hant")) {
            val other = load(dir)
            assertEquals(dir, base.keys, other.keys)
            for ((key, value) in base) {
                assertEquals("$dir/$key", value.contains("%1\$s"), other.getValue(key).contains("%1\$s"))
                assertTrue("$dir/$key は空でない", other.getValue(key).isNotBlank())
                assertTrue("$dir/$key に iOS の %@ が残っていない", !other.getValue(key).contains("%@"))
            }
        }
    }

    @Test fun japaneseCopyMatchesIos() {
        val ja = load("values-ja")
        assertEquals("紹介コードをお持ちですか?", ja["goalong_input_title"])
        assertEquals("コードを入力（例: MIKA）", ja["goalong_placeholder"])
        assertEquals("このコードで %1\$s", ja["goalong_perk_preview"])
        assertEquals("特典はログイン後に受け取れます", ja["goalong_perk_pending"])
        assertEquals("有料プランをご契約中のため、この紹介コードはご利用いただけません", ja["goalong_error_existing_subscriber"])
    }

    @Test fun fillReplacesBothPlaceholderStyles() {
        assertEquals("このコードで 1週間無料", "このコードで %1\$s".fill("1週間無料"))
        assertEquals("1週間無料 applied", "%@ applied".fill("1週間無料"))
    }
}
