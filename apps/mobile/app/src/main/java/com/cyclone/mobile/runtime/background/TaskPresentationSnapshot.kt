package com.cyclone.mobile.runtime.background

/**
 * Consumer-facing projection of a task.
 *
 * This is deliberately read-only: it never executes tools, changes controller ownership, or
 * manufactures verification. Every field is derived from the authoritative WorkspaceTaskUi /
 * TaskHarnessState evidence already owned by the runtime.
 */
enum class TaskConsumerState { WORKING, ACTION_NEEDED, DONE, FAILED }

data class TaskPresentationMilestone(
    val label: String,
    val state: SemanticStepState,
)

/**
 * Consumer compaction only. The authoritative semanticSteps list stays untouched for diagnostics.
 * Adjacent operations with the same safe label are folded into one visual milestone so repeated
 * verified taps/scrolls do not turn the chat card into an execution log.
 */
object TaskMilestoneProjector {
    fun project(steps: List<SemanticTaskStep>): List<TaskPresentationMilestone> {
        val out = mutableListOf<TaskPresentationMilestone>()
        steps.forEach { step ->
            val label = step.label.trim().take(90)
            if (label.isBlank()) return@forEach
            val previous = out.lastOrNull()
            if (previous != null && previous.label.equals(label, ignoreCase = true)) {
                out[out.lastIndex] = TaskPresentationMilestone(label, merge(previous.state, step.state))
            } else {
                out += TaskPresentationMilestone(label, step.state)
            }
        }
        return out.takeLast(8)
    }

    private fun merge(previous: SemanticStepState, next: SemanticStepState): SemanticStepState = when {
        next == SemanticStepState.ACTION_NEEDED -> next
        next == SemanticStepState.FAILED -> next
        next == SemanticStepState.ACTIVE -> next
        next == SemanticStepState.DONE -> SemanticStepState.DONE
        previous == SemanticStepState.DONE -> previous
        else -> next
    }
}

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
        val milestones = TaskMilestoneProjector.project(semantic)
        val verifiedOperations = milestones.filter { it.state == SemanticStepState.DONE }
        val activeOperation = milestones.lastOrNull {
            it.state == SemanticStepState.ACTIVE || it.state == SemanticStepState.ACTION_NEEDED
        }

        val planned = task.plannedMilestones.filter(String::isNotBlank).take(8)
        val planIndex = when (state) {
            TaskConsumerState.DONE -> planned.size
            else -> task.plannedMilestoneIndex.coerceIn(0, planned.size)
        }
        val total = planned.size.takeIf { it > 0 }
        val completedCount = if (total != null) planIndex else verifiedOperations.size
        val fraction = total?.let { denominator ->
            (completedCount.toFloat() / denominator.toFloat()).coerceIn(0f, 1f)
        } ?: if (state == TaskConsumerState.DONE) 1f else null

        val currentMilestone = activeOperation?.label?.takeIf(String::isNotBlank)
            ?: planned.getOrNull(planIndex)
            ?: task.subtitle.takeIf(String::isNotBlank)

        val supportingCopy = when (state) {
            TaskConsumerState.WORKING -> when {
                total != null -> "$completedCount of $total complete"
                verifiedOperations.isNotEmpty() ->
                    "${verifiedOperations.size} verified step${if (verifiedOperations.size == 1) "" else "s"} complete"
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
            completedMilestones = if (planned.isNotEmpty()) planned.take(planIndex).takeLast(4)
                else verifiedOperations.map { it.label }.filter(String::isNotBlank).takeLast(4),
            completedCount = completedCount,
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
