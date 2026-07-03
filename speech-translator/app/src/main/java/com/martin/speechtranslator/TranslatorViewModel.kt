package com.martin.speechtranslator

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.martin.speechtranslator.audio.AudioRecorder
import com.martin.speechtranslator.llm.DownloadEvent
import com.martin.speechtranslator.llm.GemmaEngine
import com.martin.speechtranslator.llm.ModelDownloadManager
import com.martin.speechtranslator.tts.TtsManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class TranslatorViewModel(application: Application) : AndroidViewModel(application) {

    private val downloader = ModelDownloadManager(application)
    private val recorder = AudioRecorder()
    private val tts = TtsManager(application)
    private var engine: GemmaEngine? = null

    private val _setup = MutableStateFlow<SetupState>(SetupState.AwaitingDownload)
    val setup: StateFlow<SetupState> = _setup

    private val _session = MutableStateFlow<SessionState>(SessionState.Idle())
    val session: StateFlow<SessionState> = _session

    private var recordingTimer: Job? = null
    private var lastResult: TranslationResult? = null

    init {
        when {
            downloader.isModelPresent() -> initializeEngine()
            else -> downloader.activeDownloadId()?.let { trackDownload(it) }
                ?: run { _setup.value = SetupState.AwaitingDownload }
        }
    }

    // ---- Setup -----------------------------------------------------------------

    fun onStartDownload(allowMetered: Boolean) {
        val id = downloader.startDownload(allowMetered)
        trackDownload(id)
    }

    private fun trackDownload(downloadId: Long) {
        _setup.value = SetupState.Downloading(0, ModelDownloadManager.MODEL_SIZE_BYTES)
        viewModelScope.launch {
            downloader.progressFlow(downloadId).collect { event ->
                when (event) {
                    is DownloadEvent.Progress ->
                        _setup.value = SetupState.Downloading(event.downloadedBytes, event.totalBytes)
                    is DownloadEvent.Completed ->
                        if (downloader.isModelPresent()) initializeEngine()
                        else _setup.value = SetupState.Failed("Downloaded file is incomplete — retry")
                    is DownloadEvent.Failed -> _setup.value = SetupState.Failed(event.reason)
                }
            }
        }
    }

    fun onImportModel(uri: Uri) {
        _setup.value = SetupState.Importing(0, ModelDownloadManager.MODEL_SIZE_BYTES)
        viewModelScope.launch {
            try {
                downloader.importFrom(uri) { copied, total ->
                    _setup.value = SetupState.Importing(copied, total)
                }
                initializeEngine()
            } catch (e: Exception) {
                _setup.value = SetupState.Failed(e.message ?: "Import failed")
            }
        }
    }

    private fun initializeEngine() {
        _setup.value = SetupState.InitializingEngine
        viewModelScope.launch {
            try {
                val newEngine = GemmaEngine(downloader.modelFile)
                newEngine.initialize()
                engine = newEngine
                _setup.value = SetupState.Ready
            } catch (e: Exception) {
                _setup.value = SetupState.Failed("Engine failed to start: ${e.message}")
            }
        }
    }

    // ---- Push-to-talk ----------------------------------------------------------

    fun onMicPressed() {
        if (_setup.value != SetupState.Ready) return
        when (_session.value) {
            is SessionState.Recording, SessionState.Translating -> return
            is SessionState.Speaking -> tts.stop() // interrupt-and-listen feels instant
            else -> Unit
        }
        try {
            recorder.start()
        } catch (e: Exception) {
            _session.value = SessionState.Idle(lastResult, hint = "Microphone unavailable")
            return
        }
        _session.value = SessionState.Recording(0)
        recordingTimer = viewModelScope.launch {
            val startedAt = System.currentTimeMillis()
            while (true) {
                delay(100)
                val elapsed = System.currentTimeMillis() - startedAt
                if (elapsed >= AudioRecorder.MAX_DURATION_MS) {
                    onMicReleased() // hard 30 s model limit — auto-stop and translate
                    return@launch
                }
                _session.value = SessionState.Recording(elapsed)
            }
        }
    }

    fun onMicReleased() {
        if (_session.value !is SessionState.Recording) return
        recordingTimer?.cancel()
        recordingTimer = null
        val audio = recorder.stop()
        // WHY: skip inference on accidental taps or silence — a wasted generation costs seconds.
        if (audio.durationMs < 300 || audio.rms < 0.008) {
            _session.value = SessionState.Idle(lastResult, hint = "Didn't catch that — hold and speak")
            return
        }
        _session.value = SessionState.Translating
        viewModelScope.launch {
            val text = try {
                engine?.translate(audio.wavBytes).orEmpty()
            } catch (e: Exception) {
                _session.value = SessionState.Idle(lastResult, hint = "Translation failed: ${e.message}")
                return@launch
            }
            if (text.isBlank()) {
                _session.value = SessionState.Idle(lastResult, hint = "Didn't catch that — try again")
                return@launch
            }
            val result = TranslationResult(text = text, isJapanese = containsJapanese(text))
            lastResult = result
            speak(result)
        }
    }

    fun onReplay() {
        val result = lastResult ?: return
        if (_session.value !is SessionState.Idle) return
        viewModelScope.launch { speak(result) }
    }

    private suspend fun speak(result: TranslationResult) {
        _session.value = SessionState.Speaking(result)
        tts.speak(result.text, japanese = result.isJapanese) {
            _session.value = SessionState.Idle(result)
        }
    }

    override fun onCleared() {
        recordingTimer?.cancel()
        runCatching { recorder.stop() }
        tts.shutdown()
        engine?.close()
    }
}
