package br.com.exames.datalocal

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.system.Os
import android.system.OsConstants
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import br.com.exames.domain.validation.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

enum class ProtectedMigrationStep { COPY_VERIFIED, SOURCE_RENAMED, TARGET_RENAMED }

/** Copy-and-install migration. Never opens a v1 database as v2 or falls back to deletion. */
class ProtectedDatabaseFactory(
    context: Context,
    private val name: String = "exames.db",
    private val keys: AndroidPayloadKeys = AndroidPayloadKeys(),
    private val afterStep: (ProtectedMigrationStep) -> Unit = {},
) {
    private val context = context.applicationContext
    private val active = context.getDatabasePath(name)
    private val staging = context.getDatabasePath("$name.protected-staging")
    private val backup = context.getDatabasePath("$name.legacy-backup")
    private val marker = File(context.noBackupFilesDir, "$name.protected-migration")
    private val lock = locks.getOrPut(active.absolutePath) { Mutex() }
    val codec = EncryptedPayloadCodec(keys::existing)
    @Volatile private var cached: ProtectedDatabase? = null

    init { require(name.matches(Regex("[a-zA-Z0-9_.-]{1,80}"))) }

    suspend fun open(): ProtectedDatabase = withContext(Dispatchers.IO) {
        lock.withLock {
            cached?.takeIf { it.isOpen }?.let { return@withLock it }
            prepare()
            build(active).also { database ->
                try {
                    database.openHelper.writableDatabase
                    verify(database.openHelper.writableDatabase)
                    cached = database
                } catch (error: Exception) {
                    database.close()
                    throw error
                }
            }
        }
    }

    private fun build(file: File): ProtectedDatabase = Room.databaseBuilder(context,
        ProtectedDatabase::class.java, file.absolutePath)
        .setJournalMode(RoomDatabase.JournalMode.TRUNCATE).build()

    private fun version(file: File): Int? = if (!file.exists()) null else
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { it.version }

    private fun phase(): String? = marker.takeIf { it.exists() }?.readText()?.also {
        check(it in setOf("INITIALIZING", "COPYING", "READY", "INSTALLING", "INSTALLED")) { "Invalid migration state" }
    }

    private fun prepare() {
        active.parentFile!!.mkdirs()
        context.noBackupFilesDir.mkdirs()
        var phase = phase()
        if (phase == null && version(active) == 2) {
            keys.existing()
            return
        }
        if (phase == null) {
            check(version(active) in listOf(null, 1)) { "Unsupported local database version" }
            check(!backup.exists()) { "Unresolved previous migration" }
            writePhase("INITIALIZING")
            phase = "INITIALIZING"
        }
        if (phase == "INITIALIZING") {
            if (keys.exists()) keys.existing() else keys.createForNewStore()
            writePhase("COPYING")
            phase = "COPYING"
        } else keys.existing() // Once copying starts, a missing key is not silently regenerated.
        if (phase == "COPYING") {
            check(version(active) in listOf(null, 1) && !backup.exists()) { "Invalid migration source" }
            removeGroup(staging)
            build(staging).useDatabase { target ->
                val destination = target.openHelper.writableDatabase
                if (active.exists()) copyLegacy(destination) else verify(destination)
            }
            syncDirectory(staging.parentFile!!)
            writePhase("READY")
            afterStep(ProtectedMigrationStep.COPY_VERIFIED)
            phase = "READY"
        }
        if (phase == "READY") {
            check(version(staging) == 2) { "Protected staging database is unavailable" }
            build(staging).useDatabase { verify(it.openHelper.writableDatabase); verifyMigrationPayloads(it.openHelper.writableDatabase) }
            writePhase("INSTALLING")
            phase = "INSTALLING"
        }
        if (phase == "INSTALLING") {
            if (version(active) == 1) {
                check(!backup.exists()) { "Ambiguous migration backup" }
                move(active, backup)
                removeSidecars(active)
                afterStep(ProtectedMigrationStep.SOURCE_RENAMED)
            }
            if (!active.exists()) {
                check(version(staging) == 2) { "Protected destination is unavailable" }
                move(staging, active)
                afterStep(ProtectedMigrationStep.TARGET_RENAMED)
            }
            check(version(active) == 2) { "Invalid protected destination" }
            build(active).useDatabase { verify(it.openHelper.writableDatabase); verifyMigrationPayloads(it.openHelper.writableDatabase) }
            writePhase("INSTALLED")
            phase = "INSTALLED"
        }
        check(phase == "INSTALLED" && version(active) == 2) { "Protected installation incomplete" }
        build(active).useDatabase { verify(it.openHelper.writableDatabase); verifyMigrationPayloads(it.openHelper.writableDatabase) }
        // At this point the verified encrypted destination is durable before deleting the source.
        removeGroup(backup)
        removeGroup(staging)
        check(!marker.exists() || marker.delete()) { "Unable to finish migration journal" }
        syncDirectory(marker.parentFile!!)
    }

    private fun copyLegacy(target: SupportSQLiteDatabase) {
        SQLiteDatabase.openDatabase(active.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { source ->
            check(source.version == 1) { "Unsupported migration source" }
            source.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use {
                check(it.moveToFirst() && it.getInt(0) == 0) { "Legacy writers are still active" }
            }
            source.beginTransaction()
            target.beginTransaction()
            try {
                source.rawQuery("PRAGMA foreign_key_check", null).use { check(!it.moveToFirst()) { "Legacy relationships are invalid" } }
                val documents = mutableMapOf<String, JsonObject>()
                rows(source, "documents") { row ->
                    val id = row.text("id")
                    documents[id] = row
                    insertPayload(target, "documents", "id", id, "document", row,
                        listOf("importedAt"), listOf(row.long("importedAt")))
                }
                val owners = mutableMapOf<String, Pair<String, String>>()
                val runs = mutableSetOf<String>()
                rows(source, "observations") { row ->
                    val id = row.text("id")
                    val documentId = row.text("documentId")
                    val document = requireNotNull(documents[documentId]) { "Legacy document missing" }
                    val runId = StableIds.of("legacy-run", documentId)
                    if (runs.add(runId)) {
                        val run = ProcessingRun(runId, documentId, document.text("hash"), null,
                            ExtractionMode.BASELINE, "legacy-scaffold", "legacy-scaffold", "REF_NUMERIC/1.0.0",
                            if (document.text("processingState") == "COMPLETED") RunState.COMPLETE else RunState.PARTIAL,
                            Instant.ofEpochMilli(document.long("importedAt")).toString(), "legacy-unknown", "legacy-unknown")
                        val payload = codec.encrypt("run", runId, "2", ValidationJson.format.encodeToString(run).toByteArray())
                        target.execSQL("INSERT INTO processing_runs (id,documentId,state,startedAt,payload) VALUES (?,?,?,?,?)",
                            arrayOf(runId, documentId, run.state.name, document.long("importedAt"), payload))
                    }
                    owners[id] = documentId to runId
                    insertPayload(target, "observations", "id", id, "observation", row,
                        listOf("documentId", "runId", "page", "ordinal"),
                        listOf(documentId, runId, row.long("page"), owners.size.toLong()))
                }
                rows(source, "reference_ranges") { row ->
                    val id = row.text("observationId")
                    check(id in owners) { "Legacy reference has no observation" }
                    insertPayload(target, "reference_ranges", "observationId", id, "reference", row)
                }
                rows(source, "assessments") { row ->
                    val id = row.text("observationId")
                    val runId = requireNotNull(owners[id]) { "Legacy assessment has no observation" }.second
                    insertPayload(target, "assessments", "id", id, "assessment", row,
                        listOf("observationId", "runId"), listOf(id, runId))
                }
                rows(source, "manual_corrections") { row ->
                    val id = row.text("id")
                    check(row.text("observationId") in owners) { "Legacy correction has no observation" }
                    insertPayload(target, "manual_corrections", "id", id, "correction", row,
                        listOf("observationId"), listOf(row.text("observationId")))
                }
                rows(source, "pending_deletions") { row ->
                    target.execSQL("INSERT INTO pending_deletions (documentId) VALUES (?)", arrayOf(row.text("documentId")))
                }
                for (table in listOf("documents", "observations", "reference_ranges", "assessments", "manual_corrections", "pending_deletions")) {
                    val expected = source.rawQuery("SELECT COUNT(*) FROM $table", null).use { it.moveToFirst(); it.getLong(0) }
                    val actual = target.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); it.getLong(0) }
                    check(expected == actual) { "Migration count verification failed" }
                }
                verify(target)
                target.setTransactionSuccessful()
                source.setTransactionSuccessful()
            } finally {
                target.endTransaction()
                source.endTransaction()
            }
        }
    }

    private fun insertPayload(db: SupportSQLiteDatabase, table: String, primary: String, id: String,
                              type: String, row: JsonObject, columns: List<String> = emptyList(),
                              values: List<Any> = emptyList()) {
        val plaintext = row.toString().toByteArray(Charsets.UTF_8)
        val payload = codec.encrypt(type, id, "2", plaintext)
        val names = listOf(primary) + columns + "payload"
        db.execSQL("INSERT INTO $table (${names.joinToString(",")}) VALUES (${names.joinToString(",") { "?" }})",
            (listOf(id) + values + listOf(payload)).toTypedArray())
        val stored = db.query("SELECT payload FROM $table WHERE $primary = ?", arrayOf(id)).use {
            check(it.moveToFirst()); it.getBlob(0)
        }
        check(codec.decrypt(type, id, "2", stored).contentEquals(plaintext)) { "Migration payload verification failed" }
    }

    private fun rows(source: SQLiteDatabase, table: String, consume: (JsonObject) -> Unit) {
        source.rawQuery("SELECT * FROM $table ORDER BY 1", null).use { cursor ->
            while (cursor.moveToNext()) {
                consume(JsonObject(cursor.columnNames.mapIndexed { index, name -> name to when (cursor.getType(index)) {
                    Cursor.FIELD_TYPE_NULL -> JsonNull
                    Cursor.FIELD_TYPE_INTEGER -> JsonPrimitive(cursor.getLong(index))
                    Cursor.FIELD_TYPE_FLOAT -> JsonPrimitive(cursor.getDouble(index))
                    Cursor.FIELD_TYPE_STRING -> JsonPrimitive(cursor.getString(index))
                    else -> error("Unsupported legacy field type")
                } }.toMap()))
            }
        }
    }

    private fun verify(db: SupportSQLiteDatabase) {
        db.query("PRAGMA foreign_key_check").use { check(!it.moveToFirst()) { "Protected relationships are invalid" } }
        db.query("PRAGMA integrity_check").use { check(it.moveToFirst() && it.getString(0) == "ok") { "Database integrity check failed" } }
    }

    private fun verifyMigrationPayloads(db: SupportSQLiteDatabase) {
        for ((table, primary, type) in listOf(
            Triple("documents", "id", "document"), Triple("processing_runs", "id", "run"),
            Triple("observations", "id", "observation"), Triple("reference_ranges", "observationId", "reference"),
            Triple("assessments", "id", "assessment"), Triple("manual_corrections", "id", "correction"))) {
            db.query("SELECT $primary,payload FROM $table").use { cursor ->
                while (cursor.moveToNext()) {
                    val plaintext = codec.decrypt(type, cursor.getString(0), "2", cursor.getBlob(1))
                    check(ValidationJson.format.parseToJsonElement(plaintext.toString(Charsets.UTF_8)) is JsonObject)
                }
            }
        }
    }

    private fun writePhase(value: String) {
        val temporary = File(marker.parentFile, "${marker.name}.tmp")
        FileOutputStream(temporary).use { it.write(value.toByteArray(Charsets.US_ASCII)); it.fd.sync() }
        check(temporary.renameTo(marker)) { "Unable to record migration state" }
        syncDirectory(marker.parentFile!!)
    }

    private fun move(source: File, target: File) {
        check(!target.exists() && source.renameTo(target)) { "Unable to install protected database" }
        syncDirectory(target.parentFile!!)
    }

    private fun removeSidecars(file: File) {
        for (suffix in listOf("-wal", "-shm", "-journal")) {
            val sidecar = File(file.absolutePath + suffix)
            check(!sidecar.exists() || sidecar.delete()) { "Unable to finish local cleanup" }
        }
    }

    private fun removeGroup(file: File) {
        removeSidecars(file)
        check(!file.exists() || file.delete()) { "Unable to finish local cleanup" }
        file.parentFile?.takeIf { it.exists() }?.let(::syncDirectory)
    }

    private fun syncDirectory(directory: File) {
        val fd = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
        try { Os.fsync(fd) } finally { Os.close(fd) }
    }

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content
    private fun JsonObject.long(key: String): Long = getValue(key).jsonPrimitive.long

    companion object { private val locks = ConcurrentHashMap<String, Mutex>() }
}
