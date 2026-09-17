package com.cyclone.mobile.ui.v32

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CropOriginal
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.ai.CycloneAiAccessProfile
import com.cyclone.mobile.ai.OpenRouterModelPreset
import com.cyclone.mobile.ai.OpenRouterModelPresets
import com.cyclone.mobile.ai.OpenRouterSecretStore
import com.cyclone.mobile.ai.QuickAgentConfig
import com.cyclone.mobile.ai.QuickAgentResult
import com.cyclone.mobile.ai.RequestDispatch
import com.cyclone.mobile.ai.RequestIntent
import com.cyclone.mobile.ai.RequestIntentRouter
import com.cyclone.mobile.ai.model.ModelRegistry
import com.cyclone.mobile.runtime.background.WorkspaceTasks
import com.cyclone.mobile.ui.overlay.OverlayChromeRuntime
import com.cyclone.mobile.ui.overlay.OverlayChromeState
import com.cyclone.mobile.ui.overlay.PendingTaskAttachment
import com.cyclone.mobile.ui.overlay.TaskAttachment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal enum class V39ChatRole { USER, CYCLONE }
internal data class V39ChatMessage(val id: Long, val role: V39ChatRole, val text: String, val ok: Boolean? = null)

/** Process-session chat only. Brain owns persistent run diagnostics. */
internal object V39AiChatSessionRuntime {
    private val nextId = AtomicLong(1L)
    val messages = mutableStateListOf<V39ChatMessage>()
    val submitGate = V39AiSubmitGate()
    var pendingRequest by mutableStateOf("")
    var busy by mutableStateOf(false)
    var status by mutableStateOf("")

    fun append(role: V39ChatRole, text: String, ok: Boolean? = null) {
        text.trim().takeIf(String::isNotBlank)?.let {
            messages += V39ChatMessage(nextId.getAndIncrement(), role, it, ok)
        }
    }
}

internal object V39AiChatContract {
    const val PREFS = "cyclone_ai"
    const val MODEL_KEY = "openrouter_model"
    const val PLACEHOLDER = "Ask Cyclone…"

    fun normalizedRequest(value: String) = value.trim()
    fun modelForStored(stored: String?): OpenRouterModelPreset =
        OpenRouterModelPresets.byId(stored.orEmpty())
    fun storageId(model: OpenRouterModelPreset): String = ModelRegistry.profileForPreset(model)?.cycloneId ?: model.id

    fun config(modelId: String, accessProfile: CycloneAiAccessProfile): QuickAgentConfig {
        val model = modelForStored(modelId)
        return QuickAgentConfig(
            model = model,
            visionModel = model,
            safeMode = accessProfile != CycloneAiAccessProfile.FULL,
            accessProfile = accessProfile,
        )
    }

    fun finalStatus(result: QuickAgentResult) = if (result.ok) "Completed and checked" else "Stopped safely"
}

internal class V39AiSubmitGate {
    private val active = AtomicBoolean(false)

    fun tryAccept(rawRequest: String, hasKey: Boolean): String? {
        val request = V39AiChatContract.normalizedRequest(rawRequest)
        if (request.isBlank() || !hasKey || !active.compareAndSet(false, true)) return null
        return request
    }

    fun complete() = active.set(false)
}

