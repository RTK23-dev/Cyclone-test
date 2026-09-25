package com.cyclone.mobile.ui.v32

import com.cyclone.mobile.mind.mission.OwnerField
import com.cyclone.mobile.mind.mission.OwnerInbox
import com.cyclone.mobile.mind.mission.OwnerRequestKind
import com.cyclone.mobile.mind.mission.OwnerResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnerCardCopyTest {
    @Test fun valuesAndQuestionsOpenOverOtherAppsHandBacksUseTheRibbon() {
        val inbox = OwnerInbox()
        val values = inbox.post("m", OwnerRequestKind.VALUES, "Sign-up needs your name", fields = listOf(OwnerField("First name", "name")))
        assertTrue(OwnerCardCopy.overlayCard(values))
        assertEquals("First name", values.fields.single().label)
        assertTrue(OwnerCardCopy.overlayCard(inbox.post("m", OwnerRequestKind.QUESTION, "Which account?")))
        assertFalse(OwnerCardCopy.overlayCard(inbox.post("m", OwnerRequestKind.CONTROL, "Solve the CAPTCHA")))
        assertFalse(OwnerCardCopy.overlayCard(null))
    }

    @Test fun dismissingNeverLeavesTheMissionHanging() {
        assertTrue(OwnerCardCopy.dismissal(OwnerRequestKind.QUESTION) is OwnerResponse.Answer)
        assertEquals(OwnerResponse.Decline, OwnerCardCopy.dismissal(OwnerRequestKind.VALUES))
        assertEquals("e.g. 12 March 1990", OwnerCardCopy.placeholder(OwnerField("Birth date", "date")))
    }

    @Test fun takeOverAndValuesAreDeliveredOnce() {
        val inbox = OwnerInbox()
        val request = inbox.post("m", OwnerRequestKind.VALUES, "x", fields = listOf(OwnerField("First name")))
        assertTrue(inbox.respond(request.id, OwnerResponse.Values(mapOf("First name" to "Jan"), remember = false)))
        assertFalse(inbox.respond(request.id, OwnerResponse.TakeOver))
        assertEquals(OwnerResponse.Values(mapOf("First name" to "Jan"), false), inbox.poll(request.id))
    }
}
