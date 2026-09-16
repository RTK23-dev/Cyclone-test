package com.cyclone.mobile.agent.plan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskDifficultyTest {
    @Test
    fun easyIsNamedOpenOrSimpleWebsite() {
        assertEquals(TaskDifficultyTier.EASY, TaskDifficulty.classify("open Facebook"))
        assertEquals(TaskDifficultyTier.EASY, TaskDifficulty.classify("open fb"))
        assertEquals(TaskDifficultyTier.EASY, TaskDifficulty.classify("open google maps"))
        assertEquals(TaskDifficultyTier.EASY, TaskDifficulty.classify("open shopify.com"))
        assertTrue(TaskDifficulty.isNamedAppOpenOnly("open Facebook"))
        assertFalse(TaskDifficulty.isNamedAppOpenOnly("open Facebook and login"))
        assertFalse(TaskDifficulty.isNamedAppOpenOnly("DM Jacob on Instagram that I am late"))
        assertFalse(com.cyclone.mobile.agent.contract.GoalContractCompiler.isSimpleWebNavigation("open Facebook"))
        assertEquals(1, TaskDifficulty.namedAppCount("open google maps"))
        assertEquals(1, TaskDifficulty.namedAppCount("open facebook then fb"))
    }

    @Test
    fun mediumIsOneAppWithInSceneWork() {
        assertEquals(TaskDifficultyTier.MEDIUM, TaskDifficulty.classify("open Facebook and login"))
        assertEquals(TaskDifficultyTier.MEDIUM, TaskDifficulty.classify("DM Jacob on Instagram that I am late"))
        assertEquals(TaskDifficultyTier.MEDIUM, TaskDifficulty.classify("Open Chrome and search for Pixel 8"))
        assertEquals(TaskDifficultyTier.MEDIUM, TaskDifficulty.classify("Open Settings, then Picture-in-picture"))
        assertEquals(TaskDifficultyTier.MEDIUM, TaskDifficulty.classify("open WhatsApp then send a message to Jacob"))
    }

    @Test
    fun hardIsTwoDestinationsAndUsesALocalPlan() {
        assertEquals(TaskDifficultyTier.HARD, TaskDifficulty.classify("open Gmail then send this to WhatsApp"))
        assertEquals(TaskDifficultyTier.HARD, TaskDifficulty.classify("open Facebook then share the post on Instagram"))
        assertEquals(TaskDifficultyTier.HARD, TaskDifficulty.classify("go to shopify.com then email the receipt with Gmail"))
        val assessment = TaskDifficulty.assess("open Gmail then send this to WhatsApp")
        assertEquals(2, assessment.destinationCount)
        assertTrue(assessment.localHardPlan)
        val plan = TaskDifficulty.hardWaypoints("open Gmail then send this to WhatsApp")!!
        assertEquals(2, plan.count { it.kind == WaypointKind.OPEN_APP })
        assertTrue(plan.any { it.packageName == "com.google.android.gm" })
        assertTrue(plan.any { it.packageName == "com.whatsapp" })
    }

    @Test
    fun chromePlusAWebsiteIsOneDestination() {
        val assessment = TaskDifficulty.assess("open chrome and go to shopify.com")
        assertEquals(1, assessment.destinationCount)
        assertEquals(TaskDifficultyTier.EASY, assessment.tier)
    }
}
