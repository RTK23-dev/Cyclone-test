package com.cyclone.mobile.ui.v32

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.mind.mission.MindMissions
import com.cyclone.mobile.mind.mission.Mission
import com.cyclone.mobile.mind.mission.MissionStatus
import com.cyclone.mobile.mind.mission.OwnerRequest
import com.cyclone.mobile.mind.mission.OwnerRequestKind
import com.cyclone.mobile.mind.mission.OwnerResponse

/** The running mission: its plan, what it just did, what it needs from the owner, and Stop. */
@Composable
fun CycloneLiveMissionCard(mission: Mission) {
    val request by MindMissions.inbox.pending.collectAsState()
    CycloneSignatureCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Cyclone Mind · ${statusLabel(mission)}", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary)
            Text(mission.goal, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium,
                maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (mission.plan.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    mission.plan.forEach { step ->
                        Text("${planMark(step.status)}  ${step.text}", style = MaterialTheme.typography.bodySmall,
                            color = if (step.status == "done") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
            mission.events.takeLast(3).forEach { event ->
                Text((if (event.ok) "· " else "! ") + event.text, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            request?.takeIf { it.missionId == mission.id }?.let { OwnerRequestCard(it) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { MindMissions.stop() }, shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Stop the mission" }) { Text("Stop") }
            }
        }
    }
}

@Composable
private fun OwnerRequestCard(request: OwnerRequest) {
    var answer by remember(request.id) { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(request.text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        when (request.kind) {
            OwnerRequestKind.QUESTION -> {
                if (request.choices.isNotEmpty()) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        request.choices.forEach { choice ->
                            CycloneLiquidFilterChip(selected = false, onClick = { MindMissions.answer(request.id, OwnerResponse.Answer(choice)) },
                                label = choice)
                        }
                    }
                }
                OutlinedTextField(answer, { answer = it.take(500) }, Modifier.fillMaxWidth(), label = { Text("Your answer") })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(onClick = { MindMissions.answer(request.id, OwnerResponse.Answer("I'd rather not say; continue without it.")) },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Skip") }
                    TextButton(onClick = { if (answer.isNotBlank()) MindMissions.answer(request.id, OwnerResponse.Answer(answer.trim())) },
                        enabled = answer.isNotBlank(), modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Send") }
                }
            }
            OwnerRequestKind.APPROVAL -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(onClick = { MindMissions.answer(request.id, OwnerResponse.Decline) },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Decline") }
                TextButton(onClick = { MindMissions.answer(request.id, OwnerResponse.Approve) },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Approve") }
            }
            OwnerRequestKind.SECRET -> Text("Use the Secrets Card on screen. Cyclone never sees what you enter.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OwnerRequestKind.CONTROL -> TextButton(onClick = { MindMissions.answer(request.id, OwnerResponse.Done) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Hand the phone back") }
        }
    }
}

/** Missions that ended recently, with Resume where the conversation can continue. */
@Composable
fun CycloneRecentMissions(limit: Int = 4) {
    val context = LocalContext.current
    val history by MindMissions.history.collectAsState()
    val live by MindMissions.live.collectAsState()
    LaunchedEffect(Unit) { MindMissions.refresh(context) }
    val recent = history.filter { it.id != live?.id }.take(limit)
    if (recent.isEmpty()) return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Missions", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp))
        recent.forEach { mission ->
            CycloneSignatureCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(mission.goal, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(statusLabel(mission) + (mission.summary.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TextButton(onClick = { MindMissions.delete(context, mission.id) }, modifier = Modifier.weight(1f).heightIn(min = 44.dp)) {
                            Text("Remove")
                        }
                        if (mission.status.resumable) {
                            TextButton(onClick = { MindMissions.resume(context, mission.id) }, enabled = live == null,
                                modifier = Modifier.weight(1f).heightIn(min = 44.dp)) { Text("Resume") }
                        }
                    }
                }
            }
        }
    }
}

private fun planMark(status: String) = when (status) {
    "done" -> "✓"
    "doing" -> "›"
    "skipped" -> "–"
    else -> "○"
}

private fun statusLabel(mission: Mission): String = when (mission.status) {
    MissionStatus.RUNNING -> "working · ${mission.turns} steps"
    MissionStatus.WAITING -> "waiting for you"
    MissionStatus.COMPLETED -> "done"
    MissionStatus.GAVE_UP -> "not possible"
    MissionStatus.FAILED -> "failed"
    MissionStatus.CANCELLED -> "stopped"
    MissionStatus.PAUSED -> "paused (time used up)"
    MissionStatus.INTERRUPTED -> "interrupted"
}
