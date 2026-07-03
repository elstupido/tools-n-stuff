package com.martin.speechtranslator.llm

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

sealed interface DownloadEvent {
    data class Progress(val downloadedBytes: Long, val totalBytes: Long) : DownloadEvent
    data object Completed : DownloadEvent
    data class Failed(val reason: String) : DownloadEvent
}

/**
 * Fetches the Gemma 4 E2B .litertlm (~2.4 GB) with the system DownloadManager so the
 * transfer is resumable and survives the app being killed. Also supports importing a
 * model file the user obtained manually (SAF).
 */
class ModelDownloadManager(private val context: Context) {

    companion object {
        const val MODEL_FILE_NAME = "gemma-4-E2B-it.litertlm"
        const val MODEL_URL =
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"
        const val MODEL_SIZE_BYTES = 2_588_147_712L
        private const val MODELS_SUBDIR = "models"
        private const val PREFS = "model_download"
        private const val KEY_DOWNLOAD_ID = "download_id"
    }

    private val downloadManager =
        context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val modelFile: File
        get() = File(File(context.getExternalFilesDir(null), MODELS_SUBDIR), MODEL_FILE_NAME)

    /** WHY: size sanity check (not just exists()) so a partial file never reaches the engine. */
    fun isModelPresent(): Boolean = modelFile.length() >= MODEL_SIZE_BYTES

    /** Returns the id of a download already running (e.g. app was killed mid-download), or null. */
    fun activeDownloadId(): Long? {
        val id = prefs.getLong(KEY_DOWNLOAD_ID, -1L)
        if (id == -1L) return null
        val status = queryStatus(id)?.first
        return if (status == DownloadManager.STATUS_RUNNING ||
            status == DownloadManager.STATUS_PENDING ||
            status == DownloadManager.STATUS_PAUSED ||
            status == DownloadManager.STATUS_SUCCESSFUL
        ) id else null
    }

    fun startDownload(allowMetered: Boolean): Long {
        modelFile.parentFile?.mkdirs()
        modelFile.delete()
        val request = DownloadManager.Request(Uri.parse(MODEL_URL))
            .setTitle("Gemma 4 translation model")
            .setDestinationInExternalFilesDir(context, null, "$MODELS_SUBDIR/$MODEL_FILE_NAME")
            .setAllowedOverMetered(allowMetered)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
        val id = downloadManager.enqueue(request)
        prefs.edit().putLong(KEY_DOWNLOAD_ID, id).apply()
        return id
    }

    /** Polls DownloadManager until the download finishes or fails. */
    fun progressFlow(downloadId: Long): Flow<DownloadEvent> = flow {
        while (true) {
            val (status, pair) = queryStatus(downloadId)?.let { it.first to it.second }
                ?: run {
                    emit(DownloadEvent.Failed("Download disappeared — please retry"))
                    return@flow
                }
            val (downloaded, total) = pair
            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    prefs.edit().remove(KEY_DOWNLOAD_ID).apply()
                    emit(DownloadEvent.Completed)
                    return@flow
                }
                DownloadManager.STATUS_FAILED -> {
                    prefs.edit().remove(KEY_DOWNLOAD_ID).apply()
                    emit(DownloadEvent.Failed("Download failed — check connection and retry"))
                    return@flow
                }
                else -> emit(DownloadEvent.Progress(downloaded, total))
            }
            delay(500)
        }
    }.flowOn(Dispatchers.IO)

    /** @return status to (downloadedBytes to totalBytes), or null if the id is unknown. */
    private fun queryStatus(downloadId: Long): Pair<Int, Pair<Long, Long>>? {
        val cursor = downloadManager.query(DownloadManager.Query().setFilterById(downloadId))
        cursor.use {
            if (!it.moveToFirst()) return null
            val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val downloaded =
                it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                .let { t -> if (t <= 0) MODEL_SIZE_BYTES else t }
            return status to (downloaded to total)
        }
    }

    /** Copies a user-picked .litertlm into app storage, reporting progress. */
    suspend fun importFrom(uri: Uri, onProgress: (copied: Long, total: Long) -> Unit) =
        withContext(Dispatchers.IO) {
            modelFile.parentFile?.mkdirs()
            val total = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
                ?: MODEL_SIZE_BYTES
            val tmp = File(modelFile.parentFile, "$MODEL_FILE_NAME.tmp")
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Cannot open the selected file" }
                tmp.outputStream().use { output ->
                    val buf = ByteArray(1 shl 20)
                    var copied = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        copied += n
                        onProgress(copied, total)
                    }
                }
            }
            check(tmp.length() >= MODEL_SIZE_BYTES) { "Selected file is not the full model" }
            check(tmp.renameTo(modelFile)) { "Could not move model into place" }
        }
}
