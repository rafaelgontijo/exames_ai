package br.com.exames.domain.validation

import br.com.exames.domain.usecase.GroundTruthDraft
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.util.zip.ZipInputStream

class CorpusPackage(val manifest: CorpusManifest, val cases: List<Case>) : AutoCloseable {
    class Case(val identity: CorpusCase, val document: ByteArray, val truth: GroundTruthSnapshot)
    override fun close() { cases.forEach { it.document.fill(0) } }
}

/** ZIP stays in memory. No archive path is extracted or trusted as a filesystem path. */
object CorpusPackages {
    const val MAX_ARCHIVE = 128 * 1024 * 1024
    const val MAX_MEMBER = 20 * 1024 * 1024
    const val MAX_JSON = 2 * 1024 * 1024
    private val hash = Regex("[0-9a-f]{64}")
    fun safePath(path: String): Boolean = path.length in 1..200 && Regex("[A-Za-z0-9_./-]+").matches(path) &&
        path.split('/').none { it.isEmpty() || it == "." || it == ".." }
    fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    fun readBounded(input: InputStream, limit: Int, active: () -> Unit = {}): ByteArray {
        val output = ByteArrayOutputStream(); val buffer = ByteArray(8192); var count = 0
        while (true) {
            active(); val read = input.read(buffer, 0, minOf(buffer.size, limit - count + 1))
            if (read < 0) break
            if (read == 0) continue
            count += read; require(count <= limit) { "PACKAGE_BYTE_LIMIT" }; output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }
    private data class Member(val size: Long, val crc: Long, val method: Int)
    private fun directory(bytes: ByteArray): Map<String, Member> {
        fun u16(at: Int): Int { require(at >= 0 && at + 2 <= bytes.size); return (bytes[at].toInt() and 255) or ((bytes[at+1].toInt() and 255) shl 8) }
        fun u32(at: Int): Long = u16(at).toLong() or (u16(at+2).toLong() shl 16)
        val end = (bytes.size-22 downTo maxOf(0, bytes.size-65557)).firstOrNull {
            u32(it) == 0x06054b50L && it + 22 + u16(it+20) == bytes.size
        } ?: error("PACKAGE_DIRECTORY_INVALID")
        require(u16(end+4)==0 && u16(end+6)==0 && u16(end+8)==u16(end+10))
        val count=u16(end+10); require(count in 1..2500)
        val start=u32(end+16); require(start <= bytes.size && start+u32(end+12)==end.toLong())
        var at=start.toInt(); var total=0L; val members=linkedMapOf<String, Member>()
        repeat(count) {
            require(u32(at)==0x02014b50L)
            val flags=u16(at+8); val method=u16(at+10); val compressed=u32(at+20); val size=u32(at+24)
            val length=u16(at+28); val extra=u16(at+30); val comment=u16(at+32)
            require(at+46L+length+extra+comment <= end && u16(at+34)==0)
            val name=bytes.copyOfRange(at+46,at+46+length).toString(Charsets.UTF_8)
            val unixMode=(u32(at+38) ushr 16).toInt()
            require(safePath(name) && flags and 1 == 0 && method in setOf(0,8) && unixMode and 0xf000 != 0xa000)
            require(size<=MAX_MEMBER && size<=maxOf(1L,compressed)*1000 && u32(at+42)<start)
            require(members.put(name,Member(size,u32(at+16),method))==null) { "PACKAGE_DUPLICATE_MEMBER" }
            total+=size; require(total<=MAX_ARCHIVE)
            at+=46+length+extra+comment
        }
        require(at==end && "manifest.json" in members)
        return members
    }
    fun decode(bytes: ByteArray, active: () -> Unit = {}): CorpusPackage {
        require(bytes.size in 22..MAX_ARCHIVE)
        val expected=directory(bytes); val files=linkedMapOf<String,ByteArray>()
        try {
            ZipInputStream(bytes.inputStream()).use { zip ->
                while (true) {
                    active(); val entry=zip.nextEntry ?: break
                    val member=requireNotNull(expected[entry.name]); require(entry.name !in files && entry.method==member.method)
                    val data=readBounded(zip, if (entry.name.endsWith(".json")) MAX_JSON else MAX_MEMBER, active)
                    require(data.size.toLong()==member.size); zip.closeEntry(); require(entry.crc==member.crc)
                    files[entry.name]=data
                }
            }
            require(files.keys==expected.keys)
            fun json(name: String): String = requireNotNull(files[name]).let {
                require(it.size<=MAX_JSON); val text=it.toString(Charsets.UTF_8)
                require(text.toByteArray(Charsets.UTF_8).contentEquals(it)); GroundTruthDraft.validateJson(text,MAX_JSON); text
            }
            val manifestJson=json("manifest.json")
            val manifestObject=ValidationJson.format.parseToJsonElement(manifestJson).jsonObject
            require("schemaVersion" in manifestObject)
            manifestObject.getValue("cases").jsonArray.forEach { require(setOf("transformations","provenance").all(it.jsonObject::containsKey)) }
            val manifest=ValidationJson.format.decodeFromString<CorpusManifest>(manifestJson)
            require(manifest.schemaVersion in setOf("1.0","1.1") && manifest.corpusVersion.isNotBlank() && manifest.generatorVersion.isNotBlank() && hash.matches(manifest.fontSha256))
            require(manifest.cases.size in 1..1000 && manifest.cases.map { it.caseId }.distinct().size==manifest.cases.size)
            require(manifest.cases.groupBy { it.familyId }.values.all { family -> family.map { it.partition }.distinct().size==1 })
            val referenced=mutableSetOf("manifest.json")
            val cases=manifest.cases.map { case ->
                require(hash.matches(case.caseId) && hash.matches(case.familyId) && hash.matches(case.documentSha256) && hash.matches(case.groundTruthSha256))
                require(case.layoutId.isNotBlank() && case.layoutVersion.isNotBlank() && case.documentBytes in 1..MAX_MEMBER.toLong())
                require(case.transformations.all { it.isNotBlank() })
                for (path in listOf(case.documentPath,case.groundTruthPath)) require(safePath(path) && referenced.add(path))
                if (case.origin==CorpusOrigin.SYNTHETIC) require(case.provenance==null)
                else {
                    val p=requireNotNull(case.provenance)
                    val uri=java.net.URI(p.url)
                    require(uri.scheme=="https" && !uri.host.isNullOrBlank() && uri.userInfo==null && p.reuseTerms.isNotBlank() && p.eligibilityReviewed && !p.containsIdentifiableData)
                    LocalDate.parse(p.obtainedOn)
                }
                val document=requireNotNull(files[case.documentPath]); val truthBytes=requireNotNull(files[case.groundTruthPath])
                require(document.size.toLong()==case.documentBytes && sha256(document)==case.documentSha256 && sha256(truthBytes)==case.groundTruthSha256)
                val prefix=when(case.format) {
                    CorpusFormat.PDF_TEXT,CorpusFormat.PDF_SCANNED -> "%PDF-".toByteArray()
                    CorpusFormat.PNG -> byteArrayOf(0x89.toByte(),80,78,71,13,10,26,10)
                    CorpusFormat.JPEG -> byteArrayOf(0xff.toByte(),0xd8.toByte(),0xff.toByte())
                }
                require(document.size>=prefix.size && document.copyOfRange(0,prefix.size).contentEquals(prefix))
                val truthJson=json(case.groundTruthPath)
                val truthObject=ValidationJson.format.parseToJsonElement(truthJson).jsonObject
                require("schemaVersion" in truthObject)
                for(row in truthObject.getValue("observations").jsonArray) {
                    if(manifest.schemaVersion=="1.0") require("evaluationInput" !in row.jsonObject)
                    for(e in row.jsonObject.getValue("evidence").jsonArray) {
                        require("confidence" in e.jsonObject && "rotationDegrees" in e.jsonObject.getValue("region").jsonObject)
                    }
                }
                val truth=ValidationJson.format.decodeFromString<GroundTruthVersion>(truthJson)
                validateTruth(truth,case,manifest.schemaVersion)
                CorpusPackage.Case(case,document,GroundTruthSnapshot(case.groundTruthSha256,truth))
            }
            require(referenced==files.keys)
            val result=CorpusPackage(manifest,cases)
            files.filterKeys { key -> cases.none { it.identity.documentPath==key } }.values.forEach { it.fill(0) }
            return result
        } catch (failure: Throwable) { files.values.forEach { it.fill(0) }; throw failure }
    }
    private fun validateTruth(truth: GroundTruthVersion, case: CorpusCase, schema: String) {
        require(truth.schemaVersion==schema && truth.caseId==case.caseId && truth.version.isNotBlank())
        truth.reviewedAt?.let(Instant::parse)
        if (truth.reviewState==ReviewState.REVIEWED) { require(!truth.reviewerId.isNullOrBlank()); requireNotNull(truth.reviewedAt) }
        require(truth.observations.size in 1..500 && truth.observations.map { it.observationId }.distinct().size==truth.observations.size)
        val number=Regex("[+-]?[0-9]+(?:\\.[0-9]+)?")
        fun decimal(s: String?) { require(s==null || number.matches(s)) }
        for (row in truth.observations) {
            require(hash.matches(row.observationId) && row.resultRaw.isNotEmpty() && row.expectedReason.isNotEmpty())
            listOf(row.numericValue,row.low,row.high).forEach(::decimal)
            require(row.evidence.all { hash.matches(it.spanId) && it.field in setOf("analyteRaw","resultRaw","unitRaw","referenceRaw") &&
                it.method.isNotBlank() && it.extractorVersion.isNotBlank() && it.region.page<=20 && it.region.pageWidth<=20000 && it.region.pageHeight<=20000 })
            require((schema=="1.1")== (row.evaluationInput!=null))
            row.evaluationInput?.let { input ->
                val refs=listOfNotNull(input.selectedReference)+input.candidates.map { it.metadata }
                require(refs.all { it.observationId==row.observationId })
                input.candidates.forEach { decimal(it.low); decimal(it.high) }
                when(input.referenceSelection) {
                    ReferenceSelection.ABSENT -> require(row.referenceRaw==null && row.low==null && row.high==null)
                    ReferenceSelection.UNRESOLVED -> require(row.low==null && row.high==null)
                    else -> Unit
                }
            }
        }
    }
}
