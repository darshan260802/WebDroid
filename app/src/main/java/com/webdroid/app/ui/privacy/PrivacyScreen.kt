package com.webdroid.app.ui.privacy

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material.icons.rounded.VpnLock
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.webdroid.app.adblock.AdblockEngine
import com.webdroid.app.adblock.FilterListInfo
import com.webdroid.app.data.GlobalSettings
import com.webdroid.app.ui.components.ActionRow
import com.webdroid.app.ui.components.AppTextField
import com.webdroid.app.ui.components.ChoiceRow
import com.webdroid.app.ui.components.ConfirmationSheet
import com.webdroid.app.ui.components.ContextMenu
import com.webdroid.app.ui.components.ListRow
import com.webdroid.app.ui.components.ListScreen
import com.webdroid.app.ui.components.ListSection
import com.webdroid.app.ui.components.LocalChromeBackdrop
import com.webdroid.app.ui.components.MenuAction
import com.webdroid.app.ui.components.ModalSheet
import com.webdroid.app.ui.components.NavStack
import com.webdroid.app.ui.components.Overlay
import com.webdroid.app.ui.components.SectionGap
import com.webdroid.app.ui.components.SegmentedControl
import com.webdroid.app.ui.components.SheetToolbar
import com.webdroid.app.ui.components.ToggleRow
import com.webdroid.app.ui.components.ToolbarTextButton
import com.webdroid.app.ui.components.rememberSnapshotBackdrop
import com.webdroid.app.ui.theme.AppColors
import com.webdroid.app.ui.theme.AppType
import com.webdroid.app.ui.theme.Spacing
import com.webdroid.app.ui.update.ProgressBar
import com.webdroid.app.ui.update.relativeTime
import com.webdroid.app.vpn.TorController
import com.webdroid.app.vpn.TorSettings
import com.webdroid.app.vpn.VpnManager
import com.webdroid.app.vpn.VpnProfile
import com.webdroid.app.vpn.VpnProfiles
import com.webdroid.app.vpn.VpnType
import kotlinx.coroutines.launch

sealed interface PrivacyRoute {
    data object Root : PrivacyRoute
    data object FilterLists : PrivacyRoute
    data object Tor : PrivacyRoute
    data class Profile(val id: String) : PrivacyRoute
}

private val ShieldGreen = Color(0xFF30B350)
private val TorPurple = Color(0xFF7E57C2)
private val KeyBlue = Color(0xFF1E7BF2)
private val ProxyTeal = Color(0xFF0A8EA6)

private sealed interface CheckState {
    data object Running : CheckState
    data class Done(val text: String, val ok: Boolean) : CheckState
}

private sealed interface AddSheet {
    data class WireGuard(val name: String, val text: String) : AddSheet
    data object Proxy : AddSheet
}

