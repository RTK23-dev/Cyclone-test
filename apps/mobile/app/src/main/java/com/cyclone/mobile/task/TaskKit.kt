package com.cyclone.mobile.task

import com.cyclone.mobile.runtime.background.WorkspaceTaskUi

/**
 * Task Kit: the one contract between everything the owner presses (overlay, notification, Ask, progress screen,
 * task glass) and whatever engine runs the task. Surfaces never call an engine directly; they send a [TaskCommand]
 * through [TaskCommandBus], which finds the engine that owns the task and records what happened. An engine either
 * handles a command or says why not; a press is never dropped silently.
 */

/** Which engine runs a task. Every task has exactly one. */
enum class TaskEngine { MIND, CLASSIC_FOREGROUND, BACKGROUND_WORKSPACE }

/** What the owner asked the task to do. [wire] is the legacy action string still used by service intents. */
sealed class TaskCommand(val wire: String, val label: String) {
    data object Stop : TaskCommand("cancel", "Stop")
    data object TakeOver : TaskCommand("handoff", "Take over")
    data object Pause : TaskCommand("pause", "Pause")
    /** "I'm done": the owner is finished with the phone; the task continues. */
    data object Done : TaskCommand("resume", "I'm done")
    data object Autofill : TaskCommand("autofill", "Autofill")
    data class Confirm(val token: String?) : TaskCommand("confirm", "Confirm")
    data object Approve : TaskCommand("approve", "Approve")
    data object Decline : TaskCommand("decline", "Decline")

    companion object {
        // Lazy: the command objects extend this class, so they do not exist yet while its companion initializes.
        val ALL: List<TaskCommand> by lazy { listOf(Stop, TakeOver, Pause, Done, Autofill, Confirm(null), Approve, Decline) }

        fun parse(action: String?, confirmation: String? = null): TaskCommand? = when (action?.trim()?.lowercase()) {
            "cancel", "stop" -> Stop
            "handoff", "takeover", "take_over" -> TakeOver
            "pause" -> Pause
            "resume", "done", "continue" -> Done
            "autofill" -> Autofill
            "confirm" -> Confirm(confirmation)
            "approve" -> Approve
            "decline" -> Decline
            else -> null
        }
    }
}

data class TaskCommandResult(val handled: Boolean, val engine: TaskEngine?, val detail: String) {
    companion object {
        fun done(engine: TaskEngine, detail: String) = TaskCommandResult(true, engine, detail)
        fun refused(engine: TaskEngine?, detail: String) = TaskCommandResult(false, engine, detail)
    }
}

/** One engine's side of the contract. */
interface TaskController {
    val engine: TaskEngine
    /** The commands this engine understands at all. Anything else is refused with a reason by the bus. */
    val supported: Set<Class<out TaskCommand>>
    fun handle(task: WorkspaceTaskUi, command: TaskCommand): TaskCommandResult

    fun supports(command: TaskCommand): Boolean = command.javaClass in supported
}

object TaskEngines {
    /** The explicit engine when the task names one; otherwise derived from how it was started. */
    fun of(task: WorkspaceTaskUi): TaskEngine = task.engine ?: when {
        task.taskId.startsWith(MIND_TASK_PREFIX) -> TaskEngine.MIND
        task.foreground -> TaskEngine.CLASSIC_FOREGROUND
        else -> TaskEngine.BACKGROUND_WORKSPACE
    }

    const val MIND_TASK_PREFIX = "mission-"
}

/**
 * The single way in. [current] supplies the live task; [log] receives every command and its outcome (trace,
 * diagnostics), handled or not.
 */
class TaskCommandBus(
    private val controllers: List<TaskController>,
    private val current: () -> WorkspaceTaskUi?,
    private val log: (WorkspaceTaskUi?, TaskCommand, TaskCommandResult) -> Unit = { _, _, _ -> },
) {
    init {
        val engines = controllers.map { it.engine }
        require(engines.size == engines.toSet().size) { "one controller per engine" }
    }

    fun controller(engine: TaskEngine): TaskController? = controllers.firstOrNull { it.engine == engine }

    fun send(taskId: String, command: TaskCommand): TaskCommandResult {
        val task = current()?.takeIf { it.taskId == taskId }
        val result = when {
            task == null -> TaskCommandResult.refused(null, "That task is no longer current.")
            else -> {
                val engine = TaskEngines.of(task)
                val controller = controller(engine)
                when {
                    controller == null -> TaskCommandResult.refused(engine, "No controller is registered for $engine.")
                    !controller.supports(command) -> TaskCommandResult.refused(engine, "${command.label} is not available for this task.")
                    else -> runCatching { controller.handle(task, command) }
                        .getOrElse { TaskCommandResult.refused(engine, "${command.label} failed: ${it.message ?: it.javaClass.simpleName}") }
                }
            }
        }
        runCatching { log(task, command, result) }
        return result
    }
}
