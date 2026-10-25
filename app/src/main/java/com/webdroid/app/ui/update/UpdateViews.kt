package com.webdroid.app.ui.update

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.webdroid.app.BuildConfig
import com.webdroid.app.data.GlobalSettings
import com.webdroid.app.ui.components.ClayButton
import com.webdroid.app.ui.components.ClayButtonStyle
import com.webdroid.app.ui.components.ClaySwitchRow
import com.webdroid.app.ui.components.HairlineDivider
import com.webdroid.app.ui.components.rememberHaptics
import com.webdroid.app.ui.theme.Clay
import com.webdroid.app.ui.theme.ClayShapes
import com.webdroid.app.ui.theme.ClayType
import com.webdroid.app.update.UpdateInfo
import com.webdroid.app.update.UpdateState

class UpdateActions(
    val install: () -> Unit,
    val download: () -> Unit,
    val checkNow: () -> Unit,
    val later: () -> Unit,
)

/** The update's info, for states that carry one. */
val UpdateState.info: UpdateInfo?
    get() = when (this) {
        is UpdateState.Available -> info
        is UpdateState.Downloading -> info
        is UpdateState.Ready -> info
        is UpdateState.Installing -> info
        is UpdateState.Failed -> info
        else -> null
    }

private fun sizeLabel(bytes: Long): String =
    if (bytes <= 0) "" else String.format(java.util.Locale.US, "%.1f MB", bytes / 1_048_576f)

