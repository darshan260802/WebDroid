package com.webdroid.app.ui.editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.webdroid.app.data.SiteMetadataFetcher
import com.webdroid.app.data.VideoMode
import com.webdroid.app.data.WebApp
import com.webdroid.app.data.WebAppSettings
import com.webdroid.app.ui.components.AppGlyph
import com.webdroid.app.ui.components.ClayButton
import com.webdroid.app.ui.components.ClayButtonStyle
import com.webdroid.app.ui.components.ClayIconButton
import com.webdroid.app.ui.components.ClaySegmented
import com.webdroid.app.ui.components.ClaySwitchRow
import com.webdroid.app.ui.components.ClayTextField
import com.webdroid.app.ui.components.SectionLabel
import com.webdroid.app.ui.components.rememberHaptics
import com.webdroid.app.ui.theme.CardColor
import com.webdroid.app.ui.theme.Clay
import com.webdroid.app.ui.theme.ClayMotion
import com.webdroid.app.ui.theme.ClayShapes
import com.webdroid.app.ui.theme.ClayType
import com.webdroid.app.util.UrlUtils
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

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun EditorScreen(
    existing: WebApp?,
    existingIcon: Bitmap?,
    defaultCardColor: Int,
    isolatedSupported: Boolean,
    lockSupported: Boolean,
    onSave: (EditorResult) -> Unit,
    onCancel: () -> Unit,
    onDelete: (WebApp) -> Unit,
    sharedScope: SharedTransitionScope,
    animatedScope: AnimatedVisibilityScope,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val focus = LocalFocusManager.current
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()

    var url by remember { mutableStateOf(existing?.url.orEmpty()) }
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var nameEdited by remember { mutableStateOf(existing != null) }
    var icon by remember { mutableStateOf(existingIcon) }
    var iconChanged by remember { mutableStateOf(false) }
    var iconLocked by remember { mutableStateOf(existing != null) }
    var themeColor by remember { mutableStateOf(existing?.themeColor) }
    var cardColor by remember { mutableIntStateOf(existing?.cardColor ?: defaultCardColor) }
    var settings by remember { mutableStateOf(existing?.settings ?: WebAppSettings()) }
    var fetching by remember { mutableStateOf(false) }
    var fetchedFor by remember { mutableStateOf(existing?.url) }
    var urlError by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var refetchSerial by remember { mutableIntStateOf(0) }

    val normalized = UrlUtils.normalize(url)
    val clipboardUrl = remember {
        clipboard.getText()?.text?.trim()?.takeIf { existing == null && UrlUtils.looksLikeUrl(it) && it.length < 2000 }
    }

    // Auto-detect name, icon and colours a moment after the URL settles.
    LaunchedEffect(normalized, refetchSerial) {
        val target = normalized ?: return@LaunchedEffect
        if (target == fetchedFor && refetchSerial == 0) return@LaunchedEffect
        delay(650)
        fetching = true
        val meta = SiteMetadataFetcher.fetch(target)
        fetching = false
        fetchedFor = target
        if (meta != null) {
            if (!nameEdited && !meta.title.isNullOrBlank()) name = meta.title
            if ((!iconLocked || refetchSerial > 0) && meta.icon != null) {
                icon = meta.icon
                iconChanged = true
            }
            if (meta.themeColor != null) themeColor = meta.themeColor
        } else if (!nameEdited && name.isBlank()) {
            name = UrlUtils.displayHost(target).substringBefore('.').replaceFirstChar { it.uppercase() }
        }
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val bitmap = withContext(Dispatchers.IO) { decodeSquare(context, uri) }
                if (bitmap != null) {
                    icon = bitmap
                    iconChanged = true
                    iconLocked = true
                }
            }
        }
    }

    val colors = CardColor.of(cardColor)
    val canSave = normalized != null && name.isNotBlank()

    Box(Modifier.fillMaxSize().background(Clay.Canvas)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 120.dp),
        ) {
            Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                ClayIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onCancel)
                Spacer(Modifier.width(14.dp))
                Text(if (existing == null) "New app" else "Edit app", style = ClayType.TitleLg, color = Clay.Ink)
            }

            // Live preview card
            with(sharedScope) {
                val background by animateColorAsState(colors.background, label = "preview-bg")
                Column(
                    Modifier
                        .sharedBounds(
                            rememberSharedContentState(if (existing == null) "editor-new" else "card-${existing.id}"),
                            animatedVisibilityScope = animatedScope,
                            resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
                        )
                        .fillMaxWidth()
                        .clip(ClayShapes.Xl)
                        .background(background)
                        .padding(24.dp),
                ) {
                    Row(verticalAlignment = Alignment.Top) {
                        val pulse = rememberInfiniteTransition(label = "fetch")
                        val alpha by pulse.animateFloat(
                            initialValue = 1f, targetValue = 0.45f,
                            animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "fetch-alpha",
                        )
                        AppGlyph(
                            icon, name.ifBlank { "W" }, colors, size = 72.dp,
                            modifier = Modifier
                                .graphicsLayer { this.alpha = if (fetching) alpha else 1f }
                                .clickable {
                                    pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                },
                        )
                        Spacer(Modifier.weight(1f))
                        if (fetching) {
                            Text("Detecting…", style = ClayType.Caption, color = colors.contentSoft)
                        }
                    }
                    Spacer(Modifier.height(28.dp))
                    Text(
                        name.ifBlank { "Your app" }, style = ClayType.DisplaySm, color = colors.content,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        normalized?.let { UrlUtils.displayHost(it) } ?: "website.com",
                        style = ClayType.BodySm, color = colors.contentSoft, maxLines = 1,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            SectionLabel("Website")
            Spacer(Modifier.height(8.dp))
            ClayTextField(
                value = url,
                onValueChange = {
                    url = it
                    urlError = null
                },
                placeholder = "youtube.com",
                leading = Icons.Rounded.Language,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onNext = { focus.moveFocus(androidx.compose.ui.focus.FocusDirection.Down) }),
                modifier = Modifier.fillMaxWidth(),
            )
            AnimatedVisibility(visible = url.isBlank() && clipboardUrl != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Row(
                    Modifier
                        .padding(top = 10.dp)
                        .clip(ClayShapes.Pill)
                        .background(Clay.SurfaceCard)
                        .clickable {
                            haptics.tick()
                            url = clipboardUrl.orEmpty()
                        }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.ContentPaste, null, tint = Clay.Ink, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Paste ${UrlUtils.displayHost(clipboardUrl)}", style = ClayType.Caption, color = Clay.Ink, maxLines = 1)
                }
            }
            if (urlError != null) {
                Text(urlError.orEmpty(), style = ClayType.Caption, color = Clay.Error, modifier = Modifier.padding(top = 6.dp))
            }

            Spacer(Modifier.height(18.dp))
            SectionLabel("Name")
            Spacer(Modifier.height(8.dp))
            ClayTextField(
                value = name,
                onValueChange = {
                    name = it.take(40)
                    nameEdited = true
                },
                placeholder = "App name",
                leading = Icons.Rounded.TextFields,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(22.dp))
            SectionLabel("Colour")
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CardColor.entries.forEachIndexed { index, color ->
                    val selected = cardColor == index
                    val ring by animateFloatAsState(if (selected) 1f else 0f, ClayMotion.bouncy(), label = "ring")
                    Box(
                        Modifier
                            .size(42.dp)
                            .graphicsLayer { scaleX = 0.88f + 0.12f * ring; scaleY = 0.88f + 0.12f * ring }
                            .clip(CircleShape)
                            .border(if (selected) 2.dp else 1.dp, if (selected) Clay.Ink else Clay.Hairline, CircleShape)
                            .padding(4.dp)
                            .clip(CircleShape)
                            .background(color.background)
                            .clickable {
                                haptics.tick()
                                cardColor = index
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selected) Icon(Icons.Rounded.Check, null, tint = color.content, modifier = Modifier.size(18.dp))
                    }
                }
            }

            Spacer(Modifier.height(22.dp))
            SectionLabel("Icon")
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ClayButton(
                    "Site icon", onClick = { iconLocked = false; refetchSerial++ },
                    icon = Icons.Rounded.Refresh, style = ClayButtonStyle.Secondary, height = 44.dp,
                    enabled = normalized != null,
                )
                ClayButton(
                    "Photo", onClick = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    icon = Icons.Rounded.Image, style = ClayButtonStyle.Secondary, height = 44.dp,
                )
                ClayButton(
                    "Letter", onClick = { icon = null; iconChanged = true; iconLocked = true },
                    icon = Icons.Rounded.TextFields, style = ClayButtonStyle.Secondary, height = 44.dp,
                )
            }

            Spacer(Modifier.height(26.dp))
            SectionLabel("Video player")
            Spacer(Modifier.height(10.dp))
            ClaySegmented(
                options = listOf(VideoMode.AUTO to "Auto", VideoMode.BUTTON to "Button", VideoMode.OFF to "Off"),
                selected = settings.videoMode,
                onSelect = { settings = settings.copy(videoMode = it) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                when (settings.videoMode) {
                    VideoMode.AUTO -> "Videos you play open straight in the native player."
                    VideoMode.BUTTON -> "A \"Play in WebDroid player\" pill appears when a video plays."
                    VideoMode.OFF -> "Videos stay in the page."
                },
                style = ClayType.Caption, color = Clay.Muted, modifier = Modifier.padding(top = 8.dp),
            )

            Spacer(Modifier.height(22.dp))
            SectionLabel("Behaviour")
            Spacer(Modifier.height(4.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(ClayShapes.Lg)
                    .background(Clay.SurfaceSoft)
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                ClaySwitchRow(
                    "Mini toolbar", "Pull-down bar with back, reload and share. Pull the page down anyway to refresh, or further for the menu.",
                    settings.miniToolbar, { settings = settings.copy(miniToolbar = it) }, icon = Icons.Rounded.Menu,
                )
                ClaySwitchRow(
                    "Block ads", "uBlock Origin filters for this app",
                    settings.adBlock, { settings = settings.copy(adBlock = it) }, icon = Icons.Rounded.Shield,
                )
                ClaySwitchRow(
                    "Separate login",
                    if (isolatedSupported) "Its own cookies and storage, so you can use another account" else "Update Android System WebView to use this",
                    settings.isolatedSession, { settings = settings.copy(isolatedSession = it) },
                    icon = Icons.Rounded.Security, enabled = isolatedSupported,
                )
                ClaySwitchRow(
                    "Desktop site", "Ask for the computer version of the site",
                    settings.desktopMode, { settings = settings.copy(desktopMode = it) }, icon = Icons.Rounded.DesktopWindows,
                )
                ClaySwitchRow(
                    "Force dark mode", "Darken sites that don't have a dark theme",
                    settings.forceDark, { settings = settings.copy(forceDark = it) }, icon = Icons.Rounded.DarkMode,
                )
                ClaySwitchRow(
                    "Fingerprint lock", if (lockSupported) "Unlock with fingerprint or screen lock" else "Set up a screen lock first",
                    settings.appLock, { settings = settings.copy(appLock = it) }, icon = Icons.Rounded.Fingerprint,
                    enabled = lockSupported,
                )
                ClaySwitchRow(
                    "Open other sites in browser", "Links to other websites leave the app",
                    settings.openLinksExternally, { settings = settings.copy(openLinksExternally = it) }, icon = Icons.Rounded.OpenInNew,
                )
            }

            if (existing != null) {
                Spacer(Modifier.height(28.dp))
                if (!confirmDelete) {
                    ClayButton(
                        "Delete app", onClick = { confirmDelete = true }, style = ClayButtonStyle.Danger,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text("Delete ${existing.name}? Its logins and data will be removed.", style = ClayType.BodyMd, color = Clay.Ink)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ClayButton("Keep", onClick = { confirmDelete = false }, style = ClayButtonStyle.Secondary, modifier = Modifier.weight(1f))
                        ClayButton("Delete", onClick = { onDelete(existing) }, modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        // Sticky save bar
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Clay.Canvas.copy(alpha = 0f), Clay.Canvas, Clay.Canvas)))
                .navigationBarsPadding()
                .imePadding()
                .padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 14.dp),
        ) {
            ClayButton(
                text = if (existing == null) "Add app" else "Save changes",
                onClick = {
                    val target = normalized
                    if (target == null) {
                        urlError = "Enter a website address like example.com"
                        haptics.reject()
                        return@ClayButton
                    }
                    haptics.confirm()
                    onSave(
                        EditorResult(
                            name = name.trim(),
                            url = target,
                            icon = icon,
                            iconChanged = iconChanged,
                            themeColor = themeColor,
                            cardColor = cardColor,
                            settings = settings,
                        )
                    )
                },
                enabled = canSave,
                icon = if (existing == null) Icons.Rounded.Public else Icons.Rounded.Check,
                modifier = Modifier.fillMaxWidth(),
                height = 56.dp,
            )
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