/** The Privacy tab: the ad blocker and per-site VPNs. */
@Composable
fun PrivacyScreen(
    stack: List<PrivacyRoute>,
    onPush: (PrivacyRoute) -> Unit,
    onPop: () -> Unit,
    settings: GlobalSettings,
    status: AdblockEngine.Status,
    lists: List<FilterListInfo>,
    lastUpdated: Long,
    onUpdate: ((GlobalSettings) -> GlobalSettings) -> Unit,
    onUpdateFilters: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val checks = remember { mutableStateMapOf<String, CheckState>() }
    var addAnchor by remember { mutableStateOf<Rect?>(null) }
    var sheet by remember { mutableStateOf<AddSheet?>(null) }

    fun runCheck(id: String) {
        checks[id] = CheckState.Running
        scope.launch {
            val result = VpnManager.check(context, id)
            checks[id] = if (result.error != null) CheckState.Done(result.error, false)
            else CheckState.Done(listOfNotNull(result.ip?.let { "Exit IP $it" }, result.country?.let(::countryName)).joinToString(" · "), true)
        }
    }

    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes().take(64 * 1024).toByteArray().decodeToString() }
        }.getOrNull().orEmpty()
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()?.substringBeforeLast('.') ?: "WireGuard"
        sheet = AddSheet.WireGuard(name, text)
    }

    NavStack(stack, onPop, backdrop = LocalChromeBackdrop.current) { route ->
        when (route) {
            PrivacyRoute.Root -> PrivacyRoot(
                settings, status, lists, lastUpdated, onUpdate, onUpdateFilters, onPush,
                onAdd = { addAnchor = it },
            )
            PrivacyRoute.FilterLists -> FilterListsScreen(settings, status, lists, onUpdate, onUpdateFilters, onPop)
            PrivacyRoute.Tor -> TorScreen(checks[VpnProfiles.TOR_ID], { runCheck(VpnProfiles.TOR_ID) }, onPop)
            is PrivacyRoute.Profile -> ProfileScreen(route.id, checks[route.id], { runCheck(route.id) }, onPop)
        }
    }

    Overlay {
        val menuBackdrop = rememberSnapshotBackdrop(addAnchor != null)
        ContextMenu(
            anchor = addAnchor,
            backdrop = menuBackdrop,
            onDismiss = { addAnchor = null },
            actions = listOf(
                MenuAction("Import Config File", Icons.Rounded.FileOpen) { importFile.launch(arrayOf("*/*")) },
                MenuAction("Paste WireGuard Config", Icons.Rounded.ContentPaste) { sheet = AddSheet.WireGuard("", "") },
                MenuAction("Add Proxy", Icons.Rounded.WifiTethering) { sheet = AddSheet.Proxy },
            ),
        )
        val current = sheet
        ModalSheet(visible = current is AddSheet.WireGuard, onDismissRequest = { sheet = null }) {
            val wg = remember(current) { current as? AddSheet.WireGuard } ?: AddSheet.WireGuard("", "")
            WireGuardForm(wg.name, wg.text, onCancel = { sheet = null }) { name, text ->
                VpnProfiles.get(context).add(name.ifBlank { "WireGuard" }, VpnType.WIREGUARD, text)
                sheet = null
            }
        }
        ModalSheet(visible = current == AddSheet.Proxy, onDismissRequest = { sheet = null }) {
            ProxyForm(onCancel = { sheet = null }) { name, type, spec ->
                VpnProfiles.get(context).add(name, type, spec)
                sheet = null
            }
        }
    }
}

