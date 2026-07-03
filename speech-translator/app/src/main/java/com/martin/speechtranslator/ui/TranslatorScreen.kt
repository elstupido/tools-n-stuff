package com.martin.speechtranslator.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.compose.foundation.Image
import com.martin.speechtranslator.SessionState
import com.martin.speechtranslator.SetupState
import com.martin.speechtranslator.TranslatorViewModel

@Composable
fun TranslatorScreen(viewModel: TranslatorViewModel) {
    val setup by viewModel.setup.collectAsState()
    val session by viewModel.session.collectAsState()

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (val s = setup) {
            SetupState.Ready -> SessionContent(session, viewModel)
            else -> SetupContent(s, viewModel)
        }
    }
}

// ---- First-run setup (model download) ------------------------------------------

@Composable
private fun SetupContent(setup: SetupState, viewModel: TranslatorViewModel) {
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let { viewModel.onImportModel(it) } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Speech Translator", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "English ⇄ 日本語 — everything runs on this phone",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(40.dp))

        when (setup) {
            is SetupState.AwaitingDownload -> {
                Text(
                    "One-time setup: download the Gemma 4 translation model (2.4 GB). " +
                        "Wi-Fi recommended. After this the app works fully offline.",
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
                Button(onClick = { viewModel.onStartDownload(allowMetered = false) }) {
                    Text("Download on Wi-Fi")
                }
                TextButton(onClick = { viewModel.onStartDownload(allowMetered = true) }) {
                    Text("Allow cellular data too")
                }
                TextButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) {
                    Text("Import model file instead")
                }
            }
            is SetupState.Downloading -> {
                LinearProgressIndicator(
                    progress = { setup.downloadedBytes.toFloat() / setup.totalBytes },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Downloading model… ${setup.percent}% " +
                        "(${setup.downloadedBytes / (1 shl 20)} / ${setup.totalBytes / (1 shl 20)} MB)"
                )
            }
            is SetupState.Importing -> {
                LinearProgressIndicator(
                    progress = { setup.copiedBytes.toFloat() / setup.totalBytes },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Text("Importing model… ${setup.copiedBytes / (1 shl 20)} MB")
            }
            SetupState.InitializingEngine -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text("Starting the translation engine…")
            }
            is SetupState.Failed -> {
                Text(setup.message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                Spacer(Modifier.height(24.dp))
                Button(onClick = { viewModel.onStartDownload(allowMetered = false) }) {
                    Text("Retry download")
                }
                TextButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) {
                    Text("Import model file instead")
                }
            }
            SetupState.Ready -> Unit
        }
    }
}

// ---- Main push-to-talk screen ---------------------------------------------------

@Composable
private fun SessionContent(session: SessionState, viewModel: TranslatorViewModel) {
    val context = LocalContext.current
    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var permissionDenied by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMicPermission = granted
        permissionDenied = !granted
    }

    val result = when (session) {
        is SessionState.Idle -> session.last
        is SessionState.Speaking -> session.result
        else -> null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Heard-language hint
        Text(
            text = result?.let { "Heard: ${it.heardLanguage}" } ?: "",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))

        // The translation — the thing you show to the other person, so it's big.
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = result?.text ?: "Hold the mic and speak.\nEnglish → 日本語, 日本語 → English.",
                fontSize = if (result != null) 40.sp else 22.sp,
                fontWeight = if (result != null) FontWeight.SemiBold else FontWeight.Normal,
                textAlign = TextAlign.Center,
                color = if (result != null) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Status line
        val status = when (session) {
            is SessionState.Recording -> {
                val remaining = (30_000 - session.elapsedMs) / 1000
                if (remaining <= 10) "Listening… ${remaining}s left" else "Listening…"
            }
            SessionState.Translating -> "Translating…"
            is SessionState.Speaking -> "Speaking…"
            is SessionState.Idle -> session.hint
                ?: if (permissionDenied) "Microphone permission needed" else "Hold to speak"
        }
        Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)

        if (permissionDenied) {
            TextButton(onClick = {
                context.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null),
                    )
                )
            }) { Text("Open app settings") }
        }

        Spacer(Modifier.height(16.dp))

        // Replay last translation
        TextButton(
            onClick = { viewModel.onReplay() },
        ) { Text(if (result != null) "🔊 Replay" else " ") }

        Spacer(Modifier.height(8.dp))

        MicButton(
            active = session is SessionState.Recording,
            busy = session is SessionState.Translating,
            onPress = {
                if (hasMicPermission) viewModel.onMicPressed()
                else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            },
            onRelease = { viewModel.onMicReleased() },
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun MicButton(active: Boolean, busy: Boolean, onPress: () -> Unit, onRelease: () -> Unit) {
    val color = when {
        active -> MaterialTheme.colorScheme.error
        busy -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.primary
    }
    Box(
        modifier = Modifier
            .size(120.dp)
            .background(color = color, shape = CircleShape)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        onPress()
                        tryAwaitRelease()
                        onRelease()
                    }
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        if (busy) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Image(
                painter = rememberVectorPainter(image = micIcon()),
                contentDescription = "Hold to speak",
                modifier = Modifier.size(56.dp),
            )
        }
    }
}

/** Material "mic" glyph inlined so no icon dependency is needed. */
@Composable
private fun micIcon(): ImageVector = remember {
    ImageVector.Builder(
        name = "mic", defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f,
    ).path(fill = SolidColor(Color.White)) {
        moveTo(12f, 14f)
        curveTo(13.66f, 14f, 15f, 12.66f, 15f, 11f)
        verticalLineTo(5f)
        curveTo(15f, 3.34f, 13.66f, 2f, 12f, 2f)
        curveTo(10.34f, 2f, 9f, 3.34f, 9f, 5f)
        verticalLineTo(11f)
        curveTo(9f, 12.66f, 10.34f, 14f, 12f, 14f)
        close()
        moveTo(17f, 11f)
        curveTo(17f, 13.76f, 14.76f, 16f, 12f, 16f)
        curveTo(9.24f, 16f, 7f, 13.76f, 7f, 11f)
        horizontalLineTo(5f)
        curveTo(5f, 14.53f, 7.61f, 17.43f, 11f, 17.92f)
        verticalLineTo(21f)
        horizontalLineTo(13f)
        verticalLineTo(17.92f)
        curveTo(16.39f, 17.43f, 19f, 14.53f, 19f, 11f)
        horizontalLineTo(17f)
        close()
    }.build()
}
