package com.cyclone.mobile.ui.v32

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.capsule.ContinuousCapsule

/**
 * Shared navigation/composer chrome for Cyclone 4.4.4.
 *
 * This intentionally follows the optical language of Kyant0/AndroidLiquidGlass 1.0.0 instead of
 * recreating glass with translucent Material surfaces. Action buttons continue to use the upstream
 * LiquidButton recipe; larger chrome uses the upstream LiquidBottomTabs tray recipe:
 * ContinuousCapsule + vibrancy + 8dp blur + 24/24dp lens. Selected controls use the upstream
 * 10/14dp chromatic lens treatment. Content/data cards should not use this component.
 */
@Composable
internal fun CycloneLiquidTray(
    modifier: Modifier = Modifier,
    height: Dp = 56.dp,
    contentPadding: Dp = 4.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val backdrop = LocalCycloneLiquidBackdrop.current
    val dark = isSystemInDarkTheme()
    val container = if (dark) Color(0xFF121212).copy(alpha = 0.42f) else Color(0xFFFAFAFA).copy(alpha = 0.42f)

    if (backdrop == null) {
        Box(
            modifier
                .height(height)
                .fillMaxWidth()
                .padding(contentPadding),
            contentAlignment = Alignment.Center,
            content = content,
        )
        return
    }

    Box(
        modifier
            .drawBackdrop(
                backdrop = backdrop,
                shape = { ContinuousCapsule },
                effects = {
                    vibrancy()
                    blur(8f.dp.toPx())
                    lens(24f.dp.toPx(), 24f.dp.toPx())
                },
                onDrawSurface = { drawRect(container) },
            )
            .height(height)
            .fillMaxWidth()
            .padding(contentPadding),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/** Kyant-style selected lens used inside shared trays; not a second opaque button. */
@Composable
internal fun CycloneLiquidSelectionLens(
    selectedIndex: Int,
    itemCount: Int,
    totalWidth: Dp,
    modifier: Modifier = Modifier,
    height: Dp = 48.dp,
) {
    if (itemCount <= 0) return
    val backdrop = LocalCycloneLiquidBackdrop.current ?: return
    val itemWidth = totalWidth / itemCount
    val targetOffset by animateDpAsState(
        targetValue = itemWidth * selectedIndex.coerceIn(0, itemCount - 1),
        animationSpec = tween(240),
        label = "Cyclone liquid selection",
    )
    val dark = isSystemInDarkTheme()
    val surface = if (dark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.08f)

    Box(
        modifier
            .offset(x = targetOffset)
            .width(itemWidth)
            .height(height)
            .drawBackdrop(
                backdrop = backdrop,
                shape = { ContinuousCapsule },
                effects = {
                    lens(10f.dp.toPx(), 14f.dp.toPx(), chromaticAberration = true)
                },
                onDrawSurface = { drawRect(surface) },
            ),
    )
}

/** Compact neutral liquid action for chrome-level text actions. */
@Composable
internal fun CycloneLiquidTextAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    prominent: Boolean = false,
) {
    val backdrop = LocalCycloneLiquidBackdrop.current
    if (backdrop == null) return
    val dark = isSystemInDarkTheme()
    val neutral = if (dark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.08f)
    CycloneKyantLiquidButton(
        onClick = onClick,
        backdrop = backdrop,
        modifier = modifier,
        enabled = enabled,
        tint = if (prominent) androidx.compose.material3.MaterialTheme.colorScheme.primary else Color.Unspecified,
        surfaceColor = if (prominent) Color.Unspecified else neutral,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp),
    ) {
        androidx.compose.material3.Text(
            label,
            style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
            color = if (prominent) androidx.compose.material3.MaterialTheme.colorScheme.onPrimary
            else androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Transparent hit target for secondary icons living *inside* one liquid tray. */
@Composable
internal fun CycloneTrayIconAction(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
        content = content,
    )
}
