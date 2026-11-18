package com.example.linienbegleiter

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.linienbegleiter.data.routes.RouteColorPalette

@Composable
fun RouteAppearanceControls(
    colorArgb: Int,
    lineWidth: Float,
    onColorSelected: (Int) -> Unit,
    onLineWidthChanged: (Float) -> Unit,
    modifier: Modifier = Modifier,
    allowAutomatic: Boolean = false,
    isAutomatic: Boolean = false,
    onAutomaticSelected: () -> Unit = {}
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (allowAutomatic) {
                Text(
                    text = if (isAutomatic) "✓ Авто" else "Авто",
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(onClick = onAutomaticSelected)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            RouteColorPalette.colors.forEach { color ->
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Color(color))
                        .border(
                            width = if (color == colorArgb && !isAutomatic) 3.dp else 1.dp,
                            color = if (color == colorArgb && !isAutomatic) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.outline
                            },
                            shape = CircleShape
                        )
                        .clickable { onColorSelected(color) }
                )
            }
        }
        Text(
            text = "Толщина линии: ${lineWidth.toInt()} px",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Slider(
            value = lineWidth,
            onValueChange = onLineWidthChanged,
            valueRange = 2f..14f,
            steps = 5
        )
    }
}
