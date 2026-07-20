package com.buco7854.opentv.download

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import com.buco7854.opentv.diag.ErrorLog
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile

/** Download storage: app-private filesystem paths (default) or SAF `content://` docs, chosen per item by path. Both resumable. */
object DownloadStorage {

    fun isContentUri(path: String) = path.startsWith("content://")

    fun length(context: Context, path: String): Long = when {
        path.isEmpty() -> 0L
        isContentUri(path) -> runCatching {
            DocumentFile.fromSingleUri(context, Uri.parse(path))
                ?.takeIf { it.exists() }?.length() ?: 0L
        }.getOrDefault(0L)
        else -> File(path).takeIf { it.exists() }?.length() ?: 0L
    }

    fun delete(context: Context, path: String) {
        if (path.isEmpty()) return
        if (isContentUri(path)) {
            runCatching { DocumentFile.fromSingleUri(context, Uri.parse(path))?.delete() }
        } else {
            File(path).delete()
        }
    }

    /** URI string ExoPlayer can play directly. */
    fun playableUri(path: String): String =
        if (isContentUri(path)) path else File(path).toURI().toString()

    interface Sink : Closeable {
        fun write(buffer: ByteArray, offset: Int, length: Int)
    }

    /** Opens the target for writing, positioned at [resumeAt] (0 truncates). */
    fun openSink(context: Context, path: String, resumeAt: Long): Sink {
        return if (isContentUri(path)) {
            val pfd = context.contentResolver.openFileDescriptor(Uri.parse(path), "rw")
                ?: throw IOException("Cannot open download target")
            val stream = FileOutputStream(pfd.fileDescriptor)
            if (resumeAt > 0) stream.channel.position(resumeAt) else stream.channel.truncate(0)
            object : Sink {
                override fun write(buffer: ByteArray, offset: Int, length: Int) =
                    stream.write(buffer, offset, length)

                override fun close() {
                    stream.close()
                    pfd.close()
                }
            }
        } else {
            val file = File(path)
            file.parentFile?.mkdirs()
            val raf = RandomAccessFile(file, "rw")
            if (resumeAt > 0) raf.seek(resumeAt) else raf.setLength(0)
            object : Sink {
                override fun write(buffer: ByteArray, offset: Int, length: Int) =
                    raf.write(buffer, offset, length)

                override fun close() = raf.close()
            }
        }
    }

    /** Creates the target and returns its stored path; falls back to app storage if the chosen folder is gone/revoked. */
    fun createTarget(context: Context, treeUri: String, baseName: String, extension: String): String {
        if (treeUri.isNotEmpty()) {
            try {
                val tree = DocumentFile.fromTreeUri(context, Uri.parse(treeUri))
                if (tree != null && tree.canWrite()) {
                    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                    // With a known mime the provider appends the extension itself.
                    val doc = if (mime != null) tree.createFile(mime, baseName)
                    else tree.createFile("application/octet-stream", "$baseName.$extension")
                    if (doc != null) return doc.uri.toString()
                }
                ErrorLog.log("Download folder", message = "Chosen folder is not writable; using app storage")
            } catch (e: Exception) {
                ErrorLog.log("Download folder", e)
            }
        }
        val dir = File(
            context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir,
            "OpenTV"
        )
        return File(dir, "$baseName.$extension").absolutePath
    }

    /** Human-readable label for the settings screen. */
    fun describeTree(treeUri: String): String =
        if (treeUri.isEmpty()) "App storage (default)"
        else Uri.parse(treeUri).lastPathSegment?.substringAfter(':')?.ifEmpty { "/" }
            ?.let { "/$it" } ?: treeUri

    sealed interface Relocation {
        /** Already in the destination; nothing to do. */
        object AlreadyThere : Relocation
        class Moved(val newPath: String) : Relocation
        class Failed(val reason: String) : Relocation
    }

    private fun displayName(context: Context, path: String): String =
        if (isContentUri(path)) {
            DocumentFile.fromSingleUri(context, Uri.parse(path))?.name ?: "video.mp4"
        } else {
            File(path).name
        }

    private fun openInput(context: Context, path: String) =
        if (isContentUri(path)) context.contentResolver.openInputStream(Uri.parse(path))
        else File(path).inputStream()

    /** True when [sourcePath] already lives in the destination [treeUri]. */
    private fun alreadyIn(treeUri: String, sourcePath: String): Boolean {
        if (treeUri.isEmpty()) return !isContentUri(sourcePath) // app storage = plain files
        if (!isContentUri(sourcePath)) return false
        return runCatching {
            val tree = Uri.parse(treeUri)
            val src = Uri.parse(sourcePath)
            val treeDocId = android.provider.DocumentsContract.getTreeDocumentId(tree)
            val srcDocId = android.provider.DocumentsContract.getDocumentId(src)
            src.authority == tree.authority && srcDocId.startsWith(treeDocId)
        }.getOrDefault(false)
    }

    /** True when [sourcePath] would actually move into [treeUri]. */
    fun relocateNeeded(context: Context, treeUri: String, sourcePath: String): Boolean =
        sourcePath.isNotEmpty() && !alreadyIn(treeUri, sourcePath)

    /** Moves a file into [treeUri]: copy then delete source; a partial target is cleaned up on failure. */
    fun relocate(context: Context, treeUri: String, sourcePath: String): Relocation {
        if (sourcePath.isEmpty()) return Relocation.Failed("no file")
        if (alreadyIn(treeUri, sourcePath)) return Relocation.AlreadyThere

        val name = displayName(context, sourcePath)
        val baseName = name.substringBeforeLast('.', name)
        val extension = name.substringAfterLast('.', "mp4")
        val targetPath = createTarget(context, treeUri, baseName, extension)
        // createTarget's app-storage fallback may land on the same file.
        if (targetPath == sourcePath) return Relocation.AlreadyThere

        return try {
            openInput(context, sourcePath)?.use { input ->
                openSink(context, targetPath, resumeAt = 0).use { sink ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        sink.write(buffer, 0, read)
                    }
                }
            } ?: return Relocation.Failed("cannot read source")
            delete(context, sourcePath)
            Relocation.Moved(targetPath)
        } catch (e: Exception) {
            ErrorLog.log("Move download", e)
            delete(context, targetPath) // remove the partial copy; source is intact
            Relocation.Failed(ErrorLog.describe(e))
        }
    }
}
