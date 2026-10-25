package com.webdroid.app.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.webdroid.app.ui.theme.CardColor
import com.webdroid.app.ui.theme.Clay
import com.webdroid.app.ui.theme.ClayMotion
import com.webdroid.app.ui.theme.ClayShapes
import com.webdroid.app.ui.theme.ClayType

/** Springy press feedback shared by every tappable surface. */
fun Modifier.pressScale(interactionSource: MutableInteractionSource, pressed: Float = 0.96f): Modifier = composed {
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) pressed else 1f, ClayMotion.bouncy(), label = "press")
    graphicsLayer { scaleX = scale; scaleY = scale }
}

enum class ClayButtonStyle { Primary, Secondary, OnColor, Danger, Ghost }

@Composable
fun ClayButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    style: ClayButtonStyle = ClayButtonStyle.Primary,
    enabled: Boolean = true,
    height: Dp = 52.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val haptics = rememberHaptics()
    val (bg, fg, border) = when (style) {
        ClayButtonStyle.Primary -> Triple(if (pressed) Clay.PrimaryActive else Clay.Primary, Clay.OnPrimary, null)
        ClayButtonStyle.Secondary -> Triple(Clay.Canvas, Clay.Ink, Clay.Hairline)
        ClayButtonStyle.OnColor -> Triple(Clay.Canvas, Clay.Ink, null)
        ClayButtonStyle.Danger -> Triple(Clay.Canvas, Clay.Error, Clay.Hairline)
        ClayButtonStyle.Ghost -> Triple(Color.Transparent, Clay.Ink, null)
    }
    val background by animateColorAsState(if (enabled) bg else Clay.PrimaryDisabled, label = "btn-bg")
    val content = if (enabled) fg else Clay.Muted
    Row(
        modifier = modifier
            .pressScale(interaction, 0.97f)
            .heightIn(min = height)
            .clip(ClayShapes.Md)
            .background(background)
            .then(if (border != null) Modifier.border(1.dp, border, ClayShapes.Md) else Modifier)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
            ) {
                haptics.tap()
                onClick()
            }
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = ClayType.Button, color = content, maxLines = 1)
    }
}

@Composable
fun ClayIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    background: Color = Clay.SurfaceCard,
    tint: Color = Clay.Ink,
    size: Dp = 44.dp,
    iconSize: Dp = 22.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    val haptics = rememberHaptics()
    Box(
        modifier = modifier
            .pressScale(interaction, 0.9f)
            .size(size)
            .clip(CircleShape)
            .background(background)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button) {
                haptics.tap()
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(iconSize))
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color = Clay.Muted) {
    Text(text.uppercase(), style = ClayType.CaptionUpper, color = color, modifier = modifier)
}

@Composable
fun BadgePill(text: String, modifier: Modifier = Modifier, background: Color = Clay.SurfaceCard, color: Color = Clay.Ink) {
    Text(
        text,
        style = ClayType.Caption,
        color = color,
        modifier = modifier
            .clip(ClayShapes.Pill)
            .background(background)
            .padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

@Composable
fun ClayTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    leading: ImageVector? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = true,
    textStyle: TextStyle = ClayType.BodyMd,
) {
    var focused by remember { mutableStateOf(false) }
    val borderWidth by animateDpAsState(if (focused) 1.5.dp else 1.dp, label = "field-border")
    val borderColor by animateColorAsState(if (focused) Clay.Ink else Clay.Hairline, label = "field-border-color")
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        textStyle = textStyle.copy(color = Clay.Ink),
        cursorBrush = SolidColor(Clay.Ink),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        modifier = modifier.onFocusChanged { focused = it.isFocused },
        decorationBox = { inner ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 54.dp)
                    .clip(ClayShapes.Md)
                    .background(Clay.Canvas)
                    .border(BorderStroke(borderWidth, borderColor), ClayShapes.Md)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (leading != null) {
                    Icon(leading, null, tint = Clay.Muted, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                }
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) {
                        Text(placeholder, style = textStyle, color = Clay.MutedSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    inner()
                }
                if (trailing != null) {
                    Spacer(Modifier.width(8.dp))
                    trailing()
                }
            }
        },
    )
}

@Composable
fun ClaySwitch(checked: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val track by animateColorAsState(
        when {
            !enabled -> Clay.Hairline
            checked -> Clay.Ink
            else -> Clay.SurfaceStrong
        },
        label = "switch-track",
    )
    val offset by animateDpAsState(if (checked) 20.dp else 0.dp, ClayMotion.bouncy(), label = "switch-thumb")
    Box(
        modifier = modifier
            .size(width = 50.dp, height = 30.dp)
            .clip(ClayShapes.Pill)
            .background(track)
            .padding(3.dp),
    ) {
        Box(
            Modifier
                .offset(x = offset)
                .size(24.dp)
                .clip(CircleShape)
                .background(Clay.Canvas)
        )
    }
}

/** A tappable settings row with a trailing switch. */
@Composable
fun ClaySwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val haptics = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(ClayShapes.Lg)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Switch) {
                haptics.tick()
                onCheckedChange(!checked)
            }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(
                Modifier.size(40.dp).clip(ClayShapes.Md).background(Clay.SurfaceCard),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, tint = if (enabled) Clay.Ink else Clay.MutedSoft, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = ClayType.TitleSm, color = if (enabled) Clay.Ink else Clay.MutedSoft)
            if (subtitle != null) {
                Text(subtitle, style = ClayType.BodySm, color = Clay.Muted)
            }
        }
        Spacer(Modifier.width(12.dp))
        ClaySwitch(checked = checked, enabled = enabled)
    }
}

/** Pill segmented control (category-tab style). */
@Composable
fun <T> ClaySegmented(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    Row(
        modifier = modifier
            .clip(ClayShapes.Pill)
            .background(Clay.SurfaceCard)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { (value, label) ->
            val isSelected = value == selected
            val bg by animateColorAsState(if (isSelected) Clay.Ink else Color.Transparent, label = "seg-bg")
            val fg by animateColorAsState(if (isSelected) Clay.OnPrimary else Clay.Muted, label = "seg-fg")
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(ClayShapes.Pill)
                    .background(bg)
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                        if (!isSelected) haptics.tick()
                        onSelect(value)
                    }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = ClayType.NavLink, color = fg, maxLines = 1)
            }
        }
    }
}

/** Site icon on a cream plate, or the site's initial when there is none. */
@Composable
fun AppGlyph(
    bitmap: Bitmap?,
    name: String,
    cardColor: CardColor,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(size * 0.3f))
            .background(if (bitmap != null) Clay.Canvas else cardColor.content.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = remember(bitmap) { bitmap.asImageBitmap() },
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.padding(size * 0.14f).fillMaxWidth().fillMaxHeight(),
            )
        } else {
            Text(
                name.trim().firstOrNull()?.uppercase() ?: "W",
                style = ClayType.DisplaySm.copy(fontSize = ClayType.DisplaySm.fontSize * (size.value / 52f)),
                color = cardColor.content,
            )
        }
    }
}

@Composable
fun ClayCard(
    color: Color,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(24.dp),
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(ClayShapes.Xl)
            .background(color)
            .padding(contentPadding),
    ) { content() }
}

@Composable
fun HairlineDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Clay.HairlineSoft))
}