/** Home screen card: shown while a newer WebDroid is available, downloading or ready. */
@Composable
fun UpdateCard(state: UpdateState, actions: UpdateActions, modifier: Modifier = Modifier) {
    val info = state.info ?: return
    var notesOpen by remember { mutableStateOf(false) }
    val ink = Clay.Black
    val inkSoft = Clay.Black.copy(alpha = 0.66f)
    Column(
        modifier
            .fillMaxWidth()
            .clip(ClayShapes.Xl)
            .background(Clay.Ochre)
            .animateContentSize()
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(Clay.Cream), contentAlignment = Alignment.Center) {
                when (state) {
                    is UpdateState.Installing -> CircularProgressIndicator(color = ink, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                    is UpdateState.Failed -> Icon(Icons.Rounded.ErrorOutline, null, tint = ink, modifier = Modifier.size(22.dp))
                    else -> Icon(Icons.Rounded.SystemUpdate, null, tint = ink, modifier = Modifier.size(22.dp))
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                val title = when (state) {
                    is UpdateState.Ready -> "Update ready to install"
                    is UpdateState.Downloading -> "Downloading update"
                    is UpdateState.Installing -> "Installing update"
                    is UpdateState.Failed -> "Update didn't finish"
                    else -> "Update available"
                }
                Text(title, style = ClayType.TitleMd, color = ink)
                val subtitle = when (state) {
                    is UpdateState.Downloading -> "WebDroid ${info.versionName} · ${(state.progress * 100).toInt()}%"
                    is UpdateState.Installing -> "Confirm in Android's installer"
                    is UpdateState.Failed -> state.message
                    else -> listOf("WebDroid ${info.versionName}", sizeLabel(info.size)).filter { it.isNotEmpty() }.joinToString(" · ")
                }
                Text(subtitle, style = ClayType.Caption, color = inkSoft, maxLines = 2)
            }
        }

        if (state is UpdateState.Downloading) {
            Spacer(Modifier.height(14.dp))
            ProgressBar(state.progress, ink)
        }

        if (info.notes.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            val haptics = rememberHaptics()
            val arrow by animateFloatAsState(if (notesOpen) 180f else 0f, label = "notes-arrow")
            Row(
                Modifier
                    .clip(ClayShapes.Pill)
                    .clickable { haptics.tick(); notesOpen = !notesOpen }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("What's new", style = ClayType.NavLink, color = ink)
                Icon(Icons.Rounded.KeyboardArrowDown, null, tint = ink, modifier = Modifier.size(18.dp).rotate(arrow))
            }
            AnimatedVisibility(notesOpen, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                ReleaseNotes(info.notes, ink, inkSoft, Modifier.padding(top = 8.dp))
            }
        }

        val primary: Pair<String, () -> Unit>? = when (state) {
            is UpdateState.Ready -> "Install" to actions.install
            is UpdateState.Available -> "Download" to actions.download
            is UpdateState.Failed -> "Try again" to (if (state.info != null) actions.download else actions.checkNow)
            else -> null
        }
        if (primary != null) {
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ClayButton(primary.first, onClick = primary.second, height = 44.dp, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(10.dp))
                Text(
                    "Later",
                    style = ClayType.Button,
                    color = ink,
                    modifier = Modifier
                        .clip(ClayShapes.Md)
                        .clickable(onClick = actions.later)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun ProgressBar(progress: Float, color: Color) {
    val animated by animateFloatAsState(progress, label = "update-progress")
    Box(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(ClayShapes.Pill)
            .background(color.copy(alpha = 0.14f)),
    ) {
        Box(Modifier.fillMaxWidth(animated.coerceIn(0.02f, 1f)).fillMaxHeight().clip(ClayShapes.Pill).background(color))
    }
}

/** Renders the release notes' simple Markdown (headings and bullets). */
@Composable
fun ReleaseNotes(notes: String, color: Color, softColor: Color, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        notes.lineSequence().map { it.trimEnd() }.forEach { raw ->
            val line = raw.replace("**", "").replace("`", "")
            when {
                line.isBlank() -> Unit
                line.startsWith("# ") -> Unit // Release title; the card already says it.
                line.startsWith("#") ->
                    Text(line.trimStart('#', ' '), style = ClayType.TitleSm, color = color, modifier = Modifier.padding(top = 6.dp))
                line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") -> Row {
                    Text("•", style = ClayType.BodySm, color = softColor, modifier = Modifier.width(14.dp))
                    Text(line.trimStart().drop(2), style = ClayType.BodySm, color = softColor)
                }
                else -> Text(line, style = ClayType.BodySm, color = softColor)
            }
        }
    }
}

/** Settings section: version, status, manual check and the auto-update switches. */
@Composable
fun UpdateSettingsSection(
    state: UpdateState,
    lastChecked: Long,
    settings: GlobalSettings,
    onUpdate: ((GlobalSettings) -> GlobalSettings) -> Unit,
    actions: UpdateActions,
) {
    var notesOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clip(ClayShapes.Lg).background(Clay.SurfaceSoft).padding(16.dp).animateContentSize()) {
        Text("WebDroid ${BuildConfig.VERSION_NAME}", style = ClayType.TitleSm, color = Clay.Ink)
        val status = when (state) {
            UpdateState.Checking -> "Checking for updates…"
            is UpdateState.UpToDate -> "You're up to date · checked ${relativeTime(state.checkedAt)}"
            is UpdateState.Available -> "Version ${state.info.versionName} is available"
            is UpdateState.Downloading -> "Downloading ${state.info.versionName} · ${(state.progress * 100).toInt()}%"
            is UpdateState.Ready -> "Version ${state.info.versionName} is ready to install"
            is UpdateState.Installing -> "Installing ${state.info.versionName}…"
            is UpdateState.Failed -> state.message
            UpdateState.Idle -> if (lastChecked > 0) "Last checked ${relativeTime(lastChecked)}" else "New releases arrive from GitHub automatically"
        }
        Text(status, style = ClayType.BodySm, color = if (state is UpdateState.Failed) Clay.Error else Clay.Muted, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))

        val info = state.info
        if (info != null && info.notes.isNotBlank()) {
            Text(
                if (notesOpen) "Hide what's new" else "What's new in ${info.versionName}",
                style = ClayType.NavLink,
                color = Clay.Ink,
                modifier = Modifier.clip(ClayShapes.Pill).clickable { notesOpen = !notesOpen }.padding(vertical = 4.dp),
            )
            AnimatedVisibility(notesOpen) {
                ReleaseNotes(info.notes, Clay.Ink, Clay.Body, Modifier.padding(top = 6.dp, bottom = 10.dp))
            }
            Spacer(Modifier.height(8.dp))
        }

        when (state) {
            is UpdateState.Ready -> ClayButton("Install ${state.info.versionName}", onClick = actions.install, icon = Icons.Rounded.SystemUpdate, height = 44.dp)
            is UpdateState.Available -> ClayButton(
                "Download ${listOf(state.info.versionName, sizeLabel(state.info.size)).filter { it.isNotEmpty() }.joinToString(" · ")}",
                onClick = actions.download, height = 44.dp,
            )
            is UpdateState.Failed -> ClayButton("Try again", onClick = if (state.info != null) actions.download else actions.checkNow, style = ClayButtonStyle.Secondary, height = 44.dp)
            else -> ClayButton(
                "Check for updates",
                onClick = actions.checkNow,
                style = ClayButtonStyle.Secondary,
                enabled = state !is UpdateState.Checking && state !is UpdateState.Downloading && state !is UpdateState.Installing,
                height = 44.dp,
            )
        }
        Spacer(Modifier.height(8.dp))
        HairlineDivider()
        ClaySwitchRow(
            "Automatic updates", "Check daily and download new versions in the background",
            settings.autoUpdate, { v -> onUpdate { it.copy(autoUpdate = v) } },
        )
        ClaySwitchRow(
            "Download on mobile data", "Otherwise updates wait for Wi-Fi",
            settings.updateOnMobileData, { v -> onUpdate { it.copy(updateOnMobileData = v) } },
            enabled = settings.autoUpdate,
        )
    }
}

fun relativeTime(time: Long): String {
    val minutes = (System.currentTimeMillis() - time) / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 60 * 24 -> "${minutes / 60} h ago"
        else -> "${minutes / (60 * 24)} days ago"
    }
}
