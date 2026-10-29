package com.webdroid.app.ui.update

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.rounded.ArrowCircleDown
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.webdroid.app.BuildConfig
import com.webdroid.app.data.GlobalSettings
import com.webdroid.app.ui.components.ActionRow
import com.webdroid.app.ui.components.AppButton
import com.webdroid.app.ui.components.ButtonStyle
import com.webdroid.app.ui.components.ListRow
import com.webdroid.app.ui.components.ListSection
import com.webdroid.app.ui.components.ToggleRow
import com.webdroid.app.ui.theme.AppColors
import com.webdroid.app.ui.theme.AppShapes
import com.webdroid.app.ui.theme.AppType
import com.webdroid.app.ui.theme.Motion
import com.webdroid.app.update.UpdateInfo
import com.webdroid.app.update.UpdateState

class UpdateActions(
    val install: () -> Unit,
    val download: () -> Unit,
    val checkNow: () -> Unit,
    val later: () -> Unit,
    val showNotes: () -> Unit = {},
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
    if (bytes <= 0) "" else String.format(java.util.Locale.US, "%.0f MB", bytes / 1_048_576f)

private fun subtitleFor(state: UpdateState, info: UpdateInfo): String = when (state) {
    is UpdateState.Downloading -> "Downloading · ${(state.progress * 100).toInt()}%"
    is UpdateState.Installing -> "Confirm in the installer"
    is UpdateState.Failed -> state.message
    else -> listOf("Version ${info.versionName}", sizeLabel(info.size)).filter { it.isNotEmpty() }.joinToString(" · ")
}

/** Card at the top of Apps while a newer WebDroid is available, downloading or ready. */
@Composable
fun UpdateBanner(state: UpdateState, actions: UpdateActions, modifier: Modifier = Modifier) {
    val info = state.info ?: return
    Column(
        modifier
            .fillMaxWidth()
            .clip(AppShapes.Section)
            .background(AppColors.Cell)
            .animateContentSize(Motion.smooth())
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(AppColors.Accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                when (state) {
                    is UpdateState.Installing -> CircularProgressIndicator(color = AppColors.Accent, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                    is UpdateState.Failed -> Icon(Icons.Rounded.ErrorOutline, null, tint = AppColors.Destructive, modifier = Modifier.size(24.dp))
                    else -> Icon(Icons.Rounded.ArrowCircleDown, null, tint = AppColors.Accent, modifier = Modifier.size(24.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    when (state) {
                        is UpdateState.Ready -> "Update Ready"
                        is UpdateState.Downloading -> "Downloading Update"
                        is UpdateState.Installing -> "Installing Update"
                        is UpdateState.Failed -> "Update Didn't Finish"
                        else -> "Update Available"
                    },
                    style = AppType.Headline,
                    color = AppColors.Label,
                )
                Text(subtitleFor(state, info), style = AppType.Footnote, color = AppColors.SecondaryLabel, maxLines = 2)
            }
        }
        if (state is UpdateState.Downloading) {
            Spacer(Modifier.height(12.dp))
            ProgressBar(state.progress)
        }
        val primary: Pair<String, () -> Unit>? = when (state) {
            is UpdateState.Ready -> "Install" to actions.install
            is UpdateState.Available -> "Download" to actions.download
            is UpdateState.Failed -> "Try Again" to (if (state.info != null) actions.download else actions.checkNow)
            else -> null
        }
        if (primary != null) {
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                AppButton(primary.first, primary.second, height = 40.dp)
                if (info.notes.isNotBlank()) AppButton("What’s New", actions.showNotes, style = ButtonStyle.Plain, height = 40.dp)
                Spacer(Modifier.weight(1f))
                AppButton("Later", actions.later, style = ButtonStyle.Plain, height = 40.dp)
            }
        }
    }
}

@Composable
fun ProgressBar(progress: Float, color: Color = AppColors.Accent, modifier: Modifier = Modifier) {
    val animated by animateFloatAsState(progress.coerceIn(0f, 1f), Motion.smooth(), label = "progress")
    Box(modifier.fillMaxWidth().height(4.dp).clip(AppShapes.Capsule).background(AppColors.Fill)) {
        Box(Modifier.fillMaxWidth(animated.coerceAtLeast(0.02f)).fillMaxHeight().clip(AppShapes.Capsule).background(color))
    }
}

/** Settings › Updates. */
@Composable
fun UpdatesSection(
    state: UpdateState,
    lastChecked: Long,
    settings: GlobalSettings,
    onUpdate: ((GlobalSettings) -> GlobalSettings) -> Unit,
    actions: UpdateActions,
) {
    val status = when (state) {
        UpdateState.Checking -> "Checking…"
        is UpdateState.UpToDate -> "Up to date · checked ${relativeTime(state.checkedAt)}"
        is UpdateState.Available -> "Version ${state.info.versionName} is available"
        is UpdateState.Downloading -> "Downloading ${state.info.versionName} · ${(state.progress * 100).toInt()}%"
        is UpdateState.Ready -> "Version ${state.info.versionName} is ready to install"
        is UpdateState.Installing -> "Installing ${state.info.versionName}…"
        is UpdateState.Failed -> state.message
        UpdateState.Idle -> if (lastChecked > 0) "Last checked ${relativeTime(lastChecked)}" else "New versions arrive from GitHub"
    }
    ListSection(
        header = "Updates",
        footer = "WebDroid checks GitHub when you open it and twice a day. Updates download on Wi‑Fi unless you allow mobile data.",
    ) {
        row {
            ListRow(
                title = "WebDroid ${BuildConfig.VERSION_NAME}",
                subtitle = status,
                trailing = if (state is UpdateState.Checking || state is UpdateState.Downloading || state is UpdateState.Installing) {
                    { CircularProgressIndicator(color = AppColors.SecondaryLabel, strokeWidth = 2.dp, modifier = Modifier.size(18.dp)) }
                } else null,
            )
        }
        val info = state.info
        when (state) {
            is UpdateState.Ready -> row(16.dp) { ActionRow("Install Version ${state.info.versionName}", actions.install) }
            is UpdateState.Available -> row(16.dp) { ActionRow("Download Version ${state.info.versionName}", actions.download) }
            is UpdateState.Failed -> row(16.dp) { ActionRow("Try Again", if (state.info != null) actions.download else actions.checkNow) }
            else -> row(16.dp) {
                ActionRow(
                    "Check for Updates", actions.checkNow,
                    enabled = state !is UpdateState.Checking && state !is UpdateState.Downloading && state !is UpdateState.Installing,
                )
            }
        }
        if (info != null && info.notes.isNotBlank()) {
            row(16.dp) { ListRow("What’s New in ${info.versionName}", chevron = true, onClick = actions.showNotes) }
        }
        row(16.dp) { ToggleRow("Automatic Updates", settings.autoUpdate, { v -> onUpdate { it.copy(autoUpdate = v) } }) }
        row(16.dp) {
            ToggleRow(
                "Download on Mobile Data", settings.updateOnMobileData,
                { v -> onUpdate { it.copy(updateOnMobileData = v) } },
                enabled = settings.autoUpdate,
            )
        }
    }
}

/** Renders release notes' simple Markdown (headings and bullets). */
@Composable
fun ReleaseNotes(notes: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        notes.lineSequence().map { it.trimEnd() }.forEach { raw ->
            val line = raw.replace("**", "").replace("`", "")
            when {
                line.isBlank() -> Unit
                line.startsWith("# ") -> Text(line.removePrefix("# "), style = AppType.Title2, color = AppColors.Label, modifier = Modifier.padding(bottom = 4.dp))
                line.startsWith("#") -> Text(line.trimStart('#', ' '), style = AppType.Headline, color = AppColors.Label, modifier = Modifier.padding(top = 12.dp))
                line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") -> Row {
                    Text("•", style = AppType.Body, color = AppColors.SecondaryLabel, modifier = Modifier.width(18.dp))
                    Text(line.trimStart().drop(2), style = AppType.Body, color = AppColors.Label)
                }
                else -> Text(line, style = AppType.Body, color = AppColors.Label)
            }
        }
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
