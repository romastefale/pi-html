package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.theme.AccentOrangeBorder
import com.example.ui.theme.AccentOrangeEnd
import com.example.ui.theme.AccentOrangeStart
import com.example.ui.theme.InterFontFamily

@Composable
fun FloatingMenuButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pillSurface = MaterialTheme.colorScheme.surface
    val borderColor = MaterialTheme.colorScheme.outline
    val lineColor = MaterialTheme.colorScheme.onBackground

    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .size(44.dp)
            .shadow(elevation = 8.dp, shape = CircleShape, spotColor = Color(0x18000000))
            .clip(CircleShape)
            .background(pillSurface)
            .border(width = 1.dp, color = borderColor, shape = CircleShape)
            .clickable(onClick = onClick)
            .testTag("menu_button"),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.width(16.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Box(
                modifier = Modifier
                    .width(16.dp)
                    .height(2.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(lineColor)
            )
            Box(
                modifier = Modifier
                    .width(11.dp)
                    .height(2.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(lineColor)
            )
        }
    }
}

@Composable
fun BottomComposerBar(
    urlInput: String,
    onUrlInputChange: (String) -> Unit,
    onSelectHtmlClick: () -> Unit,
    onSubmitUrl: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pillSurface = MaterialTheme.colorScheme.surface
    val borderColor = MaterialTheme.colorScheme.outline
    val textMain = MaterialTheme.colorScheme.onBackground
    val textMuted = MaterialTheme.colorScheme.outlineVariant

    Box(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .widthIn(max = 720.dp)
                .fillMaxWidth()
                .height(56.dp)
                .shadow(
                    elevation = 16.dp,
                    shape = RoundedCornerShape(28.dp),
                    spotColor = Color(0x1F000000)
                )
                .clip(RoundedCornerShape(28.dp))
                .background(pillSurface)
                .border(width = 1.dp, color = borderColor, shape = RoundedCornerShape(28.dp))
                .padding(start = 10.dp, end = 8.dp, top = 6.dp, bottom = 6.dp)
                .testTag("floating_pill_bar"),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left '+' Button to pick static HTML file
            Box(
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .size(38.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onSelectHtmlClick)
                    .testTag("plus_button"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(R.string.select_html_cd),
                    tint = textMain,
                    modifier = Modifier.size(22.dp)
                )
            }

            // Center URL input field
            BasicTextField(
                value = urlInput,
                onValueChange = onUrlInputChange,
                singleLine = true,
                textStyle = TextStyle(
                    fontFamily = InterFontFamily,
                    color = textMain,
                    fontSize = 16.sp
                ),
                cursorBrush = SolidColor(AccentOrangeStart),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go
                ),
                keyboardActions = KeyboardActions(
                    onGo = { onSubmitUrl() },
                    onDone = { onSubmitUrl() }
                ),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp)
                    .testTag("url_input"),
                decorationBox = { innerTextField ->
                    Box(
                        contentAlignment = Alignment.CenterStart,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (urlInput.isEmpty()) {
                            Text(
                                text = stringResource(R.string.url_input_placeholder),
                                color = textMuted,
                                fontSize = 16.sp,
                                fontFamily = InterFontFamily
                            )
                        }
                        innerTextField()
                    }
                }
            )

            // Right Orange Upward Arrow Button to launch URL
            val orangeGradient = Brush.linearGradient(
                colors = listOf(AccentOrangeStart, AccentOrangeEnd)
            )
            Box(
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .size(40.dp)
                    .shadow(
                        elevation = 8.dp,
                        shape = CircleShape,
                        spotColor = AccentOrangeStart
                    )
                    .clip(CircleShape)
                    .background(brush = orangeGradient)
                    .border(width = 1.dp, color = AccentOrangeBorder, shape = CircleShape)
                    .clickable(onClick = onSubmitUrl)
                    .testTag("send_url_button"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowUpward,
                    contentDescription = stringResource(R.string.load_url_cd),
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
