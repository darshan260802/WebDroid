package com.webdroid.app.ui.editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.ViewDay
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.webdroid.app.data.SiteMetadataFetcher
import com.webdroid.app.data.VideoMode
import com.webdroid.app.data.WebApp
import com.webdroid.app.data.WebAppSettings
import com.webdroid.app.ui.components.ActionRow
import com.webdroid.app.ui.components.AppButton
import com.webdroid.app.ui.components.AppIcon
import com.webdroid.app.ui.components.AppTextField
import com.webdroid.app.ui.components.ButtonStyle
import com.webdroid.app.ui.components.ChoiceRow
import com.webdroid.app.ui.components.ConfirmationSheet
import com.webdroid.app.ui.components.ContextMenu
import com.webdroid.app.ui.components.ListRow
import com.webdroid.app.ui.components.ListSection
import com.webdroid.app.ui.components.MenuAction
import com.webdroid.app.ui.components.ModalSheet
import com.webdroid.app.ui.components.SectionGap
import com.webdroid.app.ui.components.SegmentedControl
import com.webdroid.app.ui.components.SheetToolbar
import com.webdroid.app.ui.components.ToggleRow
import com.webdroid.app.ui.components.ToolbarTextButton
import com.webdroid.app.ui.components.rememberHaptics
import com.webdroid.app.ui.components.rememberPressState
import com.webdroid.app.ui.components.rememberSnapshotBackdrop
import com.webdroid.app.ui.components.tappable
import com.webdroid.app.ui.privacy.flag
import com.webdroid.app.ui.theme.AppColors
import com.webdroid.app.ui.theme.AppType
import com.webdroid.app.ui.theme.Motion
import com.webdroid.app.ui.theme.TileColor
import com.webdroid.app.util.UrlUtils
import com.webdroid.app.vpn.VpnProfiles
import com.webdroid.app.vpn.VpnType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class EditorResult(
    val name: String,
    val url: String,
    val icon: Bitmap?,
    val iconChanged: Boolean,
    val themeColor: Int?,
    val cardColor: Int,
    val settings: WebAppSettings,
)

/** Opens the editor: on an existing app, or on a new one (optionally pre-filled). */
data class EditorRequest(
    val existing: WebApp?,
    val icon: Bitmap?,
    val prefillUrl: String?,
    val tile: Int,
    /** Makes every request distinct, so reopening the same app starts fresh. */
    val serial: Long = System.nanoTime(),
)

@Stable
private class EditorState(request: EditorRequest) {
    val existing = request.existing
    private val prefill = request.prefillUrl.orEmpty()
    var url by mutableStateOf(existing?.url ?: prefill)
    var name by mutableStateOf(existing?.name.orEmpty())
    var nameEdited by mutableStateOf(existing != null)
    var icon by mutableStateOf(request.icon)
    var iconChanged by mutableStateOf(false)
    var iconLocked by mutableStateOf(existing != null)
    var themeColor by mutableStateOf(existing?.themeColor)
    var tile by mutableIntStateOf(existing?.cardColor ?: request.tile)
    var settings by mutableStateOf(existing?.settings ?: WebAppSettings())
    var fetching by mutableStateOf(false)
    var fetchedFor: String? = existing?.url
    var urlError by mutableStateOf<String?>(null)
    var refetchSerial by mutableIntStateOf(0)

    val normalized: String? get() = UrlUtils.normalize(url)
    val canSave: Boolean get() = normalized != null && name.isNotBlank()

    /** Whether closing would lose something the user did. */
    val dirty: Boolean
        get() = if (existing == null) url.trim() != prefill || (nameEdited && name.isNotBlank())
        else url != existing.url || name != existing.name || iconChanged || tile != existing.cardColor || settings != existing.settings
}

/**
 * Add / edit sheet. Cancel sits on the leading edge and Add/Save (the one
 * prominent action) on the trailing edge. With unsaved changes a swipe down
 * springs back and asks before discarding.
 */
