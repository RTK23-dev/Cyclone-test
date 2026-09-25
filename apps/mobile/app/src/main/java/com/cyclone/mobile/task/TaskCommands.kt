package com.cyclone.mobile.task

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.cyclone.mobile.DeviceState
import com.cyclone.mobile.ai.AgentTraceRuntime
import com.cyclone.mobile.mind.mission.MindMissions
import com.cyclone.mobile.mind.mission.OwnerRequestKind
import com.cyclone.mobile.mind.mission.OwnerResponse
import com.cyclone.mobile.runtime.background.WorkspaceTaskUi
import com.cyclone.mobile.runtime.background.WorkspaceTasks
import com.cyclone.mobile.ui.overlay.AgentTaskNotificationRuntime
import com.cyclone.mobile.ui.overlay.OverlayChromeRuntime
import com.cyclone.mobile.ui.overlay.OverlayUserAction

/** The app's Task Kit bus: the three engines, the live task, and a record of every command in the run trace. */
object TaskCommands {
    @Volatile private var app: Context? = null

    private val bus: TaskCommandBus by lazy {
        TaskCommandBus(listOf(MindTaskController, ClassicForegroundTaskController, BackgroundWorkspaceTaskController),
            current = { WorkspaceTasks.state.value }, log = ::record)
    }

    fun send(context: Context, taskId: String, command: TaskCommand): TaskCommandResult {
        app = context.applicationContext
        return bus.send(taskId, command)
    }

    internal fun context(): Context = checkNotNull(app) { "TaskCommands used before send()" }

    /**
     * The notification button for [command]. Background workspaces keep their own service intent (that service owns
     * them and is already running); every other task goes through [TaskCommandReceiver] into the bus.
     */
    fun pendingIntent(context: Context, task: WorkspaceTaskUi, command: TaskCommand): PendingIntent {
        if (TaskEngines.of(task) == TaskEngine.BACKGROUND_WORKSPACE) {
            val intent = WorkspaceTasks.commandIntent(context, task, command.wire)
            (command as? TaskCommand.Confirm)?.token?.let { intent.putExtra("confirmation", it) }
            return PendingIntent.getService(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val intent = Intent(context, TaskCommandReceiver::class.java).setAction(ACTION)
            .setData(android.net.Uri.parse("cyclone://task/${task.taskId}/${command.wire}"))
            .putExtra(EXTRA_TASK, task.taskId).putExtra(EXTRA_COMMAND, command.wire)
        (command as? TaskCommand.Confirm)?.token?.let { intent.putExtra(EXTRA_CONFIRMATION, it) }
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun record(task: WorkspaceTaskUi?, command: TaskCommand, result: TaskCommandResult) {
        DeviceState.addLog("Task command ${command.label}: ${if (result.handled) "handled" else "refused"} by ${result.engine ?: "none"} - ${result.detail}")
        val trace = task?.traceSessionId ?: return
        val context = app ?: return
        runCatching {
            AgentTraceRuntime.event(context, trace, "TASK_COMMAND", "${command.label}: ${result.detail}", code = command.wire,
                ok = result.handled, detail = "engine=${result.engine}")
        }
    }

    const val ACTION = "com.cyclone.mobile.TASK_COMMAND"
    const val EXTRA_TASK = "task"
    const val EXTRA_COMMAND = "command"
    const val EXTRA_CONFIRMATION = "confirmation"
}

/** Notification buttons for foreground tasks. Not exported: only Cyclone's own PendingIntents reach it. */
class TaskCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TaskCommands.ACTION) return
        val taskId = intent.getStringExtra(TaskCommands.EXTRA_TASK) ?: return
        val command = TaskCommand.parse(intent.getStringExtra(TaskCommands.EXTRA_COMMAND), intent.getStringExtra(TaskCommands.EXTRA_CONFIRMATION)) ?: return
        TaskCommands.send(context, taskId, command)
    }
}