@Composable
internal fun V39AiChatPage(context: Context, refreshTick: Int, onSettings: () -> Unit) {
    val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val task by WorkspaceTasks.state.collectAsState()
    val queuedRequests by WorkspaceTasks.requests.state.collectAsState()
    val foregroundActivity by OverlayChromeRuntime.activity.collectAsState()
    val foregroundSnapshot = remember(foregroundActivity) { OverlayChromeRuntime.snapshot() }
    val foregroundWorking = task == null && foregroundActivity in setOf(OverlayChromeState.WORKING, OverlayChromeState.LIVE)
    val attached by PendingTaskAttachment.present.collectAsState()
    val backdrop = LocalCycloneLiquidBackdrop.current
    val prefs = context.getSharedPreferences(V39AiChatContract.PREFS, Context.MODE_PRIVATE)
    val scope = rememberCoroutineScope()
    val session = V39AiChatSessionRuntime
    var chatJob by remember { mutableStateOf<Job?>(null) }
    var composer by rememberSaveable { mutableStateOf("") }
    var toolsOpen by remember { mutableStateOf(false) }
    var intelligenceOpen by remember { mutableStateOf(false) }
    var voiceOpen by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val catalogRevision by com.cyclone.mobile.ai.OpenRouterCatalogStore.revision.collectAsState()
    var selectedModelId by rememberSaveable(catalogRevision, refreshTick) {
        mutableStateOf(com.cyclone.mobile.ai.OpenRouterCatalogStore.activeId(context))
    }
    var reasoningEffort by rememberSaveable {
        mutableStateOf(prefs.getString("openrouter_reasoning_effort", "medium") ?: "medium")
    }
    val hasKey = remember(refreshTick) { OpenRouterSecretStore.hasKey(context) }
    val previewRoute = remember(composer, attached) { RequestIntentRouter.route(composer, hasAttachment = attached) }
    val emptyCanvas = session.messages.isEmpty() && !session.busy && task == null && !foregroundWorking

    val dictation = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        voiceOpen = false
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let {
                composer = listOf(composer, it).filter(String::isNotBlank).joinToString(" ")
            }
        }
    }

    fun restoreAttachmentAfterChatFailure(attachment: TaskAttachment?) {
        if (attachment != null && !PendingTaskAttachment.present.value) PendingTaskAttachment.set(attachment)
    }

    fun persistAiControls(modelId: String, effort: String) {
        selectedModelId = modelId
        reasoningEffort = effort
        prefs.edit()
            .putString(V39AiChatContract.MODEL_KEY, modelId)
            .putString("openrouter_reasoning_effort", effort)
            .apply()
    }

    fun submit(raw: String = composer) {
        message = ""
        val normalized = V39AiChatContract.normalizedRequest(raw)
        if (normalized.isBlank()) return

        if (com.cyclone.mobile.ai.OpenRouterCatalogStore.activeId(context).isBlank()) {
            message = "Choose models in Settings → Model & API first."
            return
        }
        val route = RequestIntentRouter.route(normalized, hasAttachment = attached)
        val dispatch = if (route.intent == RequestIntent.CHAT) RequestDispatch.CHAT else
            RequestIntentRouter.dispatch(route, canStartPhoneTask = WorkspaceTasks.canStartRequest())

        when (dispatch) {
            RequestDispatch.START_PHONE_TASK -> runCatching {
                check(OverlayChromeRuntime.isAttached()) { "Phone control needs repair. Open Phone control in Settings." }
                OverlayChromeRuntime.submitRequest(normalized)
            }.onSuccess {
                composer = ""
            }.onFailure { message = it.message ?: "Couldn't open the phone-task setup." }

            RequestDispatch.QUEUE_PHONE_TASK -> runCatching { WorkspaceTasks.queueRequest(normalized) }
                .onSuccess {
                    composer = ""
                    message = "Saved to Up next. Your current task continues."
                }
                .onFailure { message = it.message ?: "Couldn't save this task." }

            RequestDispatch.CHAT -> {
                val request = session.submitGate.tryAccept(normalized, hasKey) ?: run {
                    if (!hasKey) message = "Add an OpenRouter key in Settings to chat."
                    return
                }
                val history = session.messages.map { (if (it.role == V39ChatRole.USER) "user" else "assistant") to it.text }
                val attachment = PendingTaskAttachment.take()
                val model = OpenRouterModelPresets.byId(com.cyclone.mobile.ai.OpenRouterCatalogStore.activeId(context)).copy(reasoningEffort = reasoningEffort)
                composer = ""
                session.busy = true
                session.status = "Answering…"
                session.append(V39ChatRole.USER, request)
                chatJob = scope.launch {
                    try {
                        val answer = com.cyclone.mobile.ai.CycloneTextChat.answer(context, model, history, request, attachment)
                        session.append(V39ChatRole.CYCLONE, answer)
                        session.status = ""
                    } catch (cancelled: CancellationException) {
                        restoreAttachmentAfterChatFailure(attachment)
                        session.status = "Reply stopped"
                        throw cancelled
                    } catch (error: Exception) {
                        restoreAttachmentAfterChatFailure(attachment)
                        session.status = "Couldn't get a reply"
                        session.append(V39ChatRole.CYCLONE, error.message ?: "Chat failed. Try again.")
                    } finally {
                        session.submitGate.complete()
                        session.busy = false
                        chatJob = null
                    }
                }
            }
        }
    }

    fun startVoice() {
        toolsOpen = false
        intelligenceOpen = false
        voiceOpen = true
        runCatching {
            dictation.launch(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM),
            )
        }.onFailure {
            voiceOpen = false
            message = "Dictation isn't available. You can type your request."
        }
    }

    fun openCamera() {
        toolsOpen = false
        context.startActivity(
            Intent(context, com.cyclone.mobile.ui.overlay.OverlayAttachmentActivity::class.java)
                .putExtra("camera", true),
        )
    }

    fun openFiles() {
        toolsOpen = false
        context.startActivity(Intent(context, com.cyclone.mobile.ui.overlay.OverlayAttachmentActivity::class.java))
    }

    fun shareScreen() {
        toolsOpen = false
        context.startActivity(Intent(context, com.cyclone.mobile.capture.LiveCaptureConsentActivity::class.java))
    }

    LaunchedEffect(Unit) {
        session.pendingRequest.takeIf(String::isNotBlank)?.let {
            session.pendingRequest = ""
            composer = it
            submit(it)
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AskCycloneHeader(
                onMenu = onSettings,
                onProfile = onSettings,
                model = {
                    if (!keyboardOpen) {
                        CycloneModelPill(
                            modelId = selectedModelId,
                            effort = reasoningEffort,
                            enabled = !session.busy,
                            onChange = ::persistAiControls,
                        )
                    }
                },
            )

            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(if (emptyCanvas) 0.dp else 12.dp),
                contentPadding = PaddingValues(top = if (keyboardOpen) 2.dp else 4.dp, bottom = 8.dp),
            ) {
                if (emptyCanvas && !keyboardOpen) {
                    item { AskCycloneEmptyState(onSuggestion = { composer = it }) }
                } else if (session.messages.isNotEmpty()) {
                    items(session.messages, key = { it.id }) { V39ChatBubble(it) }
                }

                if (session.busy || session.status.isNotBlank()) {
                    item {
                        Box(
                            Modifier
                                .fillMaxWidth(.72f)
                                .clip(RoundedCornerShape(18.dp))
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = .56f)),
                        ) {
                            Row(
                                Modifier.padding(horizontal = 13.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(9.dp),
                            ) {
                                if (session.busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                Text(
                                    if (session.busy) "Thinking…" else session.status,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
            }

            if (task != null || queuedRequests.isNotEmpty() || foregroundWorking) {
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = if (keyboardOpen) 132.dp else 230.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 2.dp),
                ) {
                    task?.let { current ->
                        item(key = "current-${current.taskId}") { CycloneAskTaskPanel(current) }
                    }
                    if (foregroundWorking) {
                        item(key = "foreground-${foregroundSnapshot.sessionId}") {
                            CycloneForegroundWorkCard(foregroundSnapshot)
                        }
                    }
                    if (queuedRequests.isNotEmpty()) {
                        item(key = "queued") { CyclonePendingRequests() }
                    }
                }
            }

            if (!hasKey) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = .90f),
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Key, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("OpenRouter key required for chat", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = onSettings) { Text("Settings") }
                    }
                }
            }

            if (message.isNotBlank()) {
                Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            if (attached) {
                Text("Attachment ready", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            }

            if (intelligenceOpen) {
                CycloneLiquidPanel(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 24.dp,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    CycloneModelIntelligencePanel(
                        modelId = selectedModelId,
                        effort = reasoningEffort,
                        showModelSelector = false,
                        onChange = ::persistAiControls,
                    )
                }
            }

            AnimatedVisibility(
                visible = toolsOpen,
                enter = slideInVertically { it / 6 } + fadeIn(),
                exit = slideOutVertically { it / 6 } + fadeOut(),
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomStart = 22.dp, bottomEnd = 22.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = .96f),
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                ) {
                    CycloneAttachmentTools(
                        onCamera = { openCamera() },
                        onFiles = { openFiles() },
                        onShareScreen = { shareScreen() },
                        extras = listOf(
                            Icons.Rounded.CropOriginal to "Take screenshot",
                            Icons.Rounded.Apps to "Open app",
                            Icons.Rounded.Visibility to "Explain this screen",
                            Icons.Rounded.Bolt to "Create a routine",
                            Icons.Rounded.AutoAwesome to "Deep research",
                            Icons.Rounded.Tune to "Model & intelligence",
                        ),
                        onExtra = { label ->
                            toolsOpen = false
                            when (label) {
                                "Take screenshot" -> composer = "Take a screenshot"
                                "Open app" -> composer = "Open "
                                "Explain this screen" -> shareScreen()
                                "Create a routine" -> composer = "Create a routine"
                                "Deep research" -> composer = "Research "
                                "Model & intelligence" -> intelligenceOpen = true
                            }
                        },
                    )
                }
            }

            CycloneLiquidPanel(
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                cornerRadius = 30.dp,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 5.dp),
            ) {
                Column(Modifier.fillMaxWidth()) {
                    if (session.busy) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 8.dp, end = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Answering",
                                Modifier.weight(1f),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall,
                            )
                            TextButton(onClick = { chatJob?.cancel() }) { Text("Stop reply") }
                        }
                    }

                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CycloneTrayIconAction(
                            onClick = {
                                toolsOpen = !toolsOpen
                                if (toolsOpen) intelligenceOpen = false
                            },
                            enabled = !session.busy,
                            modifier = Modifier.size(44.dp),
                        ) {
                            Icon(
                                Icons.Rounded.Add,
                                "Add attachment",
                                Modifier.size(22.dp),
                                tint = if (toolsOpen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        BasicTextField(
                            value = composer,
                            onValueChange = { composer = it },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 46.dp, max = 96.dp)
                                .padding(horizontal = 8.dp, vertical = 12.dp)
                                .semantics { contentDescription = "Ask Cyclone composer" },
                            maxLines = 4,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { submit() }),
                            decorationBox = { field ->
                                Box(contentAlignment = Alignment.CenterStart) {
                                    if (composer.isEmpty()) {
                                        Text(
                                            V39AiChatContract.PLACEHOLDER,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    field()
                                }
                            },
                        )

                        val sendEnabled = composer.isNotBlank() && when (previewRoute.intent) {
                            RequestIntent.PHONE_TASK -> true
                            RequestIntent.CHAT -> hasKey && !session.busy
                        }
                        if (composer.isBlank() && !session.busy) {
                            CycloneTrayIconAction(
                                onClick = { startVoice() },
                                enabled = true,
                                modifier = Modifier.size(46.dp),
                            ) {
                                Icon(Icons.Rounded.Mic, "Dictate request", Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        } else if (backdrop != null) {
                            CycloneKyantLiquidIconButton(
                                onClick = { submit() },
                                backdrop = backdrop,
                                enabled = sendEnabled,
                                modifier = Modifier.size(46.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            ) {
                                Icon(
                                    Icons.Rounded.ArrowUpward,
                                    "Send request",
                                    Modifier.size(22.dp),
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                )
                            }
                        } else {
                            CycloneTrayIconAction(
                                onClick = { submit() },
                                enabled = sendEnabled,
                                modifier = Modifier.size(46.dp),
                            ) {
                                Icon(Icons.Rounded.ArrowUpward, "Send request", Modifier.size(22.dp))
                            }
                        }
                    }
                }
            }
        }

        if (voiceOpen) {
            AskCycloneVoiceMode(onClose = { voiceOpen = false })
        }
    }
}

