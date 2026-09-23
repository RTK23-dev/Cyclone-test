package com.cyclone.mobile.ui.v32

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.applearner.AppLearnerRuntime
import com.cyclone.mobile.applearner.graphv2.AtlasRuntime
import com.cyclone.mobile.brain.graphv2.AtlasMapStatus
import com.cyclone.mobile.brain.graphv2.AtlasPlaceSummary
import java.time.Instant

/**
 * Run-1 App Maps settings surface.
 *
 * Compact phone catalog. A Glass action is shown only when a real navigation callback is supplied.
 */
@Composable
internal fun AppMapsSettingsSection(
    context: Context,
    refreshTick: Int,
    onOpenOnGlass: ((String, String) -> Unit)? = null,
) {
    val places = remember(refreshTick) {
        AppLearnerRuntime.initialize(context)
        AtlasRuntime.catalog.all()
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("App Maps", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Map coverage stored on this phone from Follow Me and mapping sessions.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (places.isEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Text(
                    "No app maps yet. Use Follow Me or a supported mapping session to teach Cyclone an app's rooms and doors.",
                    modifier = Modifier.padding(16.dp),
                )
            }
        } else {
            places.forEach { summary ->
                AppMapPlaceCard(summary, onOpenOnGlass)
            }
        }
    }
}

@Composable
private fun AppMapPlaceCard(
    summary: AtlasPlaceSummary,
    onOpenOnGlass: ((String, String) -> Unit)?,
) {
    val place = summary.place
    val source = place.packageName ?: place.origin.orEmpty()
    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(place.label, style = MaterialTheme.typography.titleMedium)
                    Text(
                        source,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    place.mapStatus.settingsLabel(),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Text(
                "${place.persona.wireValue} · ${summary.screenCount} screens · ${summary.edgeCount} edges",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Observed ${formatTime(place.lastObservedAtEpochMillis)} · verified ${formatTime(place.lastVerifiedAtEpochMillis)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (onOpenOnGlass != null) {
                OutlinedButton(
                    onClick = { onOpenOnGlass(place.id, place.persona.wireValue) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Open on Glass")
                }
            }
        }
    }
}

private fun AtlasMapStatus.settingsLabel(): String = when (this) {
    AtlasMapStatus.UNMAPPED -> "Unmapped"
    AtlasMapStatus.PARTIAL -> "Partial"
    AtlasMapStatus.MAPPED -> "Mapped"
    AtlasMapStatus.STALE -> "Stale"
}

private fun formatTime(epochMillis: Long?): String =
    epochMillis?.let { runCatching { Instant.ofEpochMilli(it).toString() }.getOrNull() } ?: "never"