@Composable
fun BoxScope.EditorSheet(
    request: EditorRequest?,
    isolatedSupported: () -> Boolean,
    lockSupported: () -> Boolean,
    onSave: (WebApp?, EditorResult) -> Unit,
    onDelete: (WebApp) -> Unit,
    onDismiss: () -> Unit,
    onProgress: (Float) -> Unit = {},
) {
    var last by remember { mutableStateOf(request) }
    if (request != null) last = request
    val current = last ?: return
    val state = remember(current) { EditorState(current) }
    val focus = LocalFocusManager.current
    var iconMenu by remember(current) { mutableStateOf<Rect?>(null) }
    var confirmDiscard by remember(current) { mutableStateOf(false) }
    var confirmDelete by remember(current) { mutableStateOf(false) }

    fun close() {
        focus.clearFocus()
        onDismiss()
    }

    fun requestClose() {
        if (state.dirty) {
            focus.clearFocus()
            confirmDiscard = true
        } else close()
    }

    ModalSheet(
        visible = request != null,
        onDismissRequest = ::requestClose,
        canDismiss = !state.dirty,
        onProgress = onProgress,
    ) {
        EditorContent(
            state,
            isolatedSupported,
            lockSupported,
            onCancel = ::requestClose,
            onSave = { result ->
                focus.clearFocus()
                onSave(state.existing, result)
            },
            onIconMenu = { iconMenu = it },
            onDeleteRequest = {
                focus.clearFocus()
                confirmDelete = true
            },
        )
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val bitmap = withContext(Dispatchers.IO) { decodeSquare(context, uri) }
                if (bitmap != null) {
                    state.icon = bitmap
                    state.iconChanged = true
                    state.iconLocked = true
                }
            }
        }
    }
    val menuBackdrop = rememberSnapshotBackdrop(iconMenu != null)
    ContextMenu(
        anchor = iconMenu,
        backdrop = menuBackdrop,
        onDismiss = { iconMenu = null },
        actions = listOf(
            MenuAction("Website Icon", Icons.Rounded.Refresh, enabled = state.normalized != null) {
                state.iconLocked = false
                state.refetchSerial++
            },
            MenuAction("Choose Photo", Icons.Rounded.Image) {
                pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            MenuAction("Letter", Icons.Rounded.TextFields) {
                state.icon = null
                state.iconChanged = true
                state.iconLocked = true
            },
        ),
    )

    val discardBackdrop = rememberSnapshotBackdrop(confirmDiscard)
    ConfirmationSheet(
        visible = confirmDiscard,
        backdrop = discardBackdrop,
        title = if (state.existing == null) "Discard this app?" else "Discard your changes?",
        confirmTitle = "Discard Changes",
        onConfirm = {
            confirmDiscard = false
            close()
        },
        onCancel = { confirmDiscard = false },
    )

    val deleteBackdrop = rememberSnapshotBackdrop(confirmDelete)
    val existing = state.existing
    ConfirmationSheet(
        visible = confirmDelete,
        backdrop = deleteBackdrop,
        title = "Delete “${existing?.name.orEmpty()}”?",
        message = "Its logins, saved data and home screen shortcut will be removed.",
        confirmTitle = "Delete App",
        onConfirm = {
            confirmDelete = false
            if (existing != null) onDelete(existing)
        },
        onCancel = { confirmDelete = false },
    )
}