@Composable
private fun AskCycloneHeader(
    onMenu: () -> Unit,
    onProfile: () -> Unit,
    model: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClick = onMenu)
                .semantics { contentDescription = "Settings" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Menu, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurface)
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "Ask Cyclone",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            model()
        }
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClick = onProfile)
                .semantics { contentDescription = "Profile" },
            contentAlignment = Alignment.Center,
        ) {
            CycloneOrbitMark(Modifier.size(28.dp))
        }
    }
}

@Composable
private fun AskCycloneEmptyState(onSuggestion: (String) -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        AskCycloneOrb()
        Text(
            "Ready when you are",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            "Tell Cyclone what to do on your phone.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(
            Modifier.padding(top = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AskSuggestionChip("Take a screenshot", onSuggestion)
                AskSuggestionChip("Open an app", onSuggestion)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AskSuggestionChip("Find something", onSuggestion)
                AskSuggestionChip("Create a routine", onSuggestion)
            }
        }
    }
}

@Composable
private fun AskSuggestionChip(label: String, onSuggestion: (String) -> Unit) {
    Surface(
        modifier = Modifier
            .heightIn(min = 36.dp)
            .clickable(role = Role.Button, onClick = { onSuggestion(label) }),
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .72f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun AskCycloneOrb() {
    val pulse = rememberInfiniteTransition(label = "orb")
    val glow by pulse.animateFloat(
        initialValue = 0.22f,
        targetValue = 0.48f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "glow",
    )
    Box(Modifier.size(132.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(132.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = glow),
                            Color.Transparent,
                        ),
                    ),
                    CircleShape,
                ),
        )
        CycloneOrbitMark(Modifier.size(72.dp))
    }
}

