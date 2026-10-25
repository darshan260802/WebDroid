package com.webdroid.app.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material.icons.rounded.VpnLock
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.webdroid.app.ui.components.ClayButton
import com.webdroid.app.ui.components.ClayButtonStyle
import com.webdroid.app.ui.components.ClaySegmented
import com.webdroid.app.ui.components.ClayTextField
import com.webdroid.app.ui.components.HairlineDivider
import com.webdroid.app.ui.theme.Clay
import com.webdroid.app.ui.theme.ClayShapes
import com.webdroid.app.ui.theme.ClayType
import com.webdroid.app.vpn.TorController
import com.webdroid.app.vpn.TorSettings
import com.webdroid.app.vpn.VpnManager
import com.webdroid.app.vpn.VpnProfile
import com.webdroid.app.vpn.VpnProfiles
import com.webdroid.app.vpn.VpnType
import kotlinx.coroutines.launch

private sealed interface CheckState {
    data object Running : CheckState
    data class Done(val text: String, val ok: Boolean) : CheckState
}

/** Settings → VPN: Tor's exit country, WireGuard configs and proxies. */
@Composable
fun VpnSettingsSection() {
    val context = LocalContext.current
    val store = VpnProfiles.get(context)
    val profiles by store.profiles.collectAsState()
    val tor by store.tor.collectAsState()
    val torStatus by TorController.status.collectAsState()
    val scope = rememberCoroutineScope()
    val checks = remember { mutableStateMapOf<String, CheckState>() }
    var dialog by remember { mutableStateOf<Dialog?>(null) }

    fun runCheck(id: String) {
        checks[id] = CheckState.Running
        scope.launch {
            val result = VpnManager.check(context, id)
            checks[id] = if (result.error != null) {
                CheckState.Done(result.error, ok = false)
            } else {
                CheckState.Done(listOfNotNull(result.ip?.let { "Exit IP $it" }, result.country).joinToString(" · "), ok = true)
            }
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
        dialog = Dialog.WireGuard(name, text)
    }

    if (!VpnManager.isSupported) {
        Text(
            "Update Android System WebView to use per-site VPN; this version can't route sites through a proxy.",
            style = ClayType.BodySm, color = Clay.Error, modifier = Modifier.padding(bottom = 10.dp),
        )
    }

    // --- Tor ---------------------------------------------------------------
    Column(Modifier.fillMaxWidth().clip(ClayShapes.Xl).background(Clay.Lavender).padding(20.dp).animateContentSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(ClayShapes.Lg).background(Clay.Cream), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.VpnLock, null, tint = Clay.Black, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Tor network", style = ClayType.TitleMd, color = Clay.Black)
                val status = when (torStatus.phase) {
                    TorController.Phase.OFF -> "Built in · starts when a Tor app opens"
                    TorController.Phase.STARTING -> "Connecting · ${torStatus.progress}%"
                    TorController.Phase.ON -> "Connected"
                    TorController.Phase.ERROR -> torStatus.summary
                }
                Text(status, style = ClayType.Caption, color = Clay.Black.copy(alpha = 0.7f))
            }
        }
        Spacer(Modifier.height(14.dp))
        Text("Exit country", style = ClayType.TitleSm, color = Clay.Black)
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            VpnProfiles.TOR_COUNTRIES.forEach { (code, label) ->
                val selected = tor.exitCountry == code
                Text(
                    if (code.isEmpty()) label else "${flag(code)} ${code.uppercase()}",
                    style = ClayType.Caption,
                    color = if (selected) Clay.White else Clay.Black,
                    modifier = Modifier
                        .clip(ClayShapes.Pill)
                        .background(if (selected) Clay.Black else Clay.Cream.copy(alpha = 0.7f))
                        .clickable {
                            val next = TorSettings(exitCountry = code)
                            store.setTor(next)
                            TorController.applySettings(context, next)
                            checks.remove(VpnProfiles.TOR_ID)
                        }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                )
            }
        }
        Text(
            "Free and anonymous, run by volunteers. Slower than a VPN; some sites ask for captchas.",
            style = ClayType.Caption, color = Clay.Black.copy(alpha = 0.7f), modifier = Modifier.padding(top = 10.dp),
        )
        Spacer(Modifier.height(12.dp))
        CheckRow(checks[VpnProfiles.TOR_ID], dark = true) { runCheck(VpnProfiles.TOR_ID) }
    }

    // --- Profiles ------------------------------------------------------------
    Spacer(Modifier.height(14.dp))
    Column(Modifier.fillMaxWidth().clip(ClayShapes.Lg).background(Clay.SurfaceSoft).padding(16.dp).animateContentSize()) {
        Text("Your VPNs", style = ClayType.TitleSm, color = Clay.Ink)
        Text(
            "WireGuard configs (Proton VPN's free plan, your own server…) and SOCKS5/HTTP proxies such as Orbot.",
            style = ClayType.BodySm, color = Clay.Muted, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
        )
        profiles.forEachIndexed { i, profile ->
            if (i > 0) HairlineDivider()
            ProfileRow(profile, checks[profile.id], onCheck = { runCheck(profile.id) }, onDelete = { dialog = Dialog.ConfirmDelete(profile) })
        }
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ClayButton("Import .conf", onClick = { importFile.launch(arrayOf("*/*")) }, icon = Icons.Rounded.FileOpen, style = ClayButtonStyle.Secondary, height = 44.dp)
            ClayButton("Paste config", onClick = { dialog = Dialog.WireGuard("WireGuard", "") }, icon = Icons.Rounded.ContentPaste, style = ClayButtonStyle.Secondary, height = 44.dp)
            ClayButton("Add proxy", onClick = { dialog = Dialog.Proxy }, icon = Icons.Rounded.WifiTethering, style = ClayButtonStyle.Secondary, height = 44.dp)
        }
        Text(
            "Get free WireGuard configs",
            style = ClayType.NavLink, color = Clay.Ink,
            modifier = Modifier
                .padding(top = 12.dp)
                .clip(ClayShapes.Pill)
                .clickable {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://account.protonvpn.com/downloads")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
                .padding(vertical = 4.dp),
        )
    }

    when (val d = dialog) {
        is Dialog.WireGuard -> WireGuardDialog(d.name, d.text, onDismiss = { dialog = null }) { name, text ->
            store.add(name, VpnType.WIREGUARD, text)
            dialog = null
        }
        Dialog.Proxy -> ProxyDialog(onDismiss = { dialog = null }) { name, type, spec ->
            store.add(name, type, spec)
            dialog = null
        }
        is Dialog.ConfirmDelete -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Delete ${d.profile.name}?", style = ClayType.TitleMd) },
            text = { Text("Apps using it will stop loading until you pick another VPN for them.", style = ClayType.BodySm) },
            confirmButton = {
                TextButton(onClick = {
                    store.remove(d.profile.id)
                    dialog = null
                }) { Text("Delete", color = Clay.Error) }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel", color = Clay.Ink) } },
            containerColor = Clay.Canvas,
        )
        null -> Unit
    }
}