@Composable
private fun EditorContent(
    state: EditorState,
    isolatedSupported: () -> Boolean,
    lockSupported: () -> Boolean,
    onCancel: () -> Unit,
    onSave: (EditorResult) -> Unit,
    onIconMenu: (Rect) -> Unit,
    onDeleteRequest: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val focus = LocalFocusManager.current
    val haptics = rememberHaptics()
    val existing = state.existing
    // hasText() doesn't trigger Android's "pasted from clipboard" notice; the
    // text itself is only read when the user taps Paste.
    val clipboardHasText = remember { existing == null && clipboard.hasText() }
    val isolated = remember { isolatedSupported() }
    val lockable = remember { lockSupported() }

    // Detect the name, icon and colour a moment after the address settles.
    val normalized = state.normalized
    LaunchedEffect(state, normalized, state.refetchSerial) {
        val target = normalized ?: return@LaunchedEffect
        if (target == state.fetchedFor && state.refetchSerial == 0) return@LaunchedEffect
        delay(650)
        state.fetching = true
        val meta = SiteMetadataFetcher.fetch(target)
        state.fetching = false
        state.fetchedFor = target
        if (meta != null) {
            if (!state.nameEdited && !meta.title.isNullOrBlank()) state.name = meta.title.take(40)
            if ((!state.iconLocked || state.refetchSerial > 0) && meta.icon != null) {
                state.icon = meta.icon
                state.iconChanged = true
            }
            if (meta.themeColor != null) state.themeColor = meta.themeColor
        } else if (!state.nameEdited && state.name.isBlank()) {
            state.name = UrlUtils.displayHost(target).substringBefore('.').replaceFirstChar { it.uppercase() }
        }
    }

    fun save() {
        val target = state.normalized
        if (target == null) {
            state.urlError = "Enter a website address, like example.com"
            haptics.reject()
            return
        }
        haptics.confirm()
        onSave(
            EditorResult(
                name = state.name.trim(),
                url = target,
                icon = state.icon,
                iconChanged = state.iconChanged,
                themeColor = state.themeColor,
                cardColor = state.tile,
                settings = state.settings,
            )
        )
    }

    SheetToolbar(
        if (existing == null) "New App" else "Edit App",
        leading = { ToolbarTextButton("Cancel", onCancel) },
        trailing = { ToolbarTextButton(if (existing == null) "Add" else "Save", ::save, prominent = true, enabled = state.canSave) },
    )
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(bottom = 32.dp),
    ) {
        IconHeader(state, onIconMenu)

        ListSection(
            footerContent = {
                val error = state.urlError
                when {
                    error != null -> Text(error, style = AppType.Footnote, color = AppColors.Destructive)
                    state.fetching -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = AppColors.SecondaryLabel, strokeWidth = 1.5.dp, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Getting the name and icon…", style = AppType.Footnote, color = AppColors.SecondaryLabel)
                    }
                    state.url.isBlank() && clipboardHasText -> AppButton(
                        "Paste Link",
                        onClick = {
                            val text = clipboard.getText()?.text?.trim().orEmpty()
                            if (UrlUtils.looksLikeUrl(text)) state.url = text
                            else state.urlError = "The clipboard doesn’t contain a link"
                        },
                        style = ButtonStyle.Tinted,
                        icon = Icons.Rounded.ContentPaste,
                        height = 34.dp,
                    )
                    else -> Text("WebDroid fills in the name and icon from the website.", style = AppType.Footnote, color = AppColors.SecondaryLabel)
                }
            },
        ) {
            row(46.dp) {
                AppTextField(
                    value = state.url,
                    onValueChange = {
                        state.url = it
                        state.urlError = null
                    },
                    placeholder = "Website, like youtube.com",
                    leading = Icons.Rounded.Language,
                    background = Color.Transparent,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                )
            }
            row(46.dp) {
                AppTextField(
                    value = state.name,
                    onValueChange = {
                        state.name = it.take(40)
                        state.nameEdited = true
                    },
                    placeholder = "Name",
                    leading = Icons.Rounded.TextFields,
                    background = Color.Transparent,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                )
            }
        }

        SectionGap()
        ListSection(header = "Color", footer = "Used for the letter icon, notifications and the app’s card in Recents.") {
            row { Swatches(state.tile) { state.tile = it } }
        }

        SectionGap()
        ListSection(
            header = "Videos",
            footer = when (state.settings.videoMode) {
                VideoMode.AUTO -> "Videos you play open straight in WebDroid’s player."
                VideoMode.BUTTON -> "A Play in WebDroid button appears when a video plays."
                VideoMode.OFF -> "Videos stay in the page."
            },
        ) {
            row {
                Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    SegmentedControl(
                        listOf(VideoMode.AUTO to "Player", VideoMode.BUTTON to "Ask", VideoMode.OFF to "In Page"),
                        state.settings.videoMode,
                        { mode -> state.settings = state.settings.copy(videoMode = mode) },
                    )
                }
            }
        }

        val s = state.settings
        SectionGap()
        ListSection(header = "Browsing", footer = "Pull a page down to refresh it; keep pulling for the menu.") {
            row {
                ToggleRow(
                    "Mini Toolbar", s.miniToolbar, { state.settings = s.copy(miniToolbar = it) },
                    subtitle = "Back, reload and share in a bar you pull down",
                    icon = Icons.Rounded.ViewDay, iconBackground = Color(0xFF8E8E93),
                )
            }
            row {
                ToggleRow(
                    "Desktop Site", s.desktopMode, { state.settings = s.copy(desktopMode = it) },
                    icon = Icons.Rounded.DesktopWindows, iconBackground = Color(0xFF1E7BF2),
                )
            }
            row {
                ToggleRow(
                    "Force Dark", s.forceDark, { state.settings = s.copy(forceDark = it) },
                    subtitle = "Darken sites without a dark theme",
                    icon = Icons.Rounded.DarkMode, iconBackground = Color(0xFF4B4FE0),
                )
            }
            row {
                ToggleRow(
                    "Open Other Sites in Browser", s.openLinksExternally, { state.settings = s.copy(openLinksExternally = it) },
                    icon = Icons.AutoMirrored.Rounded.OpenInNew, iconBackground = Color(0xFF30B350),
                )
            }
        }

        SectionGap()
        ListSection(header = "Privacy & Security") {
            row {
                ToggleRow(
                    "Block Ads", s.adBlock, { state.settings = s.copy(adBlock = it) },
                    icon = Icons.Rounded.Shield, iconBackground = Color(0xFFFF3B30),
                )
            }
            row {
                ToggleRow(
                    "Separate Login", s.isolatedSession, { state.settings = s.copy(isolatedSession = it) },
                    subtitle = if (isolated) "Its own cookies, so you can sign in with another account" else "Needs a newer Android System WebView",
                    icon = Icons.Rounded.People, iconBackground = Color(0xFFFF9500),
                    enabled = isolated,
                )
            }
            row {
                ToggleRow(
                    "Fingerprint Lock", s.appLock, { state.settings = s.copy(appLock = it) },
                    subtitle = if (lockable) null else "Set up a screen lock first",
                    icon = Icons.Rounded.Fingerprint, iconBackground = Color(0xFFFF2D55),
                    enabled = lockable,
                )
            }
        }

        SectionGap()
        ListSection(
            header = "Background",
            footer = "For chat and mail: keeps the app connected after you leave it, so its notifications arrive. Uses more battery.",
        ) {
            row {
                ToggleRow(
                    "Stay Connected", s.keepAlive, { state.settings = s.copy(keepAlive = it) },
                    icon = Icons.Rounded.Wifi, iconBackground = Color(0xFF30B0C7),
                )
            }
        }

        SectionGap()
        VpnChoices(
            s.vpn, s.torCountry,
            onSelect = { state.settings = state.settings.copy(vpn = it) },
            onTorCountry = { state.settings = state.settings.copy(torCountry = it) },
        )

        if (existing != null) {
            SectionGap()
            ListSection {
                row(16.dp) { ActionRow("Delete App", onDeleteRequest, destructive = true) }
            }
        }
    }
}