@Composable
private fun AskCycloneVoiceMode(onClose: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF07101F), Color(0xFF123D72), Color(0xFF07101F)),
                ),
            )
            .semantics { contentDescription = "Listening…" },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            AskCycloneOrb()
            Text("Listening…", style = MaterialTheme.typography.headlineSmall, color = Color.White)
            Text("Speak naturally", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = .72f))
            Spacer(Modifier.height(24.dp))
            Surface(
                modifier = Modifier
                    .size(64.dp)
                    .clickable(role = Role.Button, onClick = onClose),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shadowElevation = 0.dp,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Stop, "Stop listening", Modifier.size(28.dp))
                }
            }
        }
        Icon(
            Icons.Rounded.Close,
            "Close voice",
            Modifier
                .align(Alignment.TopStart)
                .padding(18.dp)
                .size(28.dp)
                .clickable(onClick = onClose),
            tint = Color.White,
        )
    }
}

@Composable
private fun V39ChatBubble(message: V39ChatMessage) {
    val isUser = message.role == V39ChatRole.USER
    if (!isUser) {
        Column(
            Modifier.fillMaxWidth().padding(end = 28.dp, top = 4.dp, bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                message.text.replace("**", ""),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (message.ok != null) {
                Text(
                    if (message.ok) "Checked" else "Stopped",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (message.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
        }
        return
    }
    val shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)
    val color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .72f)
    val contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(
            modifier = Modifier
                .fillMaxWidth(.82f)
                .clip(shape)
                .background(color),
        ) {
            Text(
                message.text.replace("**", ""),
                modifier = Modifier.padding(horizontal = 15.dp, vertical = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor,
            )
        }
    }
}
