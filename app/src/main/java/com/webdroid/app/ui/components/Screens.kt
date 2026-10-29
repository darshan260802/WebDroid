package com.webdroid.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.webdroid.app.ui.glass.GlassBackdrop
import com.webdroid.app.ui.glass.glassSource
import com.webdroid.app.ui.glass.rememberGlassBackdrop
import com.webdroid.app.ui.theme.AppColors

/**
 * A scrolling grouped-list screen with floating glass chrome: a large title
 * that collapses into the top bar (tab roots), or an inline title with a back
 * button (pushed screens).
 */
@Composable
fun ListScreen(
    title: String,
    largeTitle: Boolean,
    onBack: (() -> Unit)? = null,
    bottomInset: Dp = TabBarClearance,
    state: LazyListState = rememberLazyListState(),
    trailing: @Composable RowScope.(GlassBackdrop) -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val backdrop = rememberGlassBackdrop()
    val collapse by rememberCollapse(state)
    val scrolled by remember(state) { derivedStateOf { state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > 4 } }
    TrackScrollAll(backdrop, state)
    val top = topBarHeight()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + bottomInset
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().glassSource(backdrop).background(AppColors.Grouped)) {
            LazyColumn(
                state = state,
                contentPadding = PaddingValues(top = if (largeTitle) top else top + 12.dp, bottom = bottom + 20.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (largeTitle) item(key = "large-title") { LargeTitle(title) }
                content()
            }
        }
        TopChrome(
            backdrop = backdrop,
            title = title,
            collapse = if (largeTitle) collapse else if (scrolled) 1f else 0f,
            alwaysShowTitle = !largeTitle,
            leading = { if (onBack != null) BackButton(backdrop, onBack) },
            trailing = { trailing(backdrop) },
        )
    }
}
