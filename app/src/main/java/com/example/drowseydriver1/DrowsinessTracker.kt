package com.example.drowseydriver1

import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.view.TransformExperimental
import com.example.drowseydriver1.DrowsinessTracker.DrowsinessThresholds.DROWSY_EYES_CLOSED_MS
import com.example.drowseydriver1.DrowsinessTracker.DrowsinessThresholds.SLEEP_EYES_CLOSED_MS
import com.example.drowseydriver1.DrowsinessTracker.DrowsinessThresholds.YAWN_MONITOR_MS


data class DrowsinessState(
    val label: UserState = UserState.AWAKE,
    val eyesClosedMs: Long = 0L,
    val yawnsPer3Min: Int = 0,
    val drowsinessPercent: Float = 0.0f,
    val isSleeping: Boolean = false
)

class DrowsinessTracker{

    private var state = DrowsinessState()

    private var eyeClosedStartTime = 0L
    private var lastEyeCheckMs = 0L


    private var isEyeOpened = true
    private var isMouthOpened = false


    private var lastMouthOpenMs = 0L // classify YAWN or not by time. 3.5 seconds duration(Average 4-7sec)
    private var lastYawnTimeList = mutableListOf<Long>()
    object DrowsinessThresholds {

        // Eyes
        const val DROWSY_EYES_CLOSED_MS = 600L
        const val SLEEP_EYES_CLOSED_MS = 1500L

        // Mouth
        const val YAWN_MIN_TIME_MS = 3500L  // classify YAWN or not by 3.5 seconds duration(Average 4-7sec)
        const val YAWN_MONITOR_MS = 180_000L // Monitor for 3 minutes from first yawn
    }


    @OptIn(TransformExperimental::class)
    fun trackEyes(status: CameraAnalyzer.FaceState): DrowsinessState {

        val now = SystemClock.elapsedRealtime()
        if (lastEyeCheckMs == 0L) {
            lastEyeCheckMs = now
        }

        val deltaMs = now - lastEyeCheckMs
        lastEyeCheckMs = now

        if(status == CameraAnalyzer.EyeState.CLOSED){
            if(isEyeOpened){
                isEyeOpened = false;
                eyeClosedStartTime = now
                lastEyeCheckMs = now
            }
            else{
                val totalClosedMs = now - eyeClosedStartTime

                // the time spent after the last classification (for calculating percentage)
                val deltaMs = now - lastEyeCheckMs
                lastEyeCheckMs = now

                // get a new drowsiness percentage
                val addedScore = 0.6f * (deltaMs / 1000.0f)
                var newPercent = state.drowsinessPercent + addedScore

                if (totalClosedMs > DROWSY_EYES_CLOSED_MS && newPercent <= 50.0f) {
                    newPercent = 60.0f
                }

                var newIsSleeping = state.isSleeping

                if (totalClosedMs > SLEEP_EYES_CLOSED_MS) {
                    newPercent = 90.0f
                    newIsSleeping = true
                }

                state = state.copy(
                    eyesClosedMs = totalClosedMs,
                    drowsinessPercent = newPercent.coerceIn(0f, 100f),
                    isSleeping = newIsSleeping
                )

            }


        } else if(status == CameraAnalyzer.EyeState.OPEN){
            isEyeOpened = true
            val reducedScore = 5.0f * (deltaMs / 1000.0f)
            state = state.copy(
                isSleeping = false,
                eyesClosedMs = 0,
                drowsinessPercent = (state.drowsinessPercent - reducedScore).coerceIn(0f, 100f)
            )
        }

        Log.d("DrowsinessTrackerResult", "state:  ${state}")
        state = determineLabel(state)
        return state
    }

    @OptIn(TransformExperimental::class)
    fun trackMouth(status: CameraAnalyzer.FaceState): DrowsinessState {
        Log.d("DrowsinessTrackerResult", "status:  ${status}")
        val now = SystemClock.elapsedRealtime()

        if(status == CameraAnalyzer.MouthState.YAWN){
            if(!isMouthOpened){
                isMouthOpened = true
                if(lastMouthOpenMs == 0L){
                    lastMouthOpenMs = now
                }
            }else{
                val mouthOpenPeriod = now - lastMouthOpenMs
                if(mouthOpenPeriod > DrowsinessThresholds.YAWN_MIN_TIME_MS){

                    lastYawnTimeList.removeAll { pastYawnTime ->
                        (now - pastYawnTime) > YAWN_MONITOR_MS
                    }

                    lastYawnTimeList.add(now)
                    val addedPercent = (state.drowsinessPercent + 5.0f).coerceIn(0f, 100f)
                    lastMouthOpenMs = now
                    state = state.copy(
                        yawnsPer3Min = lastYawnTimeList.size,
                        drowsinessPercent = addedPercent)
                }
            }

        }else if(status == CameraAnalyzer.MouthState.NO_YAWN){
            isMouthOpened = false
            lastMouthOpenMs = 0L
        }

        state = determineLabel(state)
        return state
    }

    fun determineLabel(state: DrowsinessState): DrowsinessState{
        val  newLabel = when{
            state.isSleeping -> UserState.SLEEP
            state.drowsinessPercent >= 50.0f -> UserState.DROWSY
            else -> UserState.AWAKE
        }
        return state.copy(label = newLabel)
    }

}