@Composable
private fun IconHeader(state: EditorState, onIconMenu: (Rect) -> Unit) {
    var bounds by remember { mutableStateOf<Rect?>(null) }
    val press = rememberPressState()
    val pulse = if (state.fetching) {
        val transition = rememberInfiniteTransition(label = "fetch")
        transition.animateFloat(1f, 0.5f, infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "fetch-alpha").value
    } else 1f
    val scale by animateFloatAsState(if (press.pressed) 0.94f else 1f, Motion.snappy(), label = "icon-press")
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .onGloballyPositioned { bounds = it.boundsInWindow() }
                .graphicsLayer { scaleX = scale; scaleY = scale; alpha = pulse }
                .tappable(press) { bounds?.let(onIconMenu) },
        ) {
            AppIcon(state.icon, state.name.ifBlank { "W" }, TileColor.of(state.tile), 88.dp)
        }
        val editPress = rememberPressState()
        Text(
            "Edit Icon",
            style = AppType.Subhead,
            color = AppColors.Accent,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(top = 6.dp)
                .graphicsLayer { alpha = if (editPress.pressed) 0.5f else 1f }
                .tappable(editPress) { bounds?.let(onIconMenu) }
                .padding(horizontal = 12.dp, vertical = 10.dp),
        )
        Text(
            state.name.ifBlank { "New App" },
            style = AppType.Title3,
            color = if (state.name.isBlank()) AppColors.TertiaryLabel else AppColors.Label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
        Text(
            state.normalized?.let { UrlUtils.displayHost(it) } ?: " ",
            style = AppType.Subhead,
            color = AppColors.SecondaryLabel,
            maxLines = 1,
        )
    }
}

@Composable
private fun Swatches(selected: Int, onSelect: (Int) -> Unit) {
    val haptics = rememberHaptics()
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        TileColor.entries.forEachIndexed { index, tile ->
            val isSelected = selected == index
            val press = rememberPressState()
            val ring by animateFloatAsState(if (isSelected) 1f else 0f, Motion.spring(0.3f, 0.75f), label = "swatch")
            Box(
                Modifier
                    .weight(1f)
                    .height(48.dp)
                    .semantics {
                        this.selected = isSelected
                        contentDescription = tile.name
                    }
                    .tappable(press, role = Role.RadioButton, haptic = false) {
                        haptics.tick()
                        onSelect(index)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(34.dp)
                        .graphicsLayer { alpha = ring; scaleX = 0.8f + 0.2f * ring; scaleY = 0.8f + 0.2f * ring }
                        .border(2.dp, tile.solid, CircleShape)
                )
                Box(
                    Modifier
                        .size(26.dp)
                        .graphicsLayer { val s = if (press.pressed) 0.88f else 1f; scaleX = s; scaleY = s }
                        .background(Brush.verticalGradient(listOf(tile.top, tile.bottom)), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isSelected) Icon(Icons.Rounded.Check, null, tint = tile.glyph, modifier = Modifier.size(15.dp))
                }
            }
        }
    }
}

