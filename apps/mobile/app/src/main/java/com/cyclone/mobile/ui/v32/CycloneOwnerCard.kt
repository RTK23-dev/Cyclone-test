package com.cyclone.mobile.ui.v32

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.mind.mission.MindMissions
import com.cyclone.mobile.mind.mission.OwnerField
import com.cyclone.mobile.mind.mission.OwnerRequest
import com.cyclone.mobile.mind.mission.OwnerRequestKind
import com.cyclone.mobile.mind.mission.OwnerResponse

/**
 * The one card through which a running mission talks to its owner, the same in the overlay (over any app) and in Ask.
 * Every card offers the two ways forward a person expects: give Cyclone what it needs right here, or take the phone
 * and do it yourself. Secrets never come through this card; they have the Secrets Card.
 */
@Composable
fun CycloneOwnerCard(request: OwnerRequest, modifier: Modifier = Modifier, framed: Boolean = true) {
    val content: @Composable () -> Unit = { OwnerCardContent(request) }
    if (framed) CycloneSignatureCard(modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { content() } }
    else Column(modifier.fillMaxWidth()) { content() }
}

@Composable
private fun OwnerCardContent(request: OwnerRequest) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(OwnerCardCopy.title(request.kind), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(request.text, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            }
            if (request.kind == OwnerRequestKind.VALUES || request.kind == OwnerRequestKind.QUESTION) {
                IconButton(onClick = { MindMissions.answer(request.id, OwnerCardCopy.dismissal(request.kind)) }, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Rounded.Close, contentDescription = "Not now")
                }
            }
        }
        when (request.kind) {
            OwnerRequestKind.VALUES -> ValuesBody(request)
            OwnerRequestKind.QUESTION -> QuestionBody(request)
            OwnerRequestKind.CONTROL -> Button(
                onClick = { MindMissions.ownerDone() },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = "I'm done, continue with Cyclone" },
                shape = RoundedCornerShape(16.dp),
            ) { Text("I'm done") }
            OwnerRequestKind.APPROVAL -> TwoButtons(
                secondary = "Decline", onSecondary = { MindMissions.answer(request.id, OwnerResponse.Decline) },
                primary = "Approve", primaryEnabled = true, onPrimary = { MindMissions.answer(request.id, OwnerResponse.Approve) },
            )
            OwnerRequestKind.SECRET -> Text("Use the Secrets Card on screen. Cyclone never sees what you enter.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ValuesBody(request: OwnerRequest) {
    val values = remember(request.id) { mutableStateMapOf<String, String>() }
    var remember by remember(request.id) { mutableStateOf(false) }
    request.fields.forEachIndexed { index, field ->
        if (field.kind == "choice" && field.choices.isNotEmpty()) {
            Text(field.label, style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                field.choices.forEach { choice ->
                    CycloneLiquidFilterChip(selected = values[field.label] == choice, onClick = { values[field.label] = choice }, label = choice)
                }
            }
        } else {
            OutlinedTextField(
                value = values[field.label].orEmpty(),
                onValueChange = { values[field.label] = it.take(300) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(field.label) },
                placeholder = OwnerCardCopy.placeholder(field)?.let { hint -> { Text(hint) } },
                singleLine = field.kind != "address",
                keyboardOptions = OwnerCardCopy.keyboard(field, last = index == request.fields.lastIndex),
            )
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = remember, onCheckedChange = { remember = it })
        Text("Remember for next time", style = MaterialTheme.typography.bodySmall)
    }
    TwoButtons(
        secondary = "Take over", onSecondary = { MindMissions.answer(request.id, OwnerResponse.TakeOver) },
        primary = "Fill in", primaryEnabled = values.values.any { it.isNotBlank() },
        onPrimary = { MindMissions.answer(request.id, OwnerResponse.Values(values.filterValues { it.isNotBlank() }.toMap(), remember)) },
    )
}

@Composable
private fun QuestionBody(request: OwnerRequest) {
    var answer by remember(request.id) { mutableStateOf("") }
    if (request.choices.isNotEmpty()) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            request.choices.forEach { choice ->
                CycloneLiquidFilterChip(selected = false, onClick = { MindMissions.answer(request.id, OwnerResponse.Answer(choice)) }, label = choice)
            }
        }
    }
    OutlinedTextField(answer, { answer = it.take(500) }, Modifier.fillMaxWidth(), label = { Text("Your answer") },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send))
    TwoButtons(
        secondary = "Take over", onSecondary = { MindMissions.answer(request.id, OwnerResponse.TakeOver) },
        primary = "Send", primaryEnabled = answer.isNotBlank(),
        onPrimary = { MindMissions.answer(request.id, OwnerResponse.Answer(answer.trim())) },
    )
}

@Composable
private fun TwoButtons(secondary: String, onSecondary: () -> Unit, primary: String, primaryEnabled: Boolean, onPrimary: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(onClick = onSecondary, modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(16.dp)) { Text(secondary) }
        Button(onClick = onPrimary, enabled = primaryEnabled, modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(16.dp)) { Text(primary) }
    }
}

/** Words and keyboards for the owner card; kept apart from the composables so they are unit-testable. */
object OwnerCardCopy {
    fun title(kind: OwnerRequestKind): String = when (kind) {
        OwnerRequestKind.VALUES -> "Cyclone needs a few details"
        OwnerRequestKind.QUESTION -> "Cyclone asks"
        OwnerRequestKind.CONTROL -> "Your turn"
        OwnerRequestKind.APPROVAL -> "Approve this?"
        OwnerRequestKind.SECRET -> "Secure input"
    }

    fun dismissal(kind: OwnerRequestKind): OwnerResponse =
        if (kind == OwnerRequestKind.QUESTION) OwnerResponse.Answer("I'd rather not answer that; continue without it.") else OwnerResponse.Decline

    fun placeholder(field: OwnerField): String? = when (field.kind) {
        "date" -> "e.g. 12 March 1990"
        "email" -> "name@example.com"
        "phone" -> "+31 6 12345678"
        else -> null
    }

    fun keyboard(field: OwnerField, last: Boolean): KeyboardOptions = KeyboardOptions(
        keyboardType = when (field.kind) {
            "email" -> KeyboardType.Email
            "phone" -> KeyboardType.Phone
            "number" -> KeyboardType.Number
            else -> KeyboardType.Text
        },
        capitalization = if (field.kind in setOf("name", "address", "text")) KeyboardCapitalization.Words else KeyboardCapitalization.None,
        imeAction = if (last) ImeAction.Done else ImeAction.Next,
    )

    /** Kinds shown as a card over other apps; hand-backs use the task ribbon so the owner can still use the app. */
    fun overlayCard(request: OwnerRequest?): Boolean =
        request != null && request.kind in setOf(OwnerRequestKind.VALUES, OwnerRequestKind.QUESTION)
}
