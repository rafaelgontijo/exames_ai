package br.com.exames.coreui

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class FigtreeFontsTest {
    private fun sha(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
    private val pinned = mapOf(
        "src/main/res/font/figtree_regular.ttf" to "448d74e778cc9774db27bd86cf451bba982b2e4dc348f610390eb6e3439cf6ca",
        "src/main/res/font/figtree_medium.ttf" to "358a4de79ae6c3fd7c6a54fcd778c1101ba376af75b8463e715df6988387bc19",
        "src/main/res/font/figtree_semibold.ttf" to "35fc4508d627f8402a7d5ae91d842d1adb36846eea902c0a849642afecd0ac74",
        "src/main/res/font/figtree_bold.ttf" to "71a35e2bd92a05427e2dc9897bab41f8a865800e148238007735b5934508198a",
        "src/main/res/font/figtree_extrabold.ttf" to "c86cc3d813f6af7723474ff78ba1d1351254d1f4d4bfeedf58d9e8ed389b1dc4",
        "third_party/figtree/OFL.txt" to "140d37233e7f3ce7313798befa9600893bcceaf41a55fa0fa5ad52f7f657a268",
    )
    @Test fun bundledFilesMatchPinnedHashes() {
        for ((path, hash) in pinned) { val f = File(path); assertTrue("MISSING $path", f.isFile); assertEquals(path, hash, sha(f)) }
    }
    @Test fun fontsAreStaticTrueTypeWithoutVariationTable() {
        for (path in pinned.keys.filter { it.endsWith(".ttf") }) {
            val b = File(path).readBytes()
            assertEquals(0x00010000, java.nio.ByteBuffer.wrap(b).int)
            val tables = (0 until java.nio.ByteBuffer.wrap(b).getShort(4).toInt()).map { String(b, 12 + it * 16, 4, Charsets.ISO_8859_1) }
            assertFalse("variable font $path", "fvar" in tables)
        }
    }
    @Test fun noRuntimeFontDownloadOrNetworkProviderIsReferenced() {
        val forbidden = listOf("GoogleFont", "fonts.google", "downloadable", "FontRequest", "http://", "https://", "java.net", "okhttp")
        File("src/main").walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }.forEach { f ->
            val t = f.readText()
            for (bad in forbidden) assertFalse("${f.path} mentions $bad", t.contains(bad))
        }
    }
}
