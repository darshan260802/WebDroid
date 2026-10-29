package com.webdroid.app.ui.components

import androidx.compose.ui.text.input.VisualTransformation
import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.webdroid.app.ui.glass.Glass
import com.webdroid.app.ui.glass.GlassBackdrop
import com.webdroid.app.ui.glass.GlassStyle
import com.webdroid.app.ui.theme.AppColors
import com.webdroid.app.ui.theme.AppShapes
import com.webdroid.app.ui.theme.AppType
import com.webdroid.app.ui.theme.Motion
import com.webdroid.app.ui.theme.TileColor
import kotlin.math.roundToInt

/* -------------------------------------------------------------------------- */
/* Press feedback: on touch-down, not on release                               */
/* -------------------------------------------------------------------------- */

@Stable
class PressState {
    var pressed by mutableStateOf(false)
        internal set
}

@Composable
fun rememberPressState() = remember { PressState() }

/** Tracks the finger without consuming it, so feedback starts the instant it lands. */
fun Modifier.trackPress(state: PressState, enabled: Boolean = true): Modifier =
    if (!enabled) this else pointerInput(state) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            state.pressed = true
            waitForUpOrCancellation()
            state.pressed = false
        }
    }

/** Shrinks slightly while pressed (springs back on release). */
fun Modifier.pressScale(state: PressState, pressed: Float = 0.96f): Modifier = androidx.compose.ui.composed {
    val value by animateFloatAsState(if (state.pressed) pressed else 1f, Motion.snappy(), label = "press")
    // Read in the layer block: the press animates without recomposing.
    graphicsLayer { scaleX = value; scaleY = value }
}

/** A tappable surface: instant press feedback, haptic tick, button semantics. */
fun Modifier.tappable(
    state: PressState,
    enabled: Boolean = true,
    role: Role = Role.Button,
    haptic: Boolean = true,
    onClick: () -> Unit,
): Modifier = androidx.compose.ui.composed {
    val haptics = rememberHaptics()
    this
        .trackPress(state, enabled)
        .clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            enabled = enabled,
            role = role,
        ) {
            if (haptic) haptics.tap()
            onClick()
        }
}

/* -------------------------------------------------------------------------- */
/* Buttons                                                                     */
/* -------------------------------------------------------------------------- */

enum class ButtonStyle { Prominent, Tinted, Plain, Destructive }

/**
 * Capsule button. One prominent (filled) button per view at most; the rest
 * tinted or plain, as the HIG asks.
 */
