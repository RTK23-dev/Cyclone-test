package com.cyclone.mobile.ui.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/** Previews use the production renderer and input, not a separate illustration. */
@Preview(name = "Signature glass · Ready", widthDp = 392, heightDp = 114)
@Preview(name = "Signature glass · Narrow", widthDp = 320, heightDp = 114)
@Preview(name = "Signature glass · Large type", widthDp = 392, heightDp = 132, fontScale = 1.6f)
@Composable
private fun ReadySignatureGlassPreview() = SignatureGlassPreview(listening = false)

@Preview(name = "Signature glass · Listening", widthDp = 392, heightDp = 114)
@Composable
private fun ListeningSignatureGlassPreview() = SignatureGlassPreview(listening = true)

@Composable
private fun SignatureGlassPreview(listening: Boolean) {
    Box(Modifier.background(Brush.linearGradient(listOf(Color(0xFF02151C), Color(0xFF165258))))
        .padding(horizontal = 16.dp, vertical = 24.dp)) {
        OverlayAppleComposerBar(
            text = "", onTextChanged = {}, focusRequester = remember { FocusRequester() }, onFocusChanged = {},
            placeholder = if (listening) "Listening…" else "Ask Cyclone",
            menuOpen = false, voiceListening = listening, working = false, paused = false,
            taskKey = "preview", onPause = {}, onStop = {}, onMenu = {}, onDictate = {}, onPrimary = {},
        )
    }
}

@Preview(name = "Task glass · Done · Light device", widthDp = 392, heightDp = 460)
@Preview(name = "Task glass · Done · Large type", widthDp = 320, heightDp = 620, fontScale = 1.5f)
@Composable
private fun SignatureTaskDonePreview() = SignatureTaskPreview(com.cyclone.mobile.runtime.background.TaskPhase.DONE)

@Preview(name = "Task glass · Working", widthDp = 392, heightDp = 460)
@Composable
private fun SignatureTaskWorkingPreview() = SignatureTaskPreview(com.cyclone.mobile.runtime.background.TaskPhase.WORKING)

@Preview(name = "Task glass · Action needed", widthDp = 392, heightDp = 460)
@Composable
private fun SignatureTaskAttentionPreview() = SignatureTaskPreview(com.cyclone.mobile.runtime.background.TaskPhase.REVIEW)

@Preview(name = "Task glass · Failed", widthDp = 392, heightDp = 460)
@Composable
private fun SignatureTaskFailedPreview() = SignatureTaskPreview(com.cyclone.mobile.runtime.background.TaskPhase.FAILED)

@Composable
private fun SignatureTaskPreview(phase: com.cyclone.mobile.runtime.background.TaskPhase) {
    Box(Modifier.background(Brush.linearGradient(listOf(Color.White, Color(0xFF712A78), Color(0xFF195CBB))))
        .padding(16.dp)) {
        com.cyclone.mobile.ui.v32.CycloneAskTaskPanel(
            com.cyclone.mobile.runtime.background.WorkspaceTaskUi(
                taskId = "preview", app = "Chrome", packageName = "com.android.chrome",
                goal = "Open Chrome and go to Facebook", phase = phase,
                message = "Checking the page", outcome = if (phase == com.cyclone.mobile.runtime.background.TaskPhase.DONE)
                    "Chrome is open and Facebook is loaded." else null,
            ),
        )
    }
}
