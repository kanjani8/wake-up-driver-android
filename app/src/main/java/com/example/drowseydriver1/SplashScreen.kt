package com.example.drowseydriver1

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
fun SplashScreen(
    onDone: () -> Unit,
    durationMs: Long = 1000L,
) {
    val isDark = isSystemInDarkTheme()
    val bgResId  = if (isDark) R.drawable.flash_dark else R.drawable.flash_light
    val logoResId  = if (isDark) R.drawable.logo_dark else R.drawable.logo_light

    LaunchedEffect(Unit) {
        delay(durationMs)
        onDone()
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {

        //Background
        Image(
            painter = painterResource(bgResId),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds
        )
        //Logo
        Image(
            painter = painterResource(logoResId),
            contentDescription = null,
            modifier = Modifier
                .align(Alignment.Center)
                .size(400.dp),
            contentScale = ContentScale.Fit
        )
    }
}
