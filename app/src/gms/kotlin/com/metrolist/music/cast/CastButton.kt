package com.metrolist.music.cast

import androidx.appcompat.view.ContextThemeWrapper
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.framework.CastButtonFactory

@Composable
fun CastButton(modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            // Wrap the context in a theme that MediaRouteButton expects
            val themeContext = ContextThemeWrapper(context, androidx.appcompat.R.style.Theme_AppCompat)
            MediaRouteButton(themeContext).apply {
                CastButtonFactory.setUpMediaRouteButton(context, this)
                // Force visibility even if no Cast devices are immediately detected on the network
                setAlwaysVisible(true)
            }
        }
    )
}
