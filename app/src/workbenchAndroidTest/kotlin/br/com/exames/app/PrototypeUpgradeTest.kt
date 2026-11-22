package br.com.exames.app

import android.os.Bundle
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import br.com.exames.datalocal.EncryptedArtifactStore
import br.com.exames.datalocal.ProtectedDatabaseFactory
import br.com.exames.datalocal.ProtectedValidationRepository
import br.com.exames.domain.validation.ValidationJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Run against the same installed synthetic database before and after the prototype APK update. */
class PrototypeUpgradeTest {
    @Test fun fingerprintSyntheticReviewHistory() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("br.com.exames.offline.validation.workbench", context.packageName)
        val marker = File(context.noBackupFilesDir, "synthetic-workbench-reopen.json")
        val fixture = JSONObject(marker.readText())
        val documentId = fixture.getString("documentId")
        val runId = fixture.getString("runId")
        val factory = ProtectedDatabaseFactory(context)
        try {
            // This APK runs on both versions. The newer repository adds a defaulted deletion hook.
            val constructor = ProtectedValidationRepository::class.java.constructors.single {
                it.parameterTypes.lastOrNull()?.name == "kotlin.jvm.internal.DefaultConstructorMarker"
            }
            val arguments = arrayOfNulls<Any>(constructor.parameterTypes.size)
            arguments[0] = factory
            arguments[arguments.lastIndex - 1] = -2
            val repo = constructor.newInstance(*arguments) as ProtectedValidationRepository
            val document = requireNotNull(repo.find(documentId))
            val source = EncryptedArtifactStore(context).read(documentId, "original")
            val digest = MessageDigest.getInstance("SHA-256")
            fun add(bytes: ByteArray) {
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array())
                digest.update(bytes)
            }
            add(source)
            source.fill(0)
            add(ValidationJson.format.encodeToString(requireNotNull(repo.findRun(runId))).toByteArray())
            val observations = repo.observations(runId)
            val assessments = repo.assessments(runId)
            assertEquals(5, observations.size)
            assertEquals(5, assessments.size)
            assertEquals(1, repo.groundTruth(documentId).size)
            assertEquals(1, repo.comparisons(runId).size)
            val corrections = observations.flatMap { repo.corrections(it.id) }
            assertEquals(1, corrections.size)
            add(document.toString().toByteArray())
            add(observations.toString().toByteArray())
            add(assessments.toString().toByteArray())
            add(corrections.toString().toByteArray())
            add(repo.groundTruth(documentId).toString().toByteArray())
            add(repo.comparisons(runId).toString().toByteArray())
            val weights = File(context.noBackupFilesDir, "models/" + fixture.getString("sentinel"))
            assertTrue(weights.isFile)
            add(weights.readBytes())
            val report = JSONObject()
                .put("phase", "upgrade-fingerprint")
                .put("pid", Process.myPid())
                .put("observations", observations.size)
                .put("statuses", JSONObject(assessments.groupingBy { it.status.name }.eachCount()))
                .put("fingerprint", digest.digest().joinToString("") { "%02x".format(it) })
            instrumentation.sendStatus(2, Bundle().apply { putString("prototype", report.toString()) })
        } finally { factory.open().close() }
    }
}
