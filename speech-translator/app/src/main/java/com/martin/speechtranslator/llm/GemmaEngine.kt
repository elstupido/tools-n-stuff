package com.martin.speechtranslator.llm

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import java.io.Closeable
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Owns the LiteRT-LM engine running Gemma 4 E2B and does audio -> translated text.
 * The audio clip goes straight to the model — no separate speech-recognition step.
 */
class GemmaEngine(private val modelFile: File) : Closeable {

    companion object {
        private const val INSTRUCTION =
            "The audio is speech in either English or Japanese. " +
                "Translate it into the other language: if the speech is English, translate it " +
                "into natural spoken Japanese; if the speech is Japanese, translate it into " +
                "natural spoken English. Output ONLY the translation, with no explanations, " +
                "no romaji, no quotes, and no other text."
    }

    private var engine: Engine? = null

    /** WHY: one generation at a time — the engine is not safe for concurrent conversations. */
    private val inferenceMutex = Mutex()

    /**
     * Heavy (up to ~10 s): must run off the main thread. Tries the GPU backend first for
     * ~0.3 s time-to-first-token, falls back to CPU if the device rejects OpenCL.
     */
    suspend fun initialize() = withContext(Dispatchers.IO) {
        try {
            engine = createAndInit(Backend.GPU())
        } catch (gpuFailure: Exception) {
            engine = createAndInit(Backend.CPU())
        }
    }

    private fun createAndInit(backend: Backend): Engine {
        val config = EngineConfig(
            modelPath = modelFile.absolutePath,
            backend = backend,
            // WHY: the audio encoder path needs its own backend or audio input is disabled.
            audioBackend = Backend.CPU(),
        )
        return Engine(config).also { it.initialize() }
    }

    /**
     * @param wavBytes complete mono 16 kHz WAV file bytes, max 30 s.
     * @return the translation text.
     */
    suspend fun translate(wavBytes: ByteArray): String = inferenceMutex.withLock {
        withContext(Dispatchers.IO) {
            val engine = checkNotNull(engine) { "Engine not initialized" }
            // WHY: a fresh conversation per utterance keeps the context window from growing
            // across translations (each one is independent).
            engine.createConversation().use { conversation ->
                val response = conversation.sendMessage(
                    Contents.of(
                        Content.AudioBytes(wavBytes),
                        Content.Text(INSTRUCTION),
                    )
                )
                cleanUp(response.toString())
            }
        }
    }

    /** Strips wrapper quotes/whitespace the model occasionally adds despite the instruction. */
    private fun cleanUp(raw: String): String =
        raw.trim().removeSurrounding("\"").removeSurrounding("「", "」").trim()

    override fun close() {
        runCatching { engine?.close() }
        engine = null
    }
}
