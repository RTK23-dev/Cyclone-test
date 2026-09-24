package com.cyclone.mobile.agent.nav

import com.cyclone.mobile.agent.plan.TaskTrajectory
import com.cyclone.mobile.agent.plan.WaypointKind
import com.cyclone.mobile.applearner.ActionRisk
import com.cyclone.mobile.applearner.PageContext
import com.cyclone.mobile.applearner.PageControl
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Fixed operator sentences. Extended with execution/proof assertions as navigation is built. */
class MultiAppScenarioTest {
    @Test
    fun everyScenarioPreservesTheOriginalSentence() {
        SCENARIOS.forEach { goal -> assertEquals(goal, TaskTrajectory.seed(goal).to) }
    }

    @Test
    fun openingGmailDoesNotCompleteTheMultiAppRequest() {
        val trajectory = TaskTrajectory.seed(SCENARIOS[1]).advanceIfSatisfied(
            page("com.google.android.gm", "Inbox", "Compose", "Search mail"),
        )
        assertFalse(trajectory.current == null)
        assertFalse(trajectory.current?.kind == WaypointKind.DONE)
    }

    companion object {
        val SCENARIOS = listOf(
            "open Gmail and tell me which Gmail I am logged in with",
            "open Gmail and check which Gmail I am logged in with, then open Chrome and go to instagram.com sign-up with that email",
            "open the clock app and set a timer for 5 minutes",
            "find the DM of Louella on Facebook",
            "open Settings, then Wi-Fi, then tell me the connected network name",
        )

        fun page(packageName: String, title: String, vararg labels: String) = PageContext(
            pageKey = "$packageName:$title", packageName = packageName, className = null,
            title = title, structuralKey = title, contentKey = labels.joinToString("|"),
            controls = labels.mapIndexed { index, label -> PageControl(
                key = "e$index", label = label, semanticName = label, role = "text",
                selector = JSONObject().put("elementId", "e$index"), androidActions = emptyList(),
                risk = ActionRisk.SAFE,
            ) }, observationCount = 1, firstSeenAt = 1, lastSeenAt = 1,
        )
    }
}
