package com.buco7854.opentv.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.util.Base64
import java.util.Properties
import kotlin.io.path.exists

/** Versioned, atomically persisted server preferences. */
class ServerSettings(
    private val dataDir: Path,
    val pageSize: Int,
) {
    @Serializable
    private data class Document(
        val version: Int = 1,
        val userAgent: String = "",
        val downloadConcurrency: Int = 1,
        val streamKey: String,
    )

    private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }
    private val file = dataDir.resolve("server-settings.json")
    private val legacyFile = dataDir.resolve("settings.properties")
    private val lock = Any()
    private var document: Document

    init {
        Files.createDirectories(dataDir)
        document = when {
            file.exists() -> json.decodeFromString(Files.readString(file))
            legacyFile.exists() -> migrateLegacy()
            else -> Document(streamKey = newStreamKey()).also(::save)
        }
        require(document.version == 1) { "Unsupported server settings version ${document.version}" }
    }

    var userAgent: String
        get() = synchronized(lock) { document.userAgent }
        set(value) = update { copy(userAgent = value.trim()) }

    /** Simultaneous background transfers; kept low to respect provider caps. */
    var downloadLimit: Int
        get() = synchronized(lock) { document.downloadConcurrency }
        set(value) = update { copy(downloadConcurrency = value.coerceIn(1, 3)) }

    val streamKey: String
        get() = synchronized(lock) { document.streamKey }

    private fun update(transform: Document.() -> Document) = synchronized(lock) {
        val next = document.transform()
        if (next != document) {
            save(next)
            document = next
        }
    }

    private fun migrateLegacy(): Document {
        val props = Properties().apply {
            legacyFile.toFile().inputStream().use(::load)
        }
        val migrated = Document(
            userAgent = props.getProperty("userAgent", ""),
            downloadConcurrency = (props.getProperty("downloadLimit", "1").toIntOrNull() ?: 1).coerceIn(1, 3),
            streamKey = props.getProperty("streamKey")?.takeIf { it.isNotBlank() } ?: newStreamKey(),
        )
        save(migrated)
        Files.move(
            legacyFile,
            dataDir.resolve("settings.properties.bak"),
            StandardCopyOption.REPLACE_EXISTING,
        )
        return migrated
    }

    private fun save(value: Document) {
        val temp = Files.createTempFile(dataDir, "server-settings", ".tmp")
        try {
            Files.writeString(temp, json.encodeToString(value))
            try {
                Files.move(
                    temp,
                    file,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun newStreamKey(): String =
        Base64.getEncoder().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
}
