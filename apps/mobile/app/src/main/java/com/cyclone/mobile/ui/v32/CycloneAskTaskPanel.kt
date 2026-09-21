package com.cyclone.mobile.ui.v32

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.ai.TaskResultActivityV292
import com.cyclone.mobile.runtime.background.TaskFollowUpAction
import com.cyclone.mobile.runtime.background.TaskPresentationProjector
import com.cyclone.mobile.runtime.background.TaskPresentationSnapshot
import com.cyclone.mobile.runtime.background.TaskRunInformationLive
import com.cyclone.mobile.runtime.background.WorkspaceTaskUi
import com.cyclone.mobile.runtime.background.WorkspaceTasks

/**
 * Ask Cyclone's single stateful task surface.
 *
 * One physical card morphs between Working, Action needed, Done and Failed. Copy/progress comes
 * from TaskPresentationProjector, so in-app chat, overlay and notifications can converge on the
 * same grounded task evidence instead of inventing state separately.
 */
@Composable
fun CycloneAskTaskPanel(task: WorkspaceTaskUi) {
    CycloneSignatureTheme { SignatureAskTaskPanel(task) }
}

@Composable
private fun SignatureAskTaskPanel(task: WorkspaceTaskUi) {
    val context = LocalContext.current
    val resolvedApp = remember(task.packageName) { appLabel(context, task.packageName) }
    val projectedTask = remember(task, resolvedApp) {
        if (resolvedApp.isNotBlank() && resolvedApp != "Other") task.copy(app = resolvedApp) else task
    }
    val liveInfo = remember(projectedTask.traceSessionId, projectedTask.phase, projectedTask.startedAtMs) {
        TaskRunInformationLive.load(projectedTask)
    }
    val snapshot = remember(projectedTask, liveInfo) {
        TaskPresentationProjector.project(projectedTask, liveInfo)
    }
    val visualState = task.taskVisualState()
    var progressExpanded by rememberSaveable(task.taskId) { mutableStateOf(true) }
    val palette = cycloneConversationPalette()

    val targetOutline = when (visualState) {
        CycloneTaskVisualState.WORKING -> palette.cardOutline.copy(alpha = .55f)
        CycloneTaskVisualState.ACTION_NEEDED -> palette.attention.copy(alpha = .26f)
        CycloneTaskVisualState.DONE -> palette.success.copy(alpha = .26f)
        CycloneTaskVisualState.FAILED -> palette.failure.copy(alpha = .28f)
    }
    val outline by animateColorAsState(
        targetValue = targetOutline,
        animationSpec = tween(CycloneConversationTokens.stateTransitionMs),
        label = "task-card-outline",
    )

    CycloneSignatureCard(
        modifier = Modifier.fillMaxWidth()
            .animateContentSize(animationSpec = tween(220))
            .clip(RoundedCornerShape(CycloneConversationTokens.taskRadius))
            .border(.6.dp, outline, RoundedCornerShape(CycloneConversationTokens.taskRadius)),
        cornerRadius = CycloneConversationTokens.taskRadius,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(
                horizontal = CycloneConversationTokens.space16,
                vertical = 15.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(CycloneConversationTokens.space12),
        ) {
            TaskCardHeader(
                snapshot = snapshot,
                state = visualState,
                expanded = progressExpanded,
                onToggle = { progressExpanded = !progressExpanded },
            )

            Text(
                snapshot.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            if (!progressExpanded) {
                snapshot.collapsedSummary?.takeIf(String::isNotBlank)?.let { summary ->
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            AnimatedContent(
                targetState = visualState,
                transitionSpec = {
                    fadeIn(tween(CycloneConversationTokens.stateTransitionMs)) togetherWith
                        fadeOut(tween(CycloneConversationTokens.fastTransitionMs))
                },
                label = "Cyclone task state",
            ) { state ->
                when (state) {
                    CycloneTaskVisualState.WORKING -> WorkingBody(
                        snapshot = snapshot,
                        expanded = progressExpanded,
                        onDownload = { openRunLogs(context, snapshot.traceSessionId) },
                    )
                    CycloneTaskVisualState.ACTION_NEEDED -> ActionNeededBody(
                        task = task,
                        snapshot = snapshot,
                        onTakeOver = { WorkspaceTasks.command(context, task, "handoff") },
                        onAutofill = { WorkspaceTasks.command(context, task, "autofill") },
                        onDone = { WorkspaceTasks.command(context, task, "resume") },
                        expanded = progressExpanded,
                        onReviewRequest = { UiTask(task).open(context) },
                        onDownload = { openRunLogs(context, snapshot.traceSessionId) },
                    )
                    CycloneTaskVisualState.DONE -> TerminalBody(
                        snapshot = snapshot,
                        primaryAction = TaskFollowUpAction.RUN_AGAIN,
                        onPrimary = { rerunTask(context, task) },
                        expanded = progressExpanded,
                        onOpenApp = { openInstalledApp(context, snapshot.packageName.ifBlank { task.packageName }) },
                        onDownload = { openRunLogs(context, snapshot.traceSessionId) },
                    )
                    CycloneTaskVisualState.FAILED -> TerminalBody(
                        snapshot = snapshot,
                        primaryAction = TaskFollowUpAction.TRY_AGAIN,
                        onPrimary = { rerunTask(context, task) },
                        expanded = progressExpanded,
                        onOpenApp = { openInstalledApp(context, snapshot.packageName.ifBlank { task.packageName }) },
                        onDownload = { openRunLogs(context, snapshot.traceSessionId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TaskCardHeader(
    snapshot: TaskPresentationSnapshot,
    state: CycloneTaskVisualState,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(
                onClickLabel = if (expanded) "Collapse task progress" else "Expand task progress",
            ) { onToggle() }
            .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CycloneConversationTokens.space8),
    ) {
        CycloneAppIcon(snapshot.packageName, Modifier.size(26.dp))
        Text(
            snapshot.destinationChain ?: snapshot.app.ifBlank { "Cyclone" },
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            maxLines = 2,
        )
        CycloneTaskStatusPill(state)
        Icon(
            if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WorkingBody(
    snapshot: TaskPresentationSnapshot,
    expanded: Boolean,
    onDownload: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(CycloneConversationTokens.space8)) {
        if (expanded) {
            CycloneTaskProgressIndicator(snapshot.progressFraction)
            val countLabel = snapshot.totalCount?.let { total ->
                "${snapshot.completedCount} of $total stages complete"
            } ?: snapshot.supportingCopy
            countLabel?.takeIf(String::isNotBlank)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            CycloneTaskCheckpoints(snapshot)
            RunInformationSection(snapshot, onDownload)
        } else {
            snapshot.currentMilestone?.takeIf(String::isNotBlank)?.let { current ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(CycloneConversationTokens.space8),
                ) {
                    CycloneNineDotSpinner()
                    Text(
                        current,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionNeededBody(
    task: WorkspaceTaskUi,
    snapshot: TaskPresentationSnapshot,
    onTakeOver: () -> Unit,
    onAutofill: () -> Unit,
    onDone: () -> Unit,
    expanded: Boolean,
    onReviewRequest: () -> Unit,
    onDownload: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(CycloneConversationTokens.space12)) {
        snapshot.supportingCopy?.takeIf(String::isNotBlank)?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (TaskFollowUpAction.AUTOFILL in snapshot.followUps) {
            Button(
                onClick = onAutofill,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                    contentDescription = "Autofill sign-in for ${task.app}"
                },
                shape = RoundedCornerShape(15.dp),
            ) { Text("Autofill") }
        }

        val takeOver = TaskFollowUpAction.TAKE_OVER in snapshot.followUps
        val continueTask = TaskFollowUpAction.CONTINUE in snapshot.followUps
        if (takeOver || continueTask) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CycloneConversationTokens.space8)) {
                if (takeOver) {
                    OutlinedButton(
                        onClick = onTakeOver,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        shape = RoundedCornerShape(15.dp),
                    ) { Text("Take Over") }
                }
                if (continueTask) {
                    OutlinedButton(
                        onClick = onDone,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        shape = RoundedCornerShape(15.dp),
                    ) { Text("I'm Done") }
                }
            }
        }

        if (expanded) {
            if (snapshot.milestones.isNotEmpty() || snapshot.stages.isNotEmpty()) {
                CycloneTaskCheckpoints(snapshot)
            }
            RunInformationSection(snapshot, onDownload)
        }
        if (task.confirmation != null) {
            TextButton(
                onClick = onReviewRequest,
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp),
            ) { Text("Review request") }
        }
    }
}

@Composable
private fun TerminalBody(
    snapshot: TaskPresentationSnapshot,
    primaryAction: TaskFollowUpAction,
    onPrimary: () -> Unit,
    expanded: Boolean,
    onOpenApp: () -> Unit,
    onDownload: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(CycloneConversationTokens.space12)) {
        snapshot.outcomeCopy?.takeIf(String::isNotBlank)?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        } ?: snapshot.supportingCopy?.takeIf(String::isNotBlank)?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (expanded) {
            if (snapshot.milestones.isNotEmpty() || snapshot.stages.isNotEmpty()) {
                CycloneTaskCheckpoints(snapshot)
            }
            RunInformationSection(snapshot, onDownload)
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(CycloneConversationTokens.space8),
        ) {
            if (primaryAction in snapshot.followUps) {
                Button(
                    onClick = onPrimary,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    shape = RoundedCornerShape(15.dp),
                ) {
                    Text(
                        when (primaryAction) {
                            TaskFollowUpAction.RUN_AGAIN -> "Run again"
                            TaskFollowUpAction.TRY_AGAIN -> "Try again"
                            else -> "Continue"
                        },
                    )
                }
            }
        }

        if (TaskFollowUpAction.OPEN_APP in snapshot.followUps) {
            TextButton(
                onClick = onOpenApp,
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp),
            ) { Text("Open app") }
        }
    }
}

@Composable
private fun RunInformationSection(
    snapshot: TaskPresentationSnapshot,
    onDownload: () -> Unit,
) {
    val info = snapshot.runInformation
    Column(verticalArrangement = Arrangement.spacedBy(CycloneConversationTokens.space4)) {
        Text(
            "Run information",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        info?.elapsedLabel?.let { elapsed ->
            val waiting = if (info.waiting) " · waiting" else ""
            Text("Elapsed $elapsed$waiting", style = MaterialTheme.typography.bodySmall)
        }
        info?.modelName?.let { Text("Model $it", style = MaterialTheme.typography.bodySmall) }
        info?.modelRequests?.let { Text("Model requests $it", style = MaterialTheme.typography.bodySmall) }
        info?.toolActions?.let { Text("Tool actions $it", style = MaterialTheme.typography.bodySmall) }
        Text(
            if (info?.tokensReported == true) {
                "Tokens ${info.tokensInput ?: 0} in / ${info.tokensOutput ?: 0} out"
            } else {
                "Tokens not reported"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (info?.inProgress == true) {
            Text(
                "In progress at export",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(
            onClick = onDownload,
            enabled = !snapshot.traceSessionId.isNullOrBlank(),
            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp),
        ) { Text("Download logs") }
    }
}

private fun openRunLogs(context: android.content.Context, traceSessionId: String?) {
    val sessionId = traceSessionId?.takeIf { it.isNotBlank() } ?: return
    runCatching {
        context.startActivity(
            android.content.Intent(context, TaskResultActivityV292::class.java)
                .putExtra(TaskResultActivityV292.EXTRA_SESSION_ID, sessionId)
                .putExtra(TaskResultActivityV292.EXTRA_EXPORT, true)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

private fun openInstalledApp(context: android.content.Context, packageName: String) {
    if (packageName.isBlank()) return
    context.packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
        runCatching {
            context.startActivity(launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

private fun rerunTask(context: android.content.Context, task: WorkspaceTaskUi) {
    runCatching {
        WorkspaceTasks.queueRequest(
            goal = task.goal,
            targetPackageName = task.packageName.takeIf(String::isNotBlank),
            targetAppLabel = task.app.takeIf(String::isNotBlank),
        )
        WorkspaceTasks.tryPromoteNext(context.applicationContext)
    }
}
