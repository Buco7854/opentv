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
import java.io.RandomAccessFile

/**
 * Storage backend for downloads. Two flavors, decided per item by its stored
 * path: plain filesystem paths in app-private storage (default), or SAF
 * `content://` documents inside a user-chosen folder - visible to file
 * managers and other apps, and movable later. Both support resume.
 */
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

    /**
     * Creates the target for a new download and returns its stored path. Falls
     * back to app-private storage if the user-chosen folder is gone or its
     * permission was revoked - downloading still works, just to the default spot.
     */
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
}