/** Cyclone Mind missions: every command resolves against what the mission is actually waiting for. */
object MindTaskController : TaskController {
    override val engine = TaskEngine.MIND
    override val supported: Set<Class<out TaskCommand>> = setOf(TaskCommand.Stop::class.java, TaskCommand.TakeOver::class.java,
        TaskCommand.Pause::class.java, TaskCommand.Done::class.java, TaskCommand.Approve::class.java, TaskCommand.Decline::class.java)

    override fun handle(task: WorkspaceTaskUi, command: TaskCommand): TaskCommandResult = when (command) {
        TaskCommand.Stop -> if (MindMissions.isLive()) {
            OverlayChromeRuntime.dispatch(OverlayUserAction.STOP_TASK)
            TaskCommandResult.done(engine, "Stopping the mission.")
        } else {
            WorkspaceTasks.clearClosedTask(task.taskId, task.sessionId)
            AgentTaskNotificationRuntime.cancel(TaskCommands.context())
            TaskCommandResult.done(engine, "Closed the finished mission.")
        }
        TaskCommand.Done -> if (MindMissions.ownerDone()) TaskCommandResult.done(engine, "Cyclone has the phone back and continues.")
            else TaskCommandResult.refused(engine, "The mission is not running.")
        TaskCommand.TakeOver, TaskCommand.Pause -> if (MindMissions.ownerTakesPhone()) TaskCommandResult.done(engine, "You have the phone; tap I'm done to continue.")
            else TaskCommandResult.refused(engine, "The mission is not running.")
        TaskCommand.Approve, TaskCommand.Decline -> {
            val request = MindMissions.inbox.pending.value?.takeIf { it.kind == OwnerRequestKind.APPROVAL }
            if (request != null && MindMissions.answer(request.id, if (command == TaskCommand.Approve) OwnerResponse.Approve else OwnerResponse.Decline))
                TaskCommandResult.done(engine, if (command == TaskCommand.Approve) "Approved." else "Declined.")
            else TaskCommandResult.refused(engine, "Nothing is waiting for approval.")
        }
        else -> TaskCommandResult.refused(engine, "${command.label} is not available for Cyclone Mind.")
    }
}

/** The classic foreground agent, through its existing overlay runtime. */
object ClassicForegroundTaskController : TaskController {
    override val engine = TaskEngine.CLASSIC_FOREGROUND
    override val supported: Set<Class<out TaskCommand>> = setOf(TaskCommand.Stop::class.java, TaskCommand.TakeOver::class.java,
        TaskCommand.Pause::class.java, TaskCommand.Done::class.java, TaskCommand.Autofill::class.java)

    override fun handle(task: WorkspaceTaskUi, command: TaskCommand): TaskCommandResult {
        val refused = OverlayChromeRuntime.commandForegroundTask(task.taskId, command.wire)
        return if (refused == null) TaskCommandResult.done(engine, "${command.label} done.") else TaskCommandResult.refused(engine, refused)
    }
}

/** Background workspace tasks, owned by WorkspaceTaskService. */
object BackgroundWorkspaceTaskController : TaskController {
    override val engine = TaskEngine.BACKGROUND_WORKSPACE
    override val supported: Set<Class<out TaskCommand>> = setOf(TaskCommand.Stop::class.java, TaskCommand.TakeOver::class.java,
        TaskCommand.Pause::class.java, TaskCommand.Done::class.java, TaskCommand.Autofill::class.java, TaskCommand.Confirm::class.java)

    override fun handle(task: WorkspaceTaskUi, command: TaskCommand): TaskCommandResult {
        val context = TaskCommands.context()
        val intent = WorkspaceTasks.commandIntent(context, task, command.wire)
        (command as? TaskCommand.Confirm)?.token?.let { intent.putExtra("confirmation", it) }
        context.startService(intent)
        return TaskCommandResult.done(engine, "${command.label} sent to the workspace.")
    }
}
