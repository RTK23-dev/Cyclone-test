package com.cyclone.mobile.runtime.background

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskPresentationSnapshotTest {
    private fun task(
        phase: TaskPhase = TaskPhase.WORKING,
        semantic: List<SemanticTaskStep> = emptyList(),
        interruption: TaskInterruption? = null,
        outcome: String? = null,
        resumable: Boolean = true,
    ) = WorkspaceTaskUi(
        taskId = "task-1",
        sessionId = "session-1",
        app = "Instagram",
        packageName = "com.instagram.android",
        goal = "Open Instagram and check my login status",
        phase = phase,
        semanticSteps = semantic,
        interruption = interruption,
        displayId = 0,
        outcome = outcome,
        resumable = resumable,
    )

    @Test
    fun workingOperationStreamDoesNotInventStableProgressDenominator() {
        val snapshot = TaskPresentationProjector.project(
            task(
                semantic = listOf(
                    SemanticTaskStep(1, "Opening Instagram", SemanticStepState.DONE),
                    SemanticTaskStep(2, "Checking login status", SemanticStepState.ACTIVE),
                ),
            ),
        )

        assertEquals(TaskConsumerState.WORKING, snapshot.state)
        assertEquals("Checking Instagram login status", snapshot.title)
        assertEquals("Checking login status", snapshot.currentMilestone)
        assertEquals(1, snapshot.completedCount)
        assertNull(snapshot.totalCount)
        assertNull(snapshot.progressFraction)
        assertTrue(snapshot.supportingCopy!!.contains("1 verified step"))
    }

    @Test
    fun explicitPendingMilestonesPermitDeterminateProgress() {
        val snapshot = TaskPresentationProjector.project(
            task(
                semantic = listOf(
                    SemanticTaskStep(1, "Opening Instagram", SemanticStepState.DONE),
                    SemanticTaskStep(2, "Checking login status", SemanticStepState.ACTIVE),
                    SemanticTaskStep(3, "Verifying result", SemanticStepState.PENDING),
                    SemanticTaskStep(4, "Complete", SemanticStepState.PENDING),
                ),
            ),
        )

        assertEquals(4, snapshot.totalCount)
        assertEquals(1, snapshot.completedCount)
        assertEquals(.25f, snapshot.progressFraction!!, .0001f)
    }

    @Test
    fun doneStateUsesOnlyBoundedOutcomeAndOffersViewDetailsRunAgain() {
        val snapshot = TaskPresentationProjector.project(
            task(
                phase = TaskPhase.DONE,
                semantic = listOf(
                    SemanticTaskStep(1, "Opening Instagram", SemanticStepState.DONE),
                    SemanticTaskStep(2, "Checking login status", SemanticStepState.DONE),
                ),
                outcome = "You're logged in to Instagram.",
            ),
        )

        assertEquals(TaskConsumerState.DONE, snapshot.state)
        assertEquals("You're logged in to Instagram.", snapshot.outcomeCopy)
        assertEquals(1f, snapshot.progressFraction!!, .0001f)
        assertEquals(
            listOf(TaskFollowUpAction.VIEW_DETAILS, TaskFollowUpAction.RUN_AGAIN),
            snapshot.followUps,
        )
    }

    @Test
    fun humanBoundaryExposesOnlyRuntimeAuthorizedActions() {
        val snapshot = TaskPresentationProjector.project(
            task(
                phase = TaskPhase.HUMAN,
                interruption = TaskInterruption(
                    reason = "LOGIN_WALL",
                    prompt = "Finish sign-in, then continue.",
                    canTakeOver = false,
                    canResumeAfterHuman = true,
                    canAutofill = true,
                ),
            ),
        )

        assertEquals(TaskConsumerState.ACTION_NEEDED, snapshot.state)
        assertTrue(TaskFollowUpAction.AUTOFILL in snapshot.followUps)
        assertTrue(TaskFollowUpAction.CONTINUE in snapshot.followUps)
        assertFalse(TaskFollowUpAction.TAKE_OVER in snapshot.followUps)
        assertTrue(TaskFollowUpAction.VIEW_DETAILS in snapshot.followUps)
    }

    @Test
    fun failedStateOffersRetryWithoutPretendingSuccess() {
        val snapshot = TaskPresentationProjector.project(
            task(phase = TaskPhase.FAILED, outcome = "I couldn't finish. Your place is saved."),
        )

        assertEquals(TaskConsumerState.FAILED, snapshot.state)
        assertNull(snapshot.progressFraction)
        assertEquals(
            listOf(TaskFollowUpAction.TRY_AGAIN, TaskFollowUpAction.VIEW_DETAILS),
            snapshot.followUps,
        )
    }
}
