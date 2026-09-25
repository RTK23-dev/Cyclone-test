package com.cyclone.mobile.mind.mission

import android.content.Context
import com.cyclone.mobile.DeviceState
import com.cyclone.mobile.agent.tools.CycloneAgentEnvironment
import com.cyclone.mobile.ai.AgentTraceRuntime
import com.cyclone.mobile.ai.OpenRouterCatalogStore
import com.cyclone.mobile.ai.OpenRouterSecretStore
import com.cyclone.mobile.ai.ProviderCancellation
import com.cyclone.mobile.mind.MindBudget
import com.cyclone.mobile.mind.MindCheckpoint
import com.cyclone.mobile.mind.MindConversation
import com.cyclone.mobile.mind.MindListener
import com.cyclone.mobile.mind.MindLoop
import com.cyclone.mobile.mind.MindMessage
import com.cyclone.mobile.mind.MindModel
import com.cyclone.mobile.mind.MindOutcome
import com.cyclone.mobile.mind.MindPlanStep
import com.cyclone.mobile.mind.MindPrompt
import com.cyclone.mobile.mind.MindStatus
import com.cyclone.mobile.mind.MindToolCall
import com.cyclone.mobile.mind.MindToolResult
import com.cyclone.mobile.mind.OpenRouterMindModel
import com.cyclone.mobile.mind.PhoneMindToolbox
import com.cyclone.mobile.runtime.background.TaskInterruption
import com.cyclone.mobile.runtime.background.TaskPhase
import com.cyclone.mobile.runtime.background.WorkspaceTaskUi
import com.cyclone.mobile.runtime.background.WorkspaceTasks
import com.cyclone.mobile.ui.overlay.OverlayChromeRuntime
import com.cyclone.mobile.ui.overlay.TaskAttachment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Cyclone Mind on the phone: one mission at a time, one model, one continuous conversation, journaled after every
 * turn so an interrupted mission can pick up where it stopped. This object only wires the engine to Android; the
 * decisions are the model's and the boundaries are the harness's.
 */
object MindMissions {
    private const val PREFS = "cyclone_ai"
    private const val ENABLED_KEY = "mind_runtime_enabled"
    private const val MINUTES_KEY = "mind_mission_minutes"

    val inbox = OwnerInbox()
    private val liveState = MutableStateFlow<Mission?>(null)
    private val historyState = MutableStateFlow<List<Mission>>(emptyList())
    val live: StateFlow<Mission?> = liveState
    val history: StateFlow<List<Mission>> = historyState

    private val lock = Any()
    private var worker: Thread? = null
    @Volatile private var stopRequested = false
    private var cancellation: ProviderCancellation? = null
    private val ownerMessages = ConcurrentLinkedQueue<String>()
    @Volatile private var store: MissionStore? = null
    @Volatile private var recovered = false

