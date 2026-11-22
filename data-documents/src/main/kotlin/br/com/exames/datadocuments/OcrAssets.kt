package br.com.exames.datadocuments

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID

/** Application context only. Static OCR weights contain no document/session content. */
internal object OcrAssets {
    const val MODEL_BYTES=1982756L
    const val MODEL_SHA256="c4932b937207a9514b7514d518b931a99938c02a28a5a5a553f8599ed58b7deb"
    @Volatile private var application: Context?=null
    fun initialize(context: Context) { application=context.applicationContext }
    private fun digest(file: File): String = file.inputStream().use { stream ->
        val hash=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(8192)
        while(true) { val n=stream.read(buffer);if(n<0) break;if(n>0) hash.update(buffer,0,n) }
        hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    @Synchronized fun prepare(): File {
        val context=checkNotNull(application) { "OCR_NOT_INITIALIZED" }
        val root=File(context.noBackupFilesDir,"ocr");val data=File(root,"tessdata")
        check(data.isDirectory || data.mkdirs()) { "OCR_STORAGE_UNAVAILABLE" }
        RandomAccessFile(File(root,".prepare.lock"),"rw").use { lock -> lock.channel.use { channel -> channel.lock().use {
            val target=File(data,"por.traineddata")
            if(target.isFile && target.length()==MODEL_BYTES && digest(target)==MODEL_SHA256) return root
            check(data.usableSpace>=MODEL_BYTES+1024*1024) { "OCR_MODEL_SPACE" }
            val temp=File(data,"${UUID.randomUUID()}.pending")
            try {
                context.assets.open("ocr/por.traineddata").use { input -> FileOutputStream(temp).use { output ->
                    val buffer=ByteArray(8192);var bytes=0L
                    while(true) {
                        val n=input.read(buffer);if(n<0) break
                        if(n>0) { bytes+=n;check(bytes<=MODEL_BYTES) { "OCR_MODEL_CORRUPT" };output.write(buffer,0,n) }
                    }
                    output.fd.sync()
                } }
                check(temp.length()==MODEL_BYTES && digest(temp)==MODEL_SHA256) { "OCR_MODEL_CORRUPT" }
                android.system.Os.rename(temp.absolutePath,target.absolutePath)
            } finally { temp.delete() }
            return root
        } } }
    }
}

/** Private initializer; no external data interface, downloads, analytics or worker creation. */
class OcrInitializer : ContentProvider() {
    override fun onCreate(): Boolean { OcrAssets.initialize(requireNotNull(context));return true }
    override fun query(uri: Uri,projection: Array<out String>?,selection: String?,selectionArgs: Array<out String>?,sortOrder: String?): Cursor? = throw UnsupportedOperationException()
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri,values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri,selection: String?,selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri,values: ContentValues?,selection: String?,selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}
