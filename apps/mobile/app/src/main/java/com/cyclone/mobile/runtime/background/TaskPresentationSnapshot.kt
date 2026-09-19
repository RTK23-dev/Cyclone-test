package com.cyclone.mobile.runtime.background

/**
 * Consumer-facing projection of a task.
 *
 * This is deliberately read-only: it never executes tools, changes controller ownership, or
 * manufactures verification. Every field is derived from the authoritative WorkspaceTaskUi /
 * TaskHarnessState evidence already owned by the runtime.
 */
enum class TaskConsumerState { WORKING, ACTION_NEEDED, DONE, FAILED }

enum class TaskFollowUpAction {
    VIEW_DETAILS,
    RUN_AGAIN,
    TRY_AGAIN,
    TAKE_OVER,
    AUTOFILL,
    CONTINUE,
}

data class TaskPresentationSnapshot(
    val taskId: String,
    val app: String,
    val packageName: String,
    val title: String,
    val state: TaskConsumerState,
    val currentMilestone: String?,
    val completedMilestones: List<String>,
    val completedCount: Int,
    val totalCount: Int?,
    /** Null means the runtime does not yet know a stable denominator. */
    val progressFraction: Float?,
    val supportingCopy: String?,
    val outcomeCopy: String?,
    val followUps: List<TaskFollowUpAction>,
)

object TaskPresentationProjector {
    fun project(task: WorkspaceTaskUi): TaskPresentationSnapshot {
        val state = when (task.phase) {
            TaskPhase.STARTING, TaskPhase.WORKING -> TaskConsumerState.WORKING
            TaskPhase.PAUSED, TaskPhase.REVIEW, TaskPhase.HUMAN -> TaskConsumerState.ACTION_NEEDED
            TaskPhase.DONE -> TaskConsumerState.DONE
            TaskPhase.FAILED, TaskPhase.STOPPED -> TaskConsumerState.FAILED
        }

        val semantic = task.semanticSteps
        val completed = semantic.filter { it.state == SemanticStepState.DONE }
        val pending = semantic.count { it.state == SemanticStepState.PENDING }
        val active = semantic.lastOrNull {
            it.state == SemanticStepState.ACTIVE || it.state == SemanticStepState.ACTION_NEEDED
        }

        // A denominator is stable only when a plan has explicitly published pending milestones, or
        // the task is terminal. The current operation stream grows as work proceeds, so using its
        // current size as a working denominator would create fake backwards progress.
        val totalKnown = pending > 0 || state == TaskConsumerState.DONE
        val total = semantic.size.takeIf { totalKnown && it > 0 }
        val fraction = total?.let { denominator ->
            (completed.size.toFloat() / denominator.toFloat()).coerceIn(0f, 1f)
        } ?: if (state == TaskConsumerState.DONE) 1f else null

        val currentMilestone = active?.label?.takeIf(String::isNotBlank)
            ?: task.subtitle.takeIf(String::isNotBlank)

        val supportingCopy = when (state) {
            TaskConsumerState.WORKING -> when {
                completed.isNotEmpty() -> "${completed.size} verified step${if (completed.size == 1) "" else "s"} complete"
                else -> currentMilestone
            }
            TaskConsumerState.ACTION_NEEDED -> task.interruption?.prompt?.takeIf(String::isNotBlank)
                ?: task.subtitle.takeIf(String::isNotBlank)
            TaskConsumerState.DONE -> task.outcome?.takeIf(String::isNotBlank)
                ?: "The requested result was checked."
            TaskConsumerState.FAILED -> task.outcome?.takeIf(String::isNotBlank)
                ?: task.subtitle.takeIf(String::isNotBlank)
        }

        return TaskPresentationSnapshot(
            taskId = task.taskId,
            app = task.app,
            packageName = task.packageName,
            title = consumerTaskTitle(task),
            state = state,
            currentMilestone = currentMilestone,
            completedMilestones = completed.map { it.label }.filter(String::isNotBlank).takeLast(4),
            completedCount = completed.size,
            totalCount = total,
            progressFraction = fraction,
            supportingCopy = supportingCopy,
            outcomeCopy = task.outcome?.takeIf(String::isNotBlank),
            followUps = TaskFollowUpPolicy.actions(task, state),
        )
    }

    private fun consumerTaskTitle(task: WorkspaceTaskUi): String {
        val clean = task.goal.trim().replace(Regex("\\s+"), " ")
        if (clean.isBlank()) return task.app.ifBlank { "Phone task" }
        val app = task.app.takeIf { it.isNotBlank() && !it.equals("Other", true) }
        val lower = clean.lowercase()
        return when {
            "battery" in lower && ("setting" in lower || app.equals("Settings", true)) ->
                "Checking battery settings"
            Regex("\\b(logged? ?in|log ?in|login|signed? ?in|sign ?in)\\b", RegexOption.IGNORE_CASE)
                .containsMatchIn(clean) -> "Checking ${app ?: "app"} login status"
            app != null && Regex("\\b(open|launch|start)\\b", RegexOption.IGNORE_CASE).containsMatchIn(clean) ->
                "Opening $app"
            app != null && Regex("\\b(check|verify|see|review)\\b", RegexOption.IGNORE_CASE).containsMatchIn(clean) ->
                "Checking $app"
            app != null && Regex("\\b(find|search|look for)\\b", RegexOption.IGNORE_CASE).containsMatchIn(clean) ->
                "Searching $app"
            app != null -> "Task in $app"
            else -> "Phone task"
        }
    }
}

object TaskFollowUpPolicy {
    fun actions(task: WorkspaceTaskUi, state: TaskConsumerState): List<TaskFollowUpAction> = buildList {
        when (state) {
            TaskConsumerState.WORKING -> add(TaskFollowUpAction.VIEW_DETAILS)
            TaskConsumerState.ACTION_NEEDED -> {
                if (task.interruption?.canAutofill == true) add(TaskFollowUpAction.AUTOFILL)
                if (task.interruption?.canTakeOver == true) add(TaskFollowUpAction.TAKE_OVER)
                if (task.interruption?.canResumeAfterHuman == true && task.resumable && task.confirmation == null) {
                    add(TaskFollowUpAction.CONTINUE)
                }
                add(TaskFollowUpAction.VIEW_DETAILS)
            }
            TaskConsumerState.DONE -> {
                add(TaskFollowUpAction.VIEW_DETAILS)
                add(TaskFollowUpAction.RUN_AGAIN)
            }
            TaskConsumerState.FAILED -> {
                add(TaskFollowUpAction.TRY_AGAIN)
                add(TaskFollowUpAction.VIEW_DETAILS)
            }
        }
    }
}