/**
 * Direct, Tor or a saved profile, for one app. With Tor, the app can also
 * leave the Tor network in its own country ([torCountry], see
 * [com.webdroid.app.data.WebAppSettings.torCountry]).
 */
@Composable
fun VpnChoices(
    selected: String?,
    torCountry: String,
    onSelect: (String?) -> Unit,
    onTorCountry: (String) -> Unit,
) {
    val store = VpnProfiles.get(LocalContext.current)
    val profiles by store.profiles.collectAsState()
    val tor by store.tor.collectAsState()
    var picking by remember { mutableStateOf(false) }
    val missing = selected != null && selected != VpnProfiles.TOR_ID && profiles.none { it.id == selected }
    val defaultLabel = tor.exitCountry.takeIf { it.isNotEmpty() }?.let { "${flag(it)}  ${VpnProfiles.countryName(it)}" } ?: "Any Country"
    fun countryLabel(code: String): String = when (code) {
        "" -> "Default ($defaultLabel)"
        VpnProfiles.ANY_COUNTRY -> "Any Country"
        else -> "${flag(code)}  ${VpnProfiles.countryName(code)}"
    }
    ListSection(
        header = "VPN",
        footer = if (selected == VpnProfiles.TOR_ID) {
            "Sites see this app as visiting from its exit country; other apps can use other countries at the same time. Countries with few relays are slower."
        } else {
            "Only this app’s websites use the VPN; everything else stays direct. Add WireGuard configs and proxies in the Privacy tab."
        },
    ) {
        row(16.dp) { ChoiceRow("Direct", selected == null, { onSelect(null) }) }
        row(if (selected == VpnProfiles.TOR_ID) 32.dp else 16.dp) {
            ChoiceRow("Tor", selected == VpnProfiles.TOR_ID, { onSelect(VpnProfiles.TOR_ID) }, subtitle = "Free and private, but slower")
        }
        if (selected == VpnProfiles.TOR_ID) {
            row(if (picking) 32.dp else 16.dp) {
                val turn by animateFloatAsState(if (picking) 90f else 0f, Motion.snappy(), label = "country-chevron")
                ListRow(
                    "Exit Country",
                    modifier = Modifier.padding(start = 16.dp),
                    onClick = { picking = !picking },
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                when (torCountry) {
                                    "" -> "Default"
                                    VpnProfiles.ANY_COUNTRY -> "Any"
                                    else -> "${flag(torCountry)} ${torCountry.uppercase()}"
                                },
                                style = AppType.Body, color = AppColors.SecondaryLabel, maxLines = 1,
                            )
                            Icon(
                                Icons.AutoMirrored.Rounded.KeyboardArrowRight, null,
                                tint = AppColors.TertiaryLabel,
                                modifier = Modifier.padding(start = 4.dp).size(22.dp).graphicsLayer { rotationZ = turn },
                            )
                        }
                    },
                )
            }
            if (picking) {
                val codes = listOf("", VpnProfiles.ANY_COUNTRY) + VpnProfiles.TOR_COUNTRIES.map { it.first }.filter { it.isNotEmpty() }
                codes.forEachIndexed { i, code ->
                    row(if (i == codes.lastIndex) 16.dp else 32.dp) {
                        ChoiceRow(
                            countryLabel(code), torCountry == code,
                            {
                                onTorCountry(code)
                                picking = false
                            },
                            modifier = Modifier.padding(start = 16.dp),
                        )
                    }
                }
            }
        }
        profiles.forEach { profile ->
            row(16.dp) {
                ChoiceRow(
                    profile.name, selected == profile.id, { onSelect(profile.id) },
                    subtitle = when (profile.type) {
                        VpnType.WIREGUARD -> "WireGuard"
                        VpnType.SOCKS5 -> "SOCKS5 proxy"
                        VpnType.HTTP -> "HTTP proxy"
                    },
                )
            }
        }
        if (missing) {
            row(16.dp) {
                ChoiceRow("Deleted VPN", true, { onSelect(null) }, subtitle = "This profile no longer exists. Choose another connection.")
            }
        }
    }
}

private fun decodeSquare(context: android.content.Context, uri: Uri): Bitmap? = runCatching {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= 384 && bounds.outHeight / (sample * 2) >= 384) sample *= 2
    val bitmap = resolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: return null
    val side = minOf(bitmap.width, bitmap.height)
    val square = Bitmap.createBitmap(bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side)
    Bitmap.createScaledBitmap(square, 192, 192, true)
}.getOrNull()
