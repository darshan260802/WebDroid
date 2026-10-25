package com.webdroid.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material.icons.rounded.VpnLock
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.webdroid.app.ui.theme.Clay
import com.webdroid.app.ui.theme.ClayShapes
import com.webdroid.app.ui.theme.ClayType
import com.webdroid.app.vpn.VpnProfiles
import com.webdroid.app.vpn.VpnType

/**
 * Picks how one web app connects: directly, through Tor, or through a saved
 * WireGuard/proxy profile.
 */
@Composable
fun VpnPicker(selected: String?, onSelect: (String?) -> Unit, modifier: Modifier = Modifier) {
    val store = VpnProfiles.get(LocalContext.current)
    val profiles by store.profiles.collectAsState()
    val tor by store.tor.collectAsState()
    val haptics = rememberHaptics()
    Column(modifier) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            VpnChip("Direct", Icons.Rounded.Public, selected == null) { haptics.tick(); onSelect(null) }
            val torLabel = "Tor" + (tor.exitCountry.takeIf { it.isNotEmpty() }?.let { " · ${it.uppercase()}" } ?: "")
            VpnChip(torLabel, Icons.Rounded.VpnLock, selected == VpnProfiles.TOR_ID) { haptics.tick(); onSelect(VpnProfiles.TOR_ID) }
            profiles.forEach { p ->
                val icon = if (p.type == VpnType.WIREGUARD) Icons.Rounded.VpnKey else Icons.Rounded.WifiTethering
                VpnChip(p.name, icon, selected == p.id) { haptics.tick(); onSelect(p.id) }
            }
        }
        if (selected != null && selected != VpnProfiles.TOR_ID && profiles.none { it.id == selected }) {
            Text("This VPN profile was deleted. Pick another one.", style = ClayType.Caption, color = Clay.Error, modifier = Modifier.padding(top = 8.dp))
        }
        Text(
            if (profiles.isEmpty()) "Only this app's sites use the VPN. Add WireGuard configs or proxies in WebDroid's Settings → VPN."
            else "Only this app's sites use the VPN; other apps stay direct.",
            style = ClayType.Caption, color = Clay.Muted, modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun VpnChip(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    val background by animateColorAsState(if (selected) Clay.Primary else Clay.SurfaceCard, label = "vpn-chip")
    val content = if (selected) Clay.OnPrimary else Clay.Ink
    Row(
        Modifier
            .clip(ClayShapes.Pill)
            .background(background)
            .then(if (selected) Modifier else Modifier.border(1.dp, Color.Transparent, ClayShapes.Pill))
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = 16.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = content, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = ClayType.NavLink, color = content, maxLines = 1)
    }
}
