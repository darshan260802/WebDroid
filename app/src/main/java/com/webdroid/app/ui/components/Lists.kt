package com.webdroid.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.webdroid.app.ui.theme.AppColors
import com.webdroid.app.ui.theme.AppShapes
import com.webdroid.app.ui.theme.AppType
import com.webdroid.app.ui.theme.Motion
import com.webdroid.app.ui.theme.Spacing

/** Screen title at the top of a scrolling screen; collapses into the top bar. */
@Composable
fun LargeTitle(text: String, modifier: Modifier = Modifier, horizontalPadding: Dp = Spacing.Edge + 4.dp) {
    Text(
        text,
        style = AppType.LargeTitle,
        color = AppColors.Label,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding)
            .padding(top = 4.dp, bottom = 12.dp)
            .semantics { heading() },
    )
}

/** Collects the rows of a [ListSection] so separators go between them. */
class SectionScope internal constructor() {
    internal val rows = mutableListOf<Pair<Dp, @Composable () -> Unit>>()

    /** Adds a row; [separatorInset] lines the divider up with the row's text. */
    fun row(separatorInset: Dp = 58.dp, content: @Composable () -> Unit) {
        rows += separatorInset to content
    }
}

/** Inset grouped section: header, rounded group of rows, footer. */
@Composable
fun ListSection(
    modifier: Modifier = Modifier,
    header: String? = null,
    footer: String? = null,
    footerContent: (@Composable ColumnScope.() -> Unit)? = null,
    build: SectionScope.() -> Unit,
) {
    val scope = SectionScope().apply(build)
    Column(modifier.fillMaxWidth().padding(horizontal = Spacing.Edge)) {
        if (header != null) {
            Text(
                header,
                style = AppType.SectionHeader,
                color = AppColors.SecondaryLabel,
                modifier = Modifier
                    .padding(start = 16.dp, end = 16.dp, bottom = 7.dp)
                    .semantics { heading() },
            )
        }
        if (scope.rows.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().clip(AppShapes.Section).background(AppColors.Cell)) {
                scope.rows.forEachIndexed { i, (inset, row) ->
                    if (i > 0) {
                        Box(
                            Modifier
                                .padding(start = inset)
                                .fillMaxWidth()
                                .height(0.5.dp)
                                .background(AppColors.Separator)
                        )
                    }
                    row()
                }
            }
        }
        if (footer != null) {
            Text(
                footer,
                style = AppType.Footnote,
                color = AppColors.SecondaryLabel,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 7.dp),
            )
        }
        if (footerContent != null) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 7.dp), content = footerContent)
        }
    }
}

/** Coloured rounded-square symbol at the start of a row (as in iOS Settings). */
@Composable
fun RowIcon(icon: ImageVector, background: Color, modifier: Modifier = Modifier) {
    Box(
        modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(background),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(19.dp))
    }
}

/**
 * One row of a grouped list. Rows with [onClick] highlight on touch-down and
 * show a chevron when [chevron]; [trailing] replaces the value/chevron.
 */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconBackground: Color = AppColors.Accent,
    value: String? = null,
    titleColor: Color = AppColors.Label,
    chevron: Boolean = false,
    enabled: Boolean = true,
    role: Role = Role.Button,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val press = rememberPressState()
    val background by animateColorAsState(
        if (press.pressed && onClick != null && enabled) AppColors.CellPressed else AppColors.Cell,
        Motion.snappy(), label = "row",
    )
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .background(background)
            .then(if (onClick != null) Modifier.tappable(press, enabled, role = role, onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            RowIcon(icon, if (enabled) iconBackground else AppColors.TertiaryLabel)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = AppType.Body,
                color = if (enabled) titleColor else AppColors.TertiaryLabel,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(subtitle, style = AppType.Footnote, color = AppColors.SecondaryLabel, modifier = Modifier.padding(top = 2.dp))
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(10.dp))
            trailing()
        } else {
            if (value != null) {
                Spacer(Modifier.width(10.dp))
                Text(value, style = AppType.Body, color = AppColors.SecondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            }
            if (chevron) {
                Spacer(Modifier.width(4.dp))
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = AppColors.TertiaryLabel, modifier = Modifier.size(22.dp))
            }
        }
    }
}

/** A row with a switch. The whole row toggles it. */
@Composable
fun ToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconBackground: Color = AppColors.Accent,
    enabled: Boolean = true,
) {
    ListRow(
        title = title,
        subtitle = subtitle,
        icon = icon,
        iconBackground = iconBackground,
        enabled = enabled,
        role = Role.Switch,
        onClick = { onCheckedChange(!checked) },
        trailing = { Toggle(checked, null, enabled = enabled) },
    )
}

/** A single-choice row with a checkmark. */
@Composable
fun ChoiceRow(title: String, selected: Boolean, onClick: () -> Unit, subtitle: String? = null, modifier: Modifier = Modifier) {
    ListRow(
        title = title,
        subtitle = subtitle,
        role = Role.RadioButton,
        onClick = onClick,
        modifier = modifier.semantics { this.selected = selected },
        trailing = {
            if (selected) Icon(Icons.Rounded.Check, null, tint = AppColors.Accent, modifier = Modifier.size(22.dp))
            else Spacer(Modifier.size(22.dp))
        },
    )
}

/** Plain text action row (e.g. "Check for Updates"), accent or destructive. */
@Composable
fun ActionRow(title: String, onClick: () -> Unit, destructive: Boolean = false, enabled: Boolean = true, trailing: (@Composable () -> Unit)? = null) {
    ListRow(
        title = title,
        titleColor = if (destructive) AppColors.Destructive else AppColors.Accent,
        enabled = enabled,
        onClick = onClick,
        trailing = trailing,
    )
}

/** Space between sections. */
@Composable
fun SectionGap(height: Dp = Spacing.SectionGap) = Spacer(Modifier.height(height))
