package br.com.exames.domain.validation

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CorpusPackageTest {
    private val document="%PDF-synthetic-fixture\n%%EOF".toByteArray()
    private val truth=ValidationJson.format.decodeFromString<List<GroundTruthVersion>>(
        javaClass.getResourceAsStream("/validation/truth-v2-scenarios.json")!!.bufferedReader().use { it.readText() }).first()
    private val truthBytes=ValidationJson.format.encodeToString(truth).toByteArray()
    private val case=CorpusCase(truth.caseId,"b".repeat(64),CorpusOrigin.SYNTHETIC,CorpusFormat.PDF_TEXT,
        CorpusPartition.DEVELOPMENT,"simple-v1","1","documents/source.pdf",CorpusPackages.sha256(document),document.size.toLong(),
        "truth/source.json",CorpusPackages.sha256(truthBytes))
    private val manifest=CorpusManifest("1.1","synthetic/1","test/1",1,"c".repeat(64),listOf(case))
    private fun zip(files: Map<String,ByteArray>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z -> files.forEach { (name,bytes) -> z.putNextEntry(ZipEntry(name));z.write(bytes);z.closeEntry() } }
    }.toByteArray()
    private fun files(m: CorpusManifest = manifest, t: ByteArray = truthBytes) = linkedMapOf(
        "manifest.json" to ValidationJson.format.encodeToString(m).toByteArray(),case.documentPath to document,case.groundTruthPath to t)
    private fun reject(bytes: ByteArray) { assertThrows(Exception::class.java) { CorpusPackages.decode(bytes).close() } }
    @Test fun validatesEntirePackageAndKeepsIndependentTruthAndBytesUntilClosed() {
        val p=CorpusPackages.decode(zip(files()))
        assertEquals(manifest,p.manifest);assertEquals(truth,p.cases.single().truth.groundTruth)
        assertArrayEquals(document,p.cases.single().document)
        val retained=p.cases.single().document;p.close();assertTrue(retained.all { it==0.toByte() })
    }
    @Test fun unsafePathsAliasesAndUnusedMembersAreRejected() {
        for (path in listOf("/source","../source","a/../source","a//source","a\\source","a/./source","a/","a\u0000b")) {
            assertFalse(CorpusPackages.safePath(path));reject(zip(files().also { it[path]=byteArrayOf(1) }))
        }
        reject(zip(files().also { it["unused"]=byteArrayOf(1) }))
        reject(zip(files().also { it.remove(case.documentPath) }))
    }
    @Test fun hashesSizesSignaturesAndIdentityAreNotTakenFromManifestOnTrust() {
        for (changed in listOf(case.copy(documentSha256="d".repeat(64)),case.copy(documentBytes=document.size+1L),
            case.copy(groundTruthSha256="d".repeat(64)),case.copy(caseId="e".repeat(64)),case.copy(format=CorpusFormat.PNG)))
            reject(zip(files(manifest.copy(cases=listOf(changed)))))
        reject(zip(files(manifest.copy(cases=listOf(case,case)))))
    }
    @Test fun duplicateKeysUnknownSchemaAndCrossPartitionFamiliesAreRejected() {
        val duplicate=files().also { it["manifest.json"]=("{\"seed\":0,"+ValidationJson.format.encodeToString(manifest).drop(1)).toByteArray() }
        reject(zip(duplicate));reject(zip(files(manifest.copy(schemaVersion="3"))))
        val other=case.copy(caseId="f".repeat(64),partition=CorpusPartition.HELD_OUT)
        reject(zip(files(manifest.copy(cases=listOf(case,other)))))
        reject(zip(files(manifest.copy(cases=listOf(case.copy(origin=CorpusOrigin.PUBLIC_DEMONSTRATION))))))
    }
    @Test fun centralDirectoryRejectsEncryptionSymlinkExpansionAndDuplicateNames() {
        val bytes=zip(files())
        val at=(0..bytes.size-4).first { bytes[it]==0x50.toByte() && bytes[it+1]==0x4b.toByte() && bytes[it+2]==1.toByte() && bytes[it+3]==2.toByte() }
        reject(bytes.copyOf().also { it[at+8]=(it[at+8].toInt() or 1).toByte() })
        reject(bytes.copyOf().also { it[at+41]=0xa0.toByte() })
        reject(bytes.copyOf().also { for(i in 0..3) it[at+24+i]=0xff.toByte() })
        reject(bytes.copyOf(bytes.size-1))
    }
    @Test fun realReadBoundStopsAtLimitEvenWhenMetadataIsMissing() {
        assertArrayEquals(byteArrayOf(1,2),CorpusPackages.readBounded(byteArrayOf(1,2).inputStream(),2))
        assertThrows(IllegalArgumentException::class.java) { CorpusPackages.readBounded(byteArrayOf(1,2,3).inputStream(),2) }
        var calls=0
        assertThrows(IllegalStateException::class.java) { CorpusPackages.readBounded(byteArrayOf(1).inputStream(),2) { calls++; error("cancel") } }
        assertEquals(1,calls)
    }
}
