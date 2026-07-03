package com.martin.speechtranslator

/**
 * Setup progress: the app is unusable until the Gemma model is on disk and the engine is up.
 */
sealed interface SetupState {
    /** First run: model not on disk yet; waiting for the user to start the 2.4 GB download. */
    data object AwaitingDownload : SetupState

    data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : SetupState {
        val percent: Int
            get() = if (totalBytes > 0) ((downloadedBytes * 100) / totalBytes).toInt() else 0
    }

    /** Copying a user-picked .litertlm file into app storage. */
    data class Importing(val copiedBytes: Long, val totalBytes: Long) : SetupState

    data object InitializingEngine : SetupState

    data object Ready : SetupState

    data class Failed(val message: String) : SetupState
}

/** One completed translation, kept on screen after speaking. */
data class TranslationResult(
    val text: String,
    val isJapanese: Boolean,
) {
    /** Label of the detected source language ("English" spoken -> Japanese output). */
    val heardLanguage: String get() = if (isJapanese) "English" else "日本語"
}

/** The push-to-talk session, only meaningful once SetupState.Ready. */
sealed interface SessionState {
    data class Idle(val last: TranslationResult? = null, val hint: String? = null) : SessionState
    data class Recording(val elapsedMs: Long) : SessionState
    data object Translating : SessionState
    data class Speaking(val result: TranslationResult) : SessionState
}

/** WHY: TTS voice is picked from the output script, so a model formatting quirk can never
 *  select the wrong voice. Any kana/kanji in the text means Japanese. */
fun containsJapanese(text: String): Boolean =
    text.any { ch ->
        val code = ch.code
        code in 0x3040..0x30FF || code in 0x4E00..0x9FFF || code in 0xFF66..0xFF9D
    }
