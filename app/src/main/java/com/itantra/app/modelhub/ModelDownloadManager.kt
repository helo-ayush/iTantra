package com.itantra.app.modelhub

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream

/** Per-language lifecycle state of a model pack download. */
sealed interface ModelDownloadState {
    data object Idle : ModelDownloadState

    data class Downloading(val progressBytes: Long, val totalBytes: Long) : ModelDownloadState {
        /** Fraction complete in 0..1. */
        val progress: Float
            get() = if (totalBytes > 0) {
                (progressBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
            } else 0f
    }

    data class Paused(val progressBytes: Long, val totalBytes: Long) : ModelDownloadState {
        /** Fraction complete in 0..1. */
        val progress: Float
            get() = if (totalBytes > 0) {
                (progressBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
            } else 0f
    }

    data object Verifying : ModelDownloadState
    data object Extracting : ModelDownloadState
    data object Installed : ModelDownloadState
    data object Cancelled : ModelDownloadState
    data class Error(val message: String) : ModelDownloadState
}

/**
 * A catalogue entry joined with its live download state and on-disk
 * installation flag. This is the UI-facing shape of the model hub.
 */
data class LanguageModelPack(
    val languageTag: String,
    val name: String,
    val script: String,
    val iso: String,
    val sizeMb: Double,
    val sha256: String,
    val isInstalled: Boolean,
    val downloadState: ModelDownloadState
)

/**
 * Downloads, verifies and extracts language packs on demand using plain
 * [HttpURLConnection] — no networking libraries. A pack is downloaded to a
 * temp file in [Context.getCacheDir], SHA-256 verified against the catalogue,
 * then extracted into `filesDir/models/{languageTag}/`. The temp file is
 * deleted on completion or failure, duplicate concurrent downloads of the
 * same language are refused, and already-installed packs are not re-fetched.
 *
 * Every download is independently controllable: it can be paused (keeping the
 * partial `.part` file for a later HTTP-Range resume), resumed, or cancelled
 * (deleting the partial file and any half-extracted destination directory).
 *
 * All blocking work runs on [Dispatchers.IO]. State is published straight
 * from the IO thread — [MutableStateFlow] is thread-safe, and hopping to the
 * main dispatcher here would let a busy UI thread throttle the copy loop.
 */
class ModelDownloadManager(
    private val context: Context,
    private val storageManager: ModelStorageManager,
    private val scope: CoroutineScope,
    private val onPackInstalled: suspend (String) -> Unit = {}
) {

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
        const val PROGRESS_UPDATE_INTERVAL_MS = 200L
        const val IO_BUFFER_BYTES = 64 * 1024
        const val USER_AGENT = "iTantra/2.4.0 (offline-first disaster mesh)"
    }

    /** Control signal consumed by the blocking worker loops each iteration. */
    private enum class DownloadControl { RUN, PAUSE, CANCEL }

    /** Mutable bookkeeping for one in-flight (or paused) download. */
    private class ActiveDownload(
        val control: MutableStateFlow<DownloadControl>,
        val tempFile: File,
        val destDir: File
    ) {
        @Volatile var progressBytes: Long = 0L
        @Volatile var totalBytes: Long = 0L
        @Volatile var job: Job? = null
    }

    /**
     * Private control-flow exception thrown from inside the blocking loops so
     * a pause/cancel request unwinds cleanly (streams are closed by their
     * `use` blocks) instead of continuing to copy/hash/extract.
     */
    private class DownloadControlSignal(val state: ModelDownloadState) : Exception()

    private val _states = MutableStateFlow<Map<String, ModelDownloadState>>(emptyMap())

    /** Download state per language tag. */
    val states: StateFlow<Map<String, ModelDownloadState>> = _states.asStateFlow()

    /** Active (and paused, awaiting resume/cancel) downloads keyed by tag. */
    private val activeDownloads = ConcurrentHashMap<String, ActiveDownload>()

    /**
     * Starts downloading [language] if it is not already installed and no
     * download for it is already in flight. Re-entering for a paused tag
     * resumes it via an HTTP Range request from the retained `.part` file.
     */
    fun download(language: CatalogueLanguage) {
        val tag = language.languageTag

        if (storageManager.isInstalled(tag)) {
            // Verified pack is already on disk — nothing to do.
            activeDownloads.remove(tag)
            _states.update { it + (tag to ModelDownloadState.Installed) }
            return
        }

        val existing = activeDownloads[tag]
        if (existing != null && existing.job?.isActive == true) {
            return // duplicate concurrent download refused
        }

        if (existing == null) {
            val entry = ActiveDownload(
                control = MutableStateFlow(DownloadControl.RUN),
                tempFile = File(context.cacheDir, "${language.archive}.part"),
                destDir = File(File(context.filesDir, "models"), tag)
            )
            entry.totalBytes = language.sizeBytes
            val winner = activeDownloads.putIfAbsent(tag, entry)
            if (winner != null) {
                // Lost a concurrent race; resume/refuse against the winner.
                if (winner.job?.isActive == true) return
                winner.control.value = DownloadControl.RUN
                launchWorker(language, winner)
                return
            }
            _states.update { it + (tag to ModelDownloadState.Downloading(0L, language.sizeBytes)) }
            launchWorker(language, entry)
        } else {
            // Resume a paused download. The worker is already finished (its
            // job is inactive); restart it against the retained `.part` file.
            existing.control.value = DownloadControl.RUN
            val partLen = existing.tempFile.takeIf { it.exists() }?.length() ?: 0L
            val total = existing.totalBytes.takeIf { it > 0 } ?: language.sizeBytes
            _states.update { it + (tag to ModelDownloadState.Downloading(partLen, total)) }
            launchWorker(language, existing)
        }
    }

