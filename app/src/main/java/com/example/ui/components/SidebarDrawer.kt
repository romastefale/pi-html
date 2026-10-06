package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.local.SavedWebAppEntity
import com.example.ui.theme.DarkSidebarBrandBg
import com.example.ui.theme.LightMarkBg
import com.example.ui.theme.ScrimOverlay

private val BrandBadgeShape = RoundedCornerShape(6.dp)
private val ActionCardShape = RoundedCornerShape(12.dp)
private val HistoryItemShape = RoundedCornerShape(10.dp)
private val DeleteBtnShape = RoundedCornerShape(8.dp)

private val SunRayCos = FloatArray(8) { i ->
    kotlin.math.cos(Math.toRadians((i * 45).toDouble())).toFloat()
}
private val SunRaySin = FloatArray(8) { i ->
    kotlin.math.sin(Math.toRadians((i * 45).toDouble())).toFloat()
}

@Composable
fun SidebarDrawer(
    isOpen: Boolean,
    savedApps: List<SavedWebAppEntity>,
    activeAppId: Long?,
    isDarkTheme: Boolean,
    onClose: () -> Unit,
    onNewAppClick: () -> Unit,
    onSelectApp: (Long) -> Unit,
    onDeleteApp: (Long) -> Unit,
    onToggleTheme: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        // Backdrop Scrim Overlay
        AnimatedVisibility(
            visible = isOpen,
            enter = fadeIn(animationSpec = tween(180)),
            exit = fadeOut(animationSpec = tween(180))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(ScrimOverlay)
                    .testTag("sidebar_overlay")
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClose
                    )
            )
        }

        // Slide-out Sidebar Panel with hardware layer caching during slide animation
        AnimatedVisibility(
            visible = isOpen,
            enter = slideInHorizontally(
                initialOffsetX = { -it },
                animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing)
            ),
            exit = slideOutHorizontally(
                targetOffsetX = { -it },
                animationSpec = tween(durationMillis = 190, easing = FastOutSlowInEasing)
            )
        ) {
            val sidebarBg = MaterialTheme.colorScheme.surfaceContainer
            val itemSurface = MaterialTheme.colorScheme.surfaceVariant
            val textMain = MaterialTheme.colorScheme.onBackground
            val textSecondary = MaterialTheme.colorScheme.onSurfaceVariant
            val textMuted = MaterialTheme.colorScheme.outlineVariant
            val borderColor = MaterialTheme.colorScheme.outline

            Column(
                modifier = Modifier
                    .width(288.dp)
                    .fillMaxHeight()
                    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                    .shadow(elevation = 16.dp)
                    .background(sidebarBg)
                    .border(width = 1.dp, color = borderColor)
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .pointerInput(Unit) {
                        var totalDragX = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { totalDragX = 0f },
                            onHorizontalDrag = { change, dragAmount ->
                                totalDragX += dragAmount
                                if (totalDragX < -32.dp.toPx()) {
                                    change.consume()
                                    onClose()
                                }
                            }
                        )
                    }
                    .padding(horizontal = 16.dp, vertical = 20.dp)
                    .testTag("sidebar_panel")
            ) {
                // Sidebar Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(BrandBadgeShape)
                                .background(if (isDarkTheme) DarkSidebarBrandBg else LightMarkBg),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.brand_short),
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = stringResource(R.string.brand_name),
                            color = textMain,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Box(
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(itemSurface)
                            .clickable(onClick = onClose)
                            .testTag("close_sidebar_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.close_menu_cd),
                            tint = textMain,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // New Application / Home Entry Item
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(ActionCardShape)
                        .background(itemSurface)
                        .clickable(onClick = onNewAppClick)
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                        .testTag("new_app_button"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        tint = textMain,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = stringResource(R.string.brand_name),
                        color = textMain,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Section Title
                Text(
                    text = stringResource(R.string.saved_apps_header).uppercase(),
                    color = textMuted,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
                )

                // Saved Applications List
                if (savedApps.isEmpty()) {
                    Text(
                        text = stringResource(R.string.empty_history_message),
                        color = textMuted,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        modifier = Modifier
                            .padding(horizontal = 8.dp, vertical = 16.dp)
                            .testTag("empty_history_text")
                    )
                    Spacer(modifier = Modifier.weight(1f))
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .testTag("saved_apps_list"),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(
                            items = savedApps,
                            key = { it.id },
                            contentType = { "saved_web_app_row" }
                        ) { app ->
                            val isActive = app.id == activeAppId
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(HistoryItemShape)
                                    .background(if (isActive) itemSurface else Color.Transparent)
                                    .clickable { onSelectApp(app.id) }
                                    .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)
                                    .testTag("history_item_${app.id}"),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = app.title,
                                    color = if (isActive) textMain else textSecondary,
                                    fontSize = 14.sp,
                                    fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )

                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(DeleteBtnShape)
                                        .clickable { onDeleteApp(app.id) }
                                        .testTag("delete_app_${app.id}"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = stringResource(R.string.delete_app_cd),
                                        tint = textMuted,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // Sidebar Footer - Theme Toggle
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(ActionCardShape)
                        .background(itemSurface)
                        .clickable(onClick = onToggleTheme)
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                        .testTag("theme_toggle_button"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Canvas(modifier = Modifier.size(18.dp)) {
                        val strokePx = 1.8.dp.toPx()
                        val center = Offset(size.width / 2f, size.height / 2f)
                        if (isDarkTheme) {
                            drawCircle(
                                color = textMain,
                                radius = size.minDimension * 0.22f,
                                center = center,
                                style = Stroke(width = strokePx)
                            )
                            val r1 = size.minDimension * 0.34f
                            val r2 = size.minDimension * 0.46f
                            for (i in 0 until 8) {
                                val cos = SunRayCos[i]
                                val sin = SunRaySin[i]
                                drawLine(
                                    color = textMain,
                                    start = Offset(center.x + cos * r1, center.y + sin * r1),
                                    end = Offset(center.x + cos * r2, center.y + sin * r2),
                                    strokeWidth = strokePx,
                                    cap = StrokeCap.Round
                                )
                            }
                        } else {
                            drawCircle(
                                color = textMain,
                                radius = size.minDimension * 0.34f,
                                center = center,
                                style = Stroke(width = strokePx)
                            )
                        }
                    }
                    Text(
                        text = if (isDarkTheme) {
                            stringResource(R.string.theme_light_mode)
                        } else {
                            stringResource(R.string.theme_dark_mode)
                        },
                        color = textMain,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}
