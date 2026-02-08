package com.example.drowseydriver1

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember

class SoundPlayer(
    private val soundPool: SoundPool,
    private val soundId: Int
) {
    fun play() {
        // leftVol, rightVol, priority, loop, rate
        soundPool.play(soundId, 1f, 1f, 1, 0, 1f)
    }

    fun release() = soundPool.release()
}

@Composable
fun rememberSoundPlayer(context: Context): SoundPlayer {
    val attrs = remember {
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    }

    val soundPool = remember {
        SoundPool.Builder()
            .setMaxStreams(1)
            .setAudioAttributes(attrs)
            .build()
    }

    val soundId = remember {
        soundPool.load(context, R.raw.alert_beep, 1)
    }

    val player = remember { SoundPlayer(soundPool, soundId) }

    DisposableEffect(Unit) {
        onDispose { player.release() }
    }

    return player
}
