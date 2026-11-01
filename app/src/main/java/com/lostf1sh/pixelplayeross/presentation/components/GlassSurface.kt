package com.lostf1sh.pixelplayeross.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding

/**
 * Lightweight glass-style container for the offline-first visual refresh.
 *
 * The layered translucent fills and fine highlight border keep content legible without
 * requiring an expensive real-time backdrop blur on every card.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(28.dp),
    content: @Composable BoxScope.() -> Unit
) {
    val fill = Brush.linearGradient(
        colors = listOf(
            androidx.compose.material3.MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
            androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.54f),
            androidx.compose.material3.MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
        )
    )
    val highlight = Brush.linearGradient(
        colors = listOf(
            androidx.compose.ui.graphics.Color.White.copy(alpha = 0.34f),
            androidx.compose.ui.graphics.Color.White.copy(alpha = 0.08f),
            androidx.compose.material3.MaterialTheme.colorScheme.outline.copy(alpha = 0.20f)
        )
    )

    Box(
        modifier = modifier
            .clip(shape)
            .background(fill, shape)
            .border(BorderStroke(1.dp, highlight), shape),
        content = content
    )
}