private sealed interface Dialog {
    data class WireGuard(val name: String, val text: String) : Dialog
    data object Proxy : Dialog
    data class ConfirmDelete(val profile: VpnProfile) : Dialog
}

private fun flag(code: String): String =
    code.uppercase().map { Character.toChars(0x1F1E6 + (it - 'A')).concatToString() }.joinToString("")

@Composable
private fun CheckRow(state: CheckState?, dark: Boolean, onCheck: () -> Unit) {
    val ink = if (dark) Clay.Black else Clay.Ink
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .clip(ClayShapes.Pill)
                .background(if (dark) Clay.Cream else Clay.SurfaceCard)
                .clickable(enabled = state != CheckState.Running, onClick = onCheck)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state == CheckState.Running) {
                CircularProgressIndicator(color = ink, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
            } else {
                Icon(Icons.Rounded.NetworkCheck, null, tint = ink, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(if (state == CheckState.Running) "Checking…" else "Check connection", style = ClayType.Caption, color = ink)
        }
        if (state is CheckState.Done) {
            Spacer(Modifier.width(10.dp))
            Text(
                state.text,
                style = ClayType.Caption,
                color = if (state.ok) ink else Clay.Error,
                maxLines = 2,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ProfileRow(profile: VpnProfile, check: CheckState?, onCheck: () -> Unit, onDelete: () -> Unit) {
    Column(Modifier.padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(ClayShapes.Md).background(Clay.SurfaceCard), contentAlignment = Alignment.Center) {
                Icon(if (profile.type == VpnType.WIREGUARD) Icons.Rounded.VpnKey else Icons.Rounded.WifiTethering, null, tint = Clay.Ink, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(profile.name, style = ClayType.TitleSm, color = Clay.Ink, maxLines = 1)
                Text("${profile.typeLabel} · ${profile.summary}", style = ClayType.Caption, color = Clay.Muted, maxLines = 1)
            }
            Box(Modifier.size(40.dp).clip(ClayShapes.Pill).clickable(onClick = onDelete), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Delete, "Delete", tint = Clay.Muted, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        CheckRow(check, dark = false, onCheck = onCheck)
    }
}

@Composable
private fun WireGuardDialog(initialName: String, initialText: String, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var name by remember { mutableStateOf(initialName) }
    var text by remember { mutableStateOf(initialText) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("WireGuard config", style = ClayType.TitleMd) },
        text = {
            Column {
                ClayTextField(name, { name = it }, placeholder = "Name, e.g. Proton NL")
                Spacer(Modifier.height(10.dp))
                ClayTextField(
                    text, { text = it; error = null },
                    placeholder = "[Interface]\nPrivateKey = …\nAddress = …\n\n[Peer]\nPublicKey = …\nEndpoint = …",
                    singleLine = false,
                    textStyle = ClayType.Caption.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.heightIn(min = 160.dp, max = 260.dp),
                )
                error?.let { Text(it, style = ClayType.Caption, color = Clay.Error, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val problem = VpnManager.validateWireGuard(text)
                if (problem != null) error = problem else onSave(name, text)
            }) { Text("Save", color = Clay.Ink) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Clay.Muted) } },
        containerColor = Clay.Canvas,
    )
}

@Composable
private fun ProxyDialog(onDismiss: () -> Unit, onSave: (String, VpnType, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(VpnType.SOCKS5) }
    var host by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add proxy", style = ClayType.TitleMd) },
        text = {
            Column {
                ClaySegmented(
                    options = listOf(VpnType.SOCKS5 to "SOCKS5", VpnType.HTTP to "HTTP"),
                    selected = type,
                    onSelect = { type = it },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                ClayTextField(host, { host = it; error = null }, placeholder = "host:port, e.g. 127.0.0.1:9050",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                Spacer(Modifier.height(8.dp))
                ClayTextField(user, { user = it }, placeholder = "Username (optional)")
                Spacer(Modifier.height(8.dp))
                ClayTextField(pass, { pass = it }, placeholder = "Password (optional)",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                Spacer(Modifier.height(8.dp))
                ClayTextField(name, { name = it }, placeholder = "Name (optional)")
                error?.let { Text(it, style = ClayType.Caption, color = Clay.Error, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val address = host.trim().removePrefix("socks5://").removePrefix("http://")
                val port = address.substringAfterLast(':', "").toIntOrNull()
                if (!address.contains(':') || port == null || port !in 1..65535) {
                    error = "Enter the proxy as host:port"
                } else {
                    val spec = if (user.isNotBlank()) "${user.trim()}:${pass}@$address" else address
                    onSave(name.ifBlank { address }, type, spec)
                }
            }) { Text("Save", color = Clay.Ink) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Clay.Muted) } },
        containerColor = Clay.Canvas,
    )
}