@Composable
private fun PrivacyRoot(
    settings: GlobalSettings,
    status: AdblockEngine.Status,
    lists: List<FilterListInfo>,
    lastUpdated: Long,
    onUpdate: ((GlobalSettings) -> GlobalSettings) -> Unit,
    onUpdateFilters: () -> Unit,
    onPush: (PrivacyRoute) -> Unit,
    onAdd: (Rect) -> Unit,
) {
    val context = LocalContext.current
    val store = VpnProfiles.get(context)
    val profiles by store.profiles.collectAsState()
    val tor by store.tor.collectAsState()
    val torStatus by TorController.status.collectAsState()
    val enabledLists = lists.count { settings.listOverrides[it.id] ?: it.enabled }
    val pending = lists.any { settings.listOverrides[it.id] != null && settings.listOverrides[it.id] != it.enabled }
    var addRowBounds by remember { mutableStateOf<Rect?>(null) }

    ListScreen(title = "Privacy", largeTitle = true) {
        item(key = "adblock") {
            val subtitle = when {
                !settings.adBlockEnabled -> "Off"
                status.state == AdblockEngine.State.READY -> "uBlock Origin · ${formatCount(status.networkFilters)} filters"
                status.state == AdblockEngine.State.FAILED -> "Couldn't start the engine"
                else -> "Starting…"
            }
            ListSection(
                header = "Ad Blocker",
                footer = "Blocks ads and trackers in every web app using uBlock Origin’s engine and filter lists. Each app can turn it off in its settings.",
            ) {
                row { ToggleRow("Block Ads", settings.adBlockEnabled, { v -> onUpdate { it.copy(adBlockEnabled = v) } }, subtitle = subtitle, icon = Icons.Rounded.Shield, iconBackground = ShieldGreen) }
                row {
                    ListRow(
                        "Filter Lists", icon = Icons.Rounded.FilterList, iconBackground = Color(0xFF8E8E93),
                        value = "$enabledLists On", chevron = true, onClick = { onPush(PrivacyRoute.FilterLists) },
                    )
                }
                row {
                    ListRow(
                        title = if (pending) "Apply List Changes" else "Update Filters",
                        subtitle = when {
                            status.updating -> status.updateProgress ?: "Updating…"
                            status.lastError != null -> status.lastError
                            lastUpdated > 0 -> "Updated ${relativeTime(lastUpdated)}"
                            else -> "Refreshes automatically every few days"
                        },
                        icon = Icons.Rounded.Sync,
                        iconBackground = AppColors.Accent,
                        titleColor = AppColors.Accent,
                        enabled = !status.updating,
                        onClick = onUpdateFilters,
                        trailing = if (status.updating) {
                            { CircularProgressIndicator(color = AppColors.SecondaryLabel, strokeWidth = 2.dp, modifier = Modifier.size(18.dp)) }
                        } else null,
                    )
                }
            }
        }
        item(key = "vpn") {
            SectionGap()
            ListSection(
                header = "VPN",
                footer = if (VpnManager.isSupported) {
                    "Choose a VPN for each app in its settings. Only that app’s sites use it; everything else connects directly."
                } else {
                    "Update Android System WebView from the Play Store to use a VPN for web apps."
                },
            ) {
                row {
                    val value = when (torStatus.phase) {
                        TorController.Phase.STARTING -> "${torStatus.progress}%"
                        TorController.Phase.ON -> "Connected"
                        TorController.Phase.ERROR -> "Error"
                        TorController.Phase.OFF -> tor.exitCountry.takeIf { it.isNotEmpty() }?.let { flag(it) } ?: "Any country"
                    }
                    ListRow("Tor", subtitle = "Free and anonymous", icon = Icons.Rounded.VpnLock, iconBackground = TorPurple, value = value, chevron = true, onClick = { onPush(PrivacyRoute.Tor) })
                }
                profiles.forEach { p ->
                    row {
                        ListRow(
                            p.name,
                            subtitle = "${p.typeLabel} · ${p.summary}",
                            icon = if (p.type == VpnType.WIREGUARD) Icons.Rounded.VpnKey else Icons.Rounded.WifiTethering,
                            iconBackground = if (p.type == VpnType.WIREGUARD) KeyBlue else ProxyTeal,
                            chevron = true,
                            onClick = { onPush(PrivacyRoute.Profile(p.id)) },
                        )
                    }
                }
                row(16.dp) {
                    Column(Modifier.onGloballyPositioned { addRowBounds = it.boundsInWindow() }) {
                        ActionRow("Add VPN…", onClick = { addRowBounds?.let(onAdd) })
                    }
                }
            }
        }
        item(key = "vpn-help") {
            SectionGap(16.dp)
            ListSection(footer = "Free WireGuard configs: Proton VPN’s free plan (account › Downloads › WireGuard), Cloudflare WARP via wgcf, or your own server.") {
                row(16.dp) {
                    ActionRow("Get Free WireGuard Configs", onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://account.protonvpn.com/downloads")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    })
                }
            }
        }
    }
}

@Composable
private fun FilterListsScreen(
    settings: GlobalSettings,
    status: AdblockEngine.Status,
    lists: List<FilterListInfo>,
    onUpdate: ((GlobalSettings) -> GlobalSettings) -> Unit,
    onUpdateFilters: () -> Unit,
    onBack: () -> Unit,
) {
    val grouped = remember(lists) { lists.groupBy { it.group } }
    val pending = lists.any { settings.listOverrides[it.id] != null && settings.listOverrides[it.id] != it.enabled }
    ListScreen(
        title = "Filter Lists",
        largeTitle = false,
        onBack = onBack,
        trailing = {
            if (pending) ToolbarTextButton("Apply", onUpdateFilters, prominent = true, enabled = !status.updating)
        },
    ) {
        grouped.entries.forEachIndexed { index, (group, items) ->
            item(key = "group-$group") {
                if (index > 0) SectionGap(24.dp)
                ListSection(
                    header = group,
                    footer = if (index == grouped.size - 1) "The same lists uBlock Origin uses. Changes apply after the next update." else null,
                ) {
                    items.forEach { list ->
                        row(16.dp) {
                            val enabled = settings.listOverrides[list.id] ?: list.enabled
                            ToggleRow(
                                list.title, enabled,
                                { v ->
                                    onUpdate { s ->
                                        val overrides = s.listOverrides.toMutableMap()
                                        if (v == list.enabled) overrides.remove(list.id) else overrides[list.id] = v
                                        s.copy(listOverrides = overrides)
                                    }
                                },
                                subtitle = if (list.enabled) "Default" else null,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TorScreen(check: CheckState?, onCheck: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val store = VpnProfiles.get(context)
    val tor by store.tor.collectAsState()
    val status by TorController.status.collectAsState()
    ListScreen(title = "Tor", largeTitle = false, onBack = onBack) {
        item(key = "status") {
            ListSection(
                footer = "Tor is free and run by volunteers. It hides your IP address but is slower than a VPN, and some sites ask for captchas.",
            ) {
                row(16.dp) {
                    Column {
                        ListRow(
                            "Status",
                            value = when (status.phase) {
                                TorController.Phase.OFF -> "Starts when a Tor app opens"
                                TorController.Phase.STARTING -> "Connecting · ${status.progress}%"
                                TorController.Phase.ON -> "Connected"
                                TorController.Phase.ERROR -> status.summary.ifBlank { "Error" }
                            },
                        )
                        if (status.phase == TorController.Phase.STARTING) {
                            ProgressBar(status.progress / 100f, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp))
                        }
                    }
                }
                row(16.dp) { CheckRow(check, onCheck) }
            }
        }
        item(key = "countries") {
            SectionGap()
            ListSection(
                header = "Default Exit Country",
                footer = "Tor apps leave the Tor network in this country unless you pick another in an app’s settings, so different apps can appear in different countries. Countries with few relays are slower.",
            ) {
                VpnProfiles.TOR_COUNTRIES.forEach { (code, label) ->
                    row(16.dp) {
                        ChoiceRow(
                            if (code.isEmpty()) label else "${flag(code)}  $label",
                            selected = tor.exitCountry == code,
                            onClick = {
                                val next = TorSettings(exitCountry = code)
                                store.setTor(next)
                                TorController.applySettings(context, next)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileScreen(id: String, check: CheckState?, onCheck: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val store = VpnProfiles.get(context)
    val profiles by store.profiles.collectAsState()
    val profile = profiles.firstOrNull { it.id == id }
    var confirm by remember { mutableStateOf(false) }
    ListScreen(title = profile?.name ?: "VPN", largeTitle = false, onBack = onBack) {
        if (profile == null) return@ListScreen
        item(key = "info") {
            ListSection {
                row(16.dp) { ListRow("Type", value = profile.typeLabel) }
                row(16.dp) { ListRow(if (profile.type == VpnType.WIREGUARD) "Endpoint" else "Address", value = profile.summary) }
            }
        }
        item(key = "check") {
            SectionGap()
            ListSection(footer = "Connects through this VPN and shows the IP address and country that sites see.") {
                row(16.dp) { CheckRow(check, onCheck) }
            }
        }
        item(key = "delete") {
            SectionGap()
            ListSection(footer = "Apps using this VPN won’t load until you choose another one for them.") {
                row(16.dp) { ActionRow("Delete VPN", { confirm = true }, destructive = true) }
            }
        }
    }
    Overlay {
        val backdrop = rememberSnapshotBackdrop(confirm)
        ConfirmationSheet(
            visible = confirm,
            backdrop = backdrop,
            title = "Delete “${profile?.name.orEmpty()}”?",
            confirmTitle = "Delete VPN",
            onConfirm = {
                confirm = false
                store.remove(id)
                onBack()
            },
            onCancel = { confirm = false },
        )
    }
}

@Composable
private fun CheckRow(check: CheckState?, onCheck: () -> Unit) {
    ListRow(
        title = "Check Connection",
        titleColor = AppColors.Accent,
        subtitle = (check as? CheckState.Done)?.text,
        enabled = check != CheckState.Running,
        onClick = onCheck,
        trailing = when (check) {
            CheckState.Running -> { { CircularProgressIndicator(color = AppColors.SecondaryLabel, strokeWidth = 2.dp, modifier = Modifier.size(18.dp)) } }
            is CheckState.Done -> { { Text(if (check.ok) "OK" else "Failed", style = AppType.Subhead, color = if (check.ok) AppColors.Success else AppColors.Destructive) } }
            null -> null
        },
    )
}

@Composable
private fun WireGuardForm(initialName: String, initialText: String, onCancel: () -> Unit, onSave: (String, String) -> Unit) {
    var name by remember { mutableStateOf(initialName) }
    var text by remember { mutableStateOf(initialText) }
    var error by remember { mutableStateOf<String?>(null) }
    SheetToolbar(
        "WireGuard",
        leading = { ToolbarTextButton("Cancel", onCancel) },
        trailing = {
            ToolbarTextButton("Save", {
                val problem = VpnManager.validateWireGuard(text)
                if (problem != null) error = problem else onSave(name, text)
            }, prominent = true, enabled = text.isNotBlank())
        },
    )
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.Edge)
            .navigationBarsPadding(),
    ) {
        Spacer(Modifier.height(8.dp))
        FieldLabel("Name")
        AppTextField(name, { name = it }, placeholder = "e.g. Proton NL", background = AppColors.Cell)
        Spacer(Modifier.height(20.dp))
        FieldLabel("Configuration")
        AppTextField(
            text, { text = it; error = null },
            placeholder = "[Interface]\nPrivateKey = …\nAddress = …\n\n[Peer]\nPublicKey = …\nEndpoint = …",
            singleLine = false,
            textStyle = AppType.Footnote.copy(fontFamily = FontFamily.Monospace),
            background = AppColors.Cell,
            modifier = Modifier.heightIn(min = 220.dp),
        )
        if (error != null) {
            Text(error.orEmpty(), style = AppType.Footnote, color = AppColors.Destructive, modifier = Modifier.padding(top = 8.dp, start = 4.dp))
        }
        Text(
            "Paste the contents of a .conf file from your VPN provider or server. Keys stay on this device.",
            style = AppType.Footnote, color = AppColors.SecondaryLabel, modifier = Modifier.padding(top = 10.dp, start = 4.dp, bottom = 24.dp),
        )
    }
}

@Composable
private fun ProxyForm(onCancel: () -> Unit, onSave: (String, VpnType, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(VpnType.SOCKS5) }
    var host by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    fun save() {
        val address = host.trim().removePrefix("socks5://").removePrefix("http://")
        val port = address.substringAfterLast(':', "").toIntOrNull()
        if (!address.contains(':') || port == null || port !in 1..65535) {
            error = "Enter the proxy as host:port, for example 127.0.0.1:9050"
            return
        }
        val spec = if (user.isNotBlank()) "${user.trim()}:$pass@$address" else address
        onSave(name.ifBlank { address }, type, spec)
    }
    SheetToolbar(
        "Add Proxy",
        leading = { ToolbarTextButton("Cancel", onCancel) },
        trailing = { ToolbarTextButton("Save", ::save, prominent = true, enabled = host.isNotBlank()) },
    )
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.Edge)
            .navigationBarsPadding(),
    ) {
        Spacer(Modifier.height(8.dp))
        SegmentedControl(listOf(VpnType.SOCKS5 to "SOCKS5", VpnType.HTTP to "HTTP"), type, { type = it })
        Spacer(Modifier.height(20.dp))
        FieldLabel("Server")
        AppTextField(host, { host = it; error = null }, placeholder = "host:port", background = AppColors.Cell,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false))
        if (error != null) Text(error.orEmpty(), style = AppType.Footnote, color = AppColors.Destructive, modifier = Modifier.padding(top = 8.dp, start = 4.dp))
        Spacer(Modifier.height(20.dp))
        FieldLabel("Sign-in (optional)")
        AppTextField(user, { user = it }, placeholder = "Username", background = AppColors.Cell)
        Spacer(Modifier.height(8.dp))
        AppTextField(pass, { pass = it }, placeholder = "Password", background = AppColors.Cell,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
        Spacer(Modifier.height(20.dp))
        FieldLabel("Name")
        AppTextField(name, { name = it }, placeholder = "Optional", background = AppColors.Cell)
        Text(
            "Works with Orbot (127.0.0.1:9050), Shadowsocks and other proxy apps, or your own server.",
            style = AppType.Footnote, color = AppColors.SecondaryLabel, modifier = Modifier.padding(top = 10.dp, start = 4.dp, bottom = 24.dp),
        )
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, style = AppType.SectionHeader, color = AppColors.SecondaryLabel, modifier = Modifier.padding(start = 4.dp, bottom = 7.dp))
}

fun formatCount(n: Int): String = when {
    n >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", n / 1_000_000f)
    n >= 1_000 -> "${n / 1000}k"
    else -> n.toString()
}

fun flag(code: String): String =
    code.uppercase().map { Character.toChars(0x1F1E6 + (it - 'A')).concatToString() }.joinToString("")

private fun countryName(code: String): String =
    runCatching { java.util.Locale("", code).displayCountry }.getOrNull()?.takeIf { it.isNotBlank() } ?: code

@Suppress("unused")
private fun profileKeep(p: VpnProfile) = p.id