    /**
     * Pauses an in-flight download, keeping the partial `.part` file. No-op
     * for a tag with no active (or paused) download.
     */
    fun pause(languageTag: String) {
        val entry = activeDownloads[languageTag] ?: return
        val snapshot = when (val current = _states.value[languageTag]) {
            is ModelDownloadState.Paused -> current
            is ModelDownloadState.Downloading ->
                ModelDownloadState.Paused(current.progressBytes, current.totalBytes)
            else -> ModelDownloadState.Paused(entry.progressBytes, entry.totalBytes)
        }
        entry.control.value = DownloadControl.PAUSE
        // Optimistic feedback; the worker confirms when it unwinds.
        _states.update { it + (languageTag to snapshot) }
    }

    /**
     * Cancels a download — active or paused. The partial `.part` file and any
     * half-extracted destination directory are removed and the state becomes
     * [ModelDownloadState.Cancelled].
     */
    fun cancel(languageTag: String) {
        val entry = activeDownloads[languageTag] ?: return
        entry.control.value = DownloadControl.CANCEL
        // Optimistic feedback; the worker confirms when it exits.
        _states.update { it + (languageTag to ModelDownloadState.Cancelled) }
        if (entry.job?.isActive != true) {
            // A paused download has no live worker — clean up inline.
            entry.tempFile.delete()
            if (entry.destDir.exists()) entry.destDir.deleteRecursively()
            activeDownloads.remove(languageTag)
        }
    }

    /** Clears the state entry for [languageTag] (e.g. after a pack deletion). */
    fun resetState(languageTag: String) {
        _states.update { it - languageTag }
    }

    /** Clears all state entries (e.g. after an emergency wipe). */
    fun clearStates() {
        _states.value = emptyMap()
    }

    /** Launches the blocking worker for [entry], publishing its final state. */
    private fun launchWorker(language: CatalogueLanguage, entry: ActiveDownload) {
        val tag = language.languageTag
        entry.job = scope.launch {
            try {
                val finalState = withContext(Dispatchers.IO) { downloadAndInstall(language, entry) }
                _states.update { it + (tag to finalState) }
                when (finalState) {
                    is ModelDownloadState.Installed -> {
                        storageManager.rescan()
                        onPackInstalled(tag)
                        activeDownloads.remove(tag)
                    }
                    is ModelDownloadState.Cancelled, is ModelDownloadState.Error -> {
                        activeDownloads.remove(tag)
                    }
                    else -> {
                        // Paused: keep the entry so cancel/resume can find it.
                    }
                }
            } catch (e: CancellationException) {
                // Scope cancelled (e.g. ViewModel cleared): drop the entry.
                activeDownloads.remove(tag)
                throw e
            }
        }
    }

    private suspend fun downloadAndInstall(language: CatalogueLanguage, entry: ActiveDownload): ModelDownloadState {
        val tag = language.languageTag
        val tempFile = entry.tempFile
        val destDir = entry.destDir

        try {
            val existingLen = tempFile.takeIf { it.exists() }?.length() ?: 0L
            if (existingLen <= 0L) {
                // Fresh download: a leftover partial extraction would block install.
                if (destDir.exists()) destDir.deleteRecursively()
            }

            // --- Phase 1: stream the archive to a temp file (Range-aware) ---
            downloadToFile(language, entry, tempFile, existingLen)

            // --- Phase 2: verify the archive against the catalogue SHA-256 --
            throwIfNotRunning(entry)
            publishState(tag) { ModelDownloadState.Verifying }
            val actualSha256 = sha256Of(tempFile, entry)
            if (!actualSha256.equals(language.sha256, ignoreCase = true)) {
                tempFile.delete()
                return ModelDownloadState.Error("SHA-256 verification failed")
            }

            // --- Phase 3: extract into filesDir/models/{languageTag} --------
            throwIfNotRunning(entry)
            publishState(tag) { ModelDownloadState.Extracting }
            extractZip(tempFile, destDir, entry)
            tempFile.delete()
            return ModelDownloadState.Installed
        } catch (e: DownloadControlSignal) {
            return when (val s = e.state) {
                is ModelDownloadState.Paused -> s // keep the .part for resume
                is ModelDownloadState.Cancelled -> {
                    tempFile.delete()
                    if (destDir.exists()) destDir.deleteRecursively()
                    s
                }
                else -> s
            }
        } catch (e: CancellationException) {
            tempFile.delete()
            throw e
        } catch (e: Exception) {
            tempFile.delete()
            return ModelDownloadState.Error(e.message ?: "Download failed")
        }
    }

