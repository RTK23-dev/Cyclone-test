package com.cyclone.mobile.runtime.background

/** Both notification surfaces use the task's state and the same service commands as in-app controls. */
object TaskNotificationProjection {
    fun title(task: WorkspaceTaskUi): String = TaskPresentationProjector.project(task).title
    fun body(task: WorkspaceTaskUi): String {
        val snapshot = TaskPresentationProjector.project(task)
        return (if (task.working) snapshot.currentMilestone else snapshot.supportingCopy)
            ?: TaskConsumerCopy.subtitle(task)
    }
    /** No invented percentage when the agent has no stable plan. */
    fun progressPercent(task: WorkspaceTaskUi): Int? = if (!task.working) null else
        TaskPresentationProjector.project(task).progressFraction?.let { (it * 100).toInt().coerceIn(0, 100) }
    fun actions(task: WorkspaceTaskUi): List<Pair<String, String>> = buildList {
        if (task.interruption?.canTakeOver == true) add("handoff" to "Take Over")
        if (task.interruption?.canAutofill == true) add("autofill" to "Autofill")
        if (task.interruption?.canResumeAfterHuman == true) add("resume" to "I'm Done")
        if (task.confirmation != null && task.interruption?.confirmationToken == task.confirmation.token)
            add("confirm" to task.confirmation.button)
        if (task.working) add("cancel" to "Stop task")
    }.take(3)
}