@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: ButtonStyle = ButtonStyle.Prominent,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    height: Dp = 50.dp,
) {
    val press = rememberPressState()
    val (bg, fg) = when (style) {
        ButtonStyle.Prominent -> AppColors.AccentFill to AppColors.OnAccent
        ButtonStyle.Tinted -> AppColors.Accent.copy(alpha = if (AppColors.isDark) 0.22f else 0.12f) to AppColors.Accent
        ButtonStyle.Plain -> Color.Transparent to AppColors.Accent
        ButtonStyle.Destructive -> AppColors.Destructive.copy(alpha = if (AppColors.isDark) 0.22f else 0.1f) to AppColors.Destructive
    }
    val background by animateColorAsState(if (enabled) bg else AppColors.Fill, Motion.snappy(), label = "btn-bg")
    val content = if (enabled) fg else AppColors.TertiaryLabel
    val dim by animateFloatAsState(if (press.pressed) 0.75f else 1f, Motion.snappy(), label = "btn-dim")
    Row(
        modifier
            .pressScale(press, 0.97f)
            .heightIn(min = height)
            .clip(AppShapes.Capsule)
            .background(background)
            .graphicsLayer { alpha = if (style == ButtonStyle.Plain) dim else 1f }
            .tappable(press, enabled, onClick = onClick)
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = content, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = AppType.Button, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Round Liquid Glass button for the floating layer. Pressing it makes the
 * glass swell and brighten, like a drop under a fingertip.
 */
@Composable
fun GlassIconButton(
    backdrop: GlassBackdrop?,
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    tint: Color = AppColors.Label,
    prominent: Boolean = false,
    enabled: Boolean = true,
    style: GlassStyle = GlassStyle.Regular,
) {
    val press = rememberPressState()
    val grow by animateFloatAsState(if (press.pressed) 1.12f else 1f, Motion.spring(0.3f, if (press.pressed) 1f else 0.7f), label = "glass-grow")
    val glow by animateFloatAsState(if (press.pressed) 1f else 0f, Motion.snappy(), label = "glass-glow")
    Glass(
        backdrop = backdrop,
        modifier = modifier
            .size(size)
            .tappable(press, enabled, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        cornerRadius = size / 2,
        style = style,
        tint = if (prominent) AppColors.AccentFill else null,
        scale = grow,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind { drawCircle(Color.White.copy(alpha = 0.18f * glow)) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon, null,
                tint = when {
                    !enabled -> AppColors.TertiaryLabel
                    prominent -> AppColors.OnAccent
                    else -> tint
                },
                modifier = Modifier.size(size * 0.5f),
            )
        }
    }
}

/* -------------------------------------------------------------------------- */
/* Toggle (switch)                                                             */
/* -------------------------------------------------------------------------- */

/** iOS-style switch: the thumb stretches while pressed and springs across. */
@Composable
fun Toggle(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val press = rememberPressState()
    val haptics = rememberHaptics()
    val track by animateColorAsState(
        when {
            checked -> AppColors.ToggleOn
            else -> AppColors.Fill
        },
        Motion.smooth(), label = "toggle-track",
    )
    val travel by animateFloatAsState(if (checked) 1f else 0f, Motion.spring(0.3f, 0.85f), label = "toggle-travel")
    val stretch by animateDpAsState(if (press.pressed) 6.dp else 0.dp, Motion.snappy(), label = "toggle-stretch")
    val density = LocalDensity.current
    Box(
        modifier
            .size(width = 51.dp, height = 31.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
            .clip(AppShapes.Capsule)
            .background(track)
            .then(
                if (onCheckedChange != null) {
                    Modifier
                        .trackPress(press, enabled)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            enabled = enabled,
                            role = Role.Switch,
                        ) {
                            haptics.tick()
                            onCheckedChange(!checked)
                        }
                } else Modifier
            )
            .semantics { stateDescription = if (checked) "On" else "Off" }
            .padding(2.dp),
    ) {
        val thumbWidth = 27.dp + stretch
        Box(
            Modifier
                .offset {
                    val maxTravel = with(density) { (47.dp - thumbWidth).toPx() }
                    IntOffset((maxTravel * travel).roundToInt(), 0)
                }
                .size(width = thumbWidth, height = 27.dp)
                .drawBehind {
                    // Soft shadow under the thumb.
                    drawRoundRect(Color.Black.copy(alpha = 0.10f), topLeft = androidx.compose.ui.geometry.Offset(0f, 1.5.dp.toPx()), cornerRadius = CornerRadius(size.height / 2))
                }
                .clip(AppShapes.Capsule)
                .background(Color.White)
        )
    }
}

/* -------------------------------------------------------------------------- */
/* Segmented control                                                           */
/* -------------------------------------------------------------------------- */

@Composable
fun <T> SegmentedControl(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    val index = options.indexOfFirst { it.first == selected }.coerceAtLeast(0)
    val position by animateFloatAsState(index.toFloat(), Motion.spring(0.32f, 0.85f), label = "segment")
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(AppColors.Fill)
            .padding(3.dp),
    ) {
        val segmentWidth = maxWidth / options.size
        Box(
            Modifier
                .offset(x = segmentWidth * position)
                .width(segmentWidth)
                .fillMaxHeight()
                .drawBehind {
                    drawRoundRect(Color.Black.copy(alpha = 0.06f), topLeft = androidx.compose.ui.geometry.Offset(0f, 1.dp.toPx()), cornerRadius = CornerRadius(9.dp.toPx()))
                }
                .clip(RoundedCornerShape(9.dp))
                .background(if (AppColors.isDark) Color(0xFF636366) else Color.White)
        )
        Row(Modifier.fillMaxSize()) {
            options.forEach { (value, label) ->
                val isSelected = value == selected
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Tab,
                        ) {
                            if (!isSelected) haptics.tick()
                            onSelect(value)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = AppType.Subhead.copy(fontWeight = if (isSelected) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Medium),
                        color = AppColors.Label,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/* -------------------------------------------------------------------------- */
/* Text field                                                                  */
/* -------------------------------------------------------------------------- */

@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    leading: ImageVector? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = true,
    textStyle: TextStyle = AppType.Body,
    background: Color = AppColors.Fill,
    clearable: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        visualTransformation = visualTransformation,
        singleLine = singleLine,
        textStyle = textStyle.copy(color = AppColors.Label),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(AppColors.Accent),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        modifier = modifier,
        decorationBox = { inner ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(background)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
            ) {
                if (leading != null) {
                    Icon(leading, null, tint = AppColors.SecondaryLabel, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                }
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) {
                        Text(placeholder, style = textStyle, color = AppColors.SecondaryLabel, maxLines = if (singleLine) 1 else 8, overflow = TextOverflow.Ellipsis)
                    }
                    inner()
                }
                if (clearable && singleLine && value.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        Icons.Rounded.Cancel, "Clear",
                        tint = AppColors.TertiaryLabel,
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .clickable { onValueChange("") },
                    )
                }
            }
        },
    )
}

/* -------------------------------------------------------------------------- */
/* App icon                                                                    */
/* -------------------------------------------------------------------------- */

/** Continuous-corner app icon: the site's icon on a light plate, or a letter tile. */
@Composable
fun AppIcon(icon: Bitmap?, name: String, tile: TileColor, size: Dp, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(size * 0.225f)
    val image = remember(icon) { icon?.asImageBitmap() }
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(
                if (image != null) Brush.verticalGradient(listOf(Color.White, Color(0xFFF2F2F7)))
                else Brush.verticalGradient(listOf(tile.top, tile.bottom))
            )
            .border(0.5.dp, Color.Black.copy(alpha = if (AppColors.isDark) 0.0f else 0.08f), shape)
            .semantics { contentDescription = name },
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(
                image, null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(size * 0.66f).clip(RoundedCornerShape(size * 0.12f)),
            )
        } else {
            Text(
                name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "W",
                color = tile.glyph,
                fontSize = (size.value * 0.46f).sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                style = AppType.Title1.copy(letterSpacing = 0.sp),
            )
        }
    }
}

/** Small capsule count or status. */
@Composable
fun Badge(text: String, modifier: Modifier = Modifier, color: Color = AppColors.Destructive) {
    Text(
        text,
        style = AppType.Caption2,
        color = Color.White,
        modifier = modifier
            .clip(AppShapes.Capsule)
            .background(color)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