    /** Streams the archive to [tempFile], honoring HTTP Range for resumption. */
    private suspend fun downloadToFile(
        language: CatalogueLanguage,
        entry: ActiveDownload,
        tempFile: File,
        existingLen: Long
    ) {
        val tag = language.languageTag
        val connection = URL(ModelCatalogue.DOWNLOAD_BASE_URL + language.archive)
            .openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", USER_AGENT)

            if (existingLen > 0) {
                connection.setRequestProperty("Range", "bytes=$existingLen-")
            }

            when (val code = connection.responseCode) {
                416 -> {
                    // Range not satisfiable — the local .part is already the
                    // full file, so skip straight to verification.
                    entry.progressBytes = existingLen
                    entry.totalBytes = existingLen
                    publishState(tag) {
                        ModelDownloadState.Downloading(existingLen, existingLen)
                    }
                }
                206 -> appendToTemp(language, entry, connection, tempFile, append = true, startOffset = existingLen)
                in 200..299 -> appendToTemp(language, entry, connection, tempFile, append = false, startOffset = 0L)
                else -> throw IOException("Server responded HTTP $code")
            }
        } finally {
            connection.disconnect()
        }
    }

    /** Copies the response body into [tempFile], publishing throttled progress. */
    private suspend fun appendToTemp(
        language: CatalogueLanguage,
        entry: ActiveDownload,
        connection: HttpURLConnection,
        tempFile: File,
        append: Boolean,
        startOffset: Long
    ) {
        val tag = language.languageTag
        val contentLength = connection.contentLengthLong.takeIf { it > 0 } ?: 0L
        val totalBytes = if (append) {
            parseContentRangeTotal(connection)
                ?: (startOffset + contentLength).takeIf { it > startOffset }
                ?: language.sizeBytes
        } else {
            contentLength.takeIf { it > 0 } ?: language.sizeBytes
        }
        entry.totalBytes = totalBytes

        var readBytes = startOffset
        var lastUpdateAt = 0L

        connection.inputStream.use { input ->
            FileOutputStream(tempFile, append).use { output ->
                val buffer = ByteArray(IO_BUFFER_BYTES)
                while (true) {
                    throwIfNotRunning(entry)
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    readBytes += count
                    entry.progressBytes = readBytes
                    entry.totalBytes = totalBytes
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastUpdateAt >= PROGRESS_UPDATE_INTERVAL_MS) {
                        lastUpdateAt = now
                        publishState(tag) { ModelDownloadState.Downloading(readBytes, totalBytes) }
                    }
                }
            }
        }
        entry.progressBytes = readBytes
        entry.totalBytes = totalBytes
        publishState(tag) { ModelDownloadState.Downloading(readBytes, totalBytes) }
    }

    /** Parses the total byte count from a `Content-Range: bytes start-end/total` header. */
    private fun parseContentRangeTotal(connection: HttpURLConnection): Long? {
        val header = connection.getHeaderField("Content-Range") ?: return null
        val slash = header.lastIndexOf('/')
        if (slash < 0 || slash == header.length - 1) return null
        return header.substring(slash + 1).trim().toLongOrNull()
    }

    /** Throws [DownloadControlSignal] unless the download is still running. */
    private fun throwIfNotRunning(entry: ActiveDownload) {
        when (entry.control.value) {
            DownloadControl.RUN -> Unit
            DownloadControl.PAUSE -> throw DownloadControlSignal(
                ModelDownloadState.Paused(entry.progressBytes, entry.totalBytes)
            )
            DownloadControl.CANCEL -> throw DownloadControlSignal(ModelDownloadState.Cancelled)
        }
    }

    /** Publishes a state transition; safe to call from any thread. */
    private fun publishState(tag: String, state: () -> ModelDownloadState) {
        _states.update { it + (tag to state()) }
    }

    private fun sha256Of(file: File, entry: ActiveDownload): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(IO_BUFFER_BYTES)
            while (true) {
                throwIfNotRunning(entry)
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Extracts [zipFile] into [destDir], rejecting entries that would escape
     * the destination directory (zip-slip protection). Each entry checks the
     * control signal so pause/cancel stays responsive during extraction.
     */
    private fun extractZip(zipFile: File, destDir: File, entry: ActiveDownload) {
        destDir.mkdirs()
        val canonicalDest = destDir.canonicalPath

        ZipInputStream(FileInputStream(zipFile).buffered()).use { zip ->
            var zipEntry = zip.nextEntry
            while (zipEntry != null) {
                throwIfNotRunning(entry)
                val target = File(destDir, zipEntry.name).canonicalFile
                if (!target.path.startsWith(canonicalDest + File.separator)) {
                    throw IOException("Archive entry escapes destination: ${zipEntry.name}")
                }
                if (zipEntry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { output ->
                        val buffer = ByteArray(IO_BUFFER_BYTES)
                        while (true) {
                            throwIfNotRunning(entry)
                            val count = zip.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                }
                zip.closeEntry()
                zipEntry = zip.nextEntry
            }
        }
    }
}