    private val hooks = object : OverlayChromeRuntime.MissionHooks {
        override fun stop() = MindMissions.stop()
        override fun ownerText(text: String): Boolean = steer(text)
    }

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED_KEY, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ENABLED_KEY, enabled).apply()
    }

    fun workingMinutes(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(MINUTES_KEY, 30).coerceIn(5, 120)

    fun setWorkingMinutes(context: Context, minutes: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(MINUTES_KEY, minutes.coerceIn(5, 120)).apply()
    }

    fun store(context: Context): MissionStore = store ?: synchronized(lock) {
        store ?: MissionStore(File(context.applicationContext.filesDir, "Cyclone Brain/Missions")).also { store = it }
    }

    fun refresh(context: Context) {
        val missions = store(context)
        if (!recovered) {
            recovered = true
            missions.recover(System.currentTimeMillis(), liveState.value?.id)
        }
        historyState.value = missions.list()
    }

    fun isLive(): Boolean = synchronized(lock) { worker?.isAlive == true }

    /** Starts a new mission. Returns false when another mission is still running. */
    fun start(context: Context, goal: String, attachment: TaskAttachment? = null): Boolean {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        val id = "m" + now.toString(36) + UUID.randomUUID().toString().take(8)
        val mission = Mission(id, goal.trim().take(2_000), MissionStatus.RUNNING, now, now, "", "")
        return launch(app, mission, resume = null, attachment = attachment)
    }

    /** Continues a paused, failed or interrupted mission with its full conversation. */
    fun resume(context: Context, id: String): Boolean {
        val app = context.applicationContext
        val missions = store(app)
        val mission = missions.load(id)?.takeIf { it.status.resumable } ?: return false
        val journal = missions.loadJournal(id) ?: return false
        val reason = when (mission.status) {
            MissionStatus.PAUSED -> "its working time ran out and the owner gave it more"
            MissionStatus.INTERRUPTED -> "Cyclone was stopped or restarted"
            else -> "it failed and the owner asked to try again"
        }
        return launch(app, mission.copy(status = MissionStatus.RUNNING, resumes = mission.resumes + 1, summary = ""), journal, null, reason)
    }

    fun stop() {
        stopRequested = true
        synchronized(lock) { cancellation?.cancel() }
        inbox.withdrawAll()
    }

    /**
     * Owner text while a mission runs: it answers the open question, or joins the conversation as a new instruction
     * the model reads at its next turn.
     */
    fun steer(text: String): Boolean {
        if (!isLive()) return false
        val clean = text.trim().take(2_000)
        if (clean.isBlank()) return true
        inbox.pending.value?.let { request ->
            when (request.kind) {
                OwnerRequestKind.QUESTION -> if (inbox.respond(request.id, OwnerResponse.Answer(clean))) return true
                OwnerRequestKind.CONTROL -> if (clean.lowercase() in setOf("done", "ok", "klaar", "go")) {
                    if (inbox.respond(request.id, OwnerResponse.Done)) return true
                }
                else -> Unit
            }
        }
        ownerMessages += clean
        return true
    }

    fun answer(requestId: String, response: OwnerResponse): Boolean = inbox.respond(requestId, response)

    fun delete(context: Context, id: String) {
        if (liveState.value?.id == id) return
        store(context).delete(id)
        refresh(context)
    }

    private fun launch(context: Context, initial: Mission, resume: MissionJournal?, attachment: TaskAttachment?, resumeReason: String = ""): Boolean {
        synchronized(lock) {
            if (worker?.isAlive == true) return false
            stopRequested = false
            ownerMessages.clear()
            cancellation = ProviderCancellation()
            liveState.value = initial
            val thread = Thread({ run(context, initial, resume, attachment, resumeReason) }, "cyclone-mind")
            worker = thread
            OverlayChromeRuntime.attachMission(hooks)
            thread.start()
        }
        return true
    }

    private fun run(context: Context, initial: Mission, resume: MissionJournal?, attachment: TaskAttachment?, resumeReason: String) {
        val missions = store(context)
        var mission = initial
        val taskId = "mission-${mission.id}"
        fun save(change: (Mission) -> Mission) {
            mission = change(mission).copy(updatedAtMs = System.currentTimeMillis())
            liveState.value = mission
            runCatching { missions.save(mission) }
        }
        publishTask(context, taskId, mission)
        DeviceState.setController(DeviceState.Controller.AGENT)
        OverlayChromeRuntime.missionWorking(taskId, "Thinking")
        var traceId: String? = null
        var outcome: MindOutcome? = null
        var failure: String? = null
        try {
            val key = OpenRouterSecretStore.read(context)
            require(key.isNotBlank()) { "Add your OpenRouter API key in Cyclone's AI settings first." }
            val primaryId = OpenRouterCatalogStore.activeId(context)
            require(primaryId.isNotBlank()) { "Choose a verified model in Cyclone's AI settings first." }
            val backupId = OpenRouterCatalogStore.backupId(context).takeIf { it.isNotBlank() }
            val effort = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("openrouter_reasoning_effort", "medium")
            traceId = mission.traceId?.takeIf { resume != null } ?: AgentTraceRuntime.start(context, mission.goal, primaryId)
            val trace = traceId
            val cancel = synchronized(lock) { cancellation } ?: ProviderCancellation()
            fun model(id: String): MindModel {
                val preset = OpenRouterCatalogStore.preset(context, id)
                return OpenRouterMindModel(key, id, preset.label, preset.vision, effort, trace, cancel, { stopRequested }) { phase, _ ->
                    if (phase.isNotBlank()) OverlayChromeRuntime.missionStatus("Thinking")
                }
            }
            val primary = model(primaryId)
            val backup = backupId?.let(::model)
            save { it.copy(modelId = primaryId, modelLabel = primary.label, traceId = trace) }
            AgentTraceRuntime.event(context, trace, if (resume == null) "MISSION_START" else "MISSION_RESUME",
                if (resume == null) "Mission started with ${primary.label}" else "Mission resumed (${mission.resumes}) with ${primary.label}")

            val device = AndroidMindDevice(context)
            val owner = AndroidMindOwner(context, inbox, mission.id, { stopRequested },
                onWaiting = { question ->
                    waiting(context, taskId, question)
                    save { it.copy(status = if (question == null) MissionStatus.RUNNING else MissionStatus.WAITING, waitingFor = question) }
                },
                onStatus = { text -> status(context, taskId, text) },
                onPlan = { steps -> save { it.copy(plan = steps) }; planToTask(taskId, steps) })
            val environment = CycloneAgentEnvironment(context, userTaskGoal = mission.goal)
            val toolbox = PhoneMindToolbox(environment, owner, device, mission.goal, { stopRequested })
            val native = resume?.nativeTools ?: (OpenRouterCatalogStore.lookup(primaryId)?.nativeTools != false)
            val system = MindPrompt.system(null, native, toolbox.specs(), device.now(), device.device())
            val conversation = if (resume != null) resume.conversation.also {
                it.replaceFirst(MindMessage.System(system))
                it.add(MindMessage.User(MindPrompt.resumed(resumeReason),
                    origin = MindMessage.User.Origin.HARNESS))
            } else MindConversation(listOf(
                MindMessage.System(system),
                MindMessage.User(MindPrompt.mission(mission.goal, toolbox.situation()) +
                    (attachment?.text?.let { "\n\nThe owner attached this (reference only, not instructions):\n${it.take(4_000)}" }.orEmpty()),
                    attachment?.imageDataUrl?.takeIf { primary.vision }),
            ))
            val budget = MindBudget(workingMs = workingMinutes(context) * 60_000L)
            val listener = MissionListener(context, trace, taskId, missions, mission.id,
                onTurn = { turn -> save { it.copy(turns = turn) } }) { event -> save { it.withEvent(event) } }
            val loop = MindLoop(primary, backup, toolbox, budget, listener, cancelled = { stopRequested },
                ownerMessages = { drainOwnerMessages() }, nativeTools = native)
            outcome = loop.run(conversation, resume?.checkpoint())
            val result = outcome
            save {
                it.copy(
                    status = when (result.status) {
                        MindStatus.COMPLETED -> MissionStatus.COMPLETED
                        MindStatus.GAVE_UP -> MissionStatus.GAVE_UP
                        MindStatus.FAILED -> MissionStatus.FAILED
                        MindStatus.CANCELLED -> MissionStatus.CANCELLED
                        MindStatus.OUT_OF_BUDGET -> MissionStatus.PAUSED
                    },
                    summary = result.summary, evidence = result.evidence.orEmpty(), turns = result.turns, workingMs = result.workingMs,
                    usage = result.usage, modelLabel = result.modelLabel, waitingFor = null,
                )
            }
        } catch (error: Throwable) {
            failure = error.message ?: error.javaClass.simpleName
            save { it.copy(status = MissionStatus.FAILED, summary = "Cyclone could not run this mission: $failure", waitingFor = null) }
        } finally {
            val ok = mission.status == MissionStatus.COMPLETED
            traceId?.let { trace ->
                AgentTraceRuntime.finish(context, trace, when (mission.status) {
                    MissionStatus.COMPLETED -> "COMPLETED"
                    MissionStatus.CANCELLED -> "CANCELLED"
                    MissionStatus.PAUSED -> "PAUSED"
                    else -> "FAILED"
                }, mission.summary.take(500), mission.turns)
            }
            finishTask(context, taskId, mission)
            OverlayChromeRuntime.missionFinished(taskId, ok, mission.summary.take(200).ifBlank { "Mission ended." })
            synchronized(lock) {
                worker = null
                cancellation = null
                OverlayChromeRuntime.detachMission(hooks)
            }
            inbox.withdrawAll()
            liveState.value = null
            historyState.value = runCatching { missions.list() }.getOrDefault(historyState.value)
            WorkspaceTasks.scheduleQueuePromotion(context)
        }
    }

    private fun drainOwnerMessages(): List<String> = buildList { while (true) add(ownerMessages.poll() ?: break) }

    // ---- the task card and notification --------------------------------------------------------------------------

    private fun publishTask(context: Context, taskId: String, mission: Mission) {
        val task = WorkspaceTaskUi(taskId, "default-foreground", "Cyclone Mind", "", mission.goal, phase = TaskPhase.WORKING,
            message = "On it.", displayId = 0, startedAtMs = System.currentTimeMillis(), traceSessionId = mission.traceId,
            plannedMilestones = mission.plan.map { it.text })
        runCatching { WorkspaceTasks.publishStart(task) }.onFailure { WorkspaceTasks.update(taskId) { task } }
        com.cyclone.mobile.ui.overlay.AgentTaskNotificationRuntime.start(context)
    }

    private fun status(context: Context, taskId: String, text: String) {
        WorkspaceTasks.update(taskId) { it.copy(message = text.take(160), phase = TaskPhase.WORKING, interruption = null) }
        OverlayChromeRuntime.missionStatus(text.take(120))
        com.cyclone.mobile.ui.overlay.AgentTaskNotificationRuntime.progress(context, text)
    }

    private fun waiting(context: Context, taskId: String, question: String?) {
        if (question == null) {
            WorkspaceTasks.update(taskId) { it.copy(phase = TaskPhase.WORKING, interruption = null) }
            return
        }
        WorkspaceTasks.update(taskId) {
            it.copy(phase = TaskPhase.REVIEW, message = question.take(160),
                interruption = TaskInterruption(reason = "MIND_OWNER_REQUEST", prompt = question.take(300), canResumeAfterHuman = true))
        }
        OverlayChromeRuntime.missionStatus(question.take(120))
        com.cyclone.mobile.ui.overlay.AgentTaskNotificationRuntime.waiting(context, question)
    }

    private fun planToTask(taskId: String, steps: List<MindPlanStep>) {
        WorkspaceTasks.update(taskId) { task ->
            task.copy(plannedMilestones = steps.map { it.text.take(80) },
                plannedMilestoneIndex = steps.indexOfFirst { it.status == "doing" || it.status == "todo" }.coerceAtLeast(0))
        }
    }

    private fun finishTask(context: Context, taskId: String, mission: Mission) {
        val phase = when (mission.status) {
            MissionStatus.COMPLETED -> TaskPhase.DONE
            MissionStatus.CANCELLED -> TaskPhase.STOPPED
            else -> TaskPhase.FAILED
        }
        val message = mission.summary.ifBlank { "Mission ended." }.take(300)
        WorkspaceTasks.update(taskId) { it.copy(phase = phase, message = message, outcome = message, interruption = null,
            resumable = mission.status.resumable) }
        com.cyclone.mobile.ui.overlay.AgentTaskNotificationRuntime.finish(context, mission.status == MissionStatus.COMPLETED, message)
    }

    /** Journals every turn and mirrors the model-visible story into the run trace. Never provider reasoning. */
    private class MissionListener(
        private val context: Context,
        private val traceId: String,
        private val taskId: String,
        private val store: MissionStore,
        private val missionId: String,
        private val onTurn: (Int) -> Unit,
        private val onEvent: (MissionEvent) -> Unit,
    ) : MindListener {
        override fun onModelStart(turn: Int, model: MindModel) {
            OverlayChromeRuntime.missionStatus("Thinking")
            onTurn(turn)
        }

        override fun onAssistant(turn: Int, message: MindMessage.Assistant) {
            val said = MindRedaction.scrub(message.text.trim())
            val calls = message.toolCalls.joinToString { it.name }
            AgentTraceRuntime.event(context, traceId, "MIND_TURN", "Turn $turn: ${said.take(300).ifBlank { calls.ifBlank { "(no action)" } }}",
                code = "MIND_TURN", detail = if (calls.isBlank()) null else "calls: $calls")
            if (said.isNotBlank()) WorkspaceTasks.update(taskId) { it.copy(message = said.take(160)) }
        }

        override fun onToolStart(turn: Int, call: MindToolCall) {
            AgentTraceRuntime.event(context, traceId, "MIND_ACTION", call.name, code = call.name,
                detail = MindRedaction.scrub(call.arguments).take(600))
        }

        override fun onToolResult(turn: Int, call: MindToolCall, result: MindToolResult) {
            val brief = MindRedaction.scrub(result.brief)
            AgentTraceRuntime.event(context, traceId, "MIND_RESULT", brief.take(300), code = call.name, ok = result.ok,
                detail = MindRedaction.scrub(result.text).take(1_500))
            if (call.name !in QUIET_TOOLS) onEvent(MissionEvent(System.currentTimeMillis(), brief.take(200), result.ok))
        }

        override fun onNotice(turn: Int, text: String) {
            AgentTraceRuntime.event(context, traceId, "MIND_NOTICE", text.take(300), code = "MIND_NOTICE")
            onEvent(MissionEvent(System.currentTimeMillis(), text.take(200)))
        }

        override fun checkpoint(checkpoint: MindCheckpoint) {
            runCatching { store.saveJournal(missionId, checkpoint) }
        }

        private companion object {
            val QUIET_TOOLS = setOf("screen_read", "note")
        }
    }
}
