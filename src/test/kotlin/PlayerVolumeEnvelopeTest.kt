package com.alananasss.kittytune

import com.alananasss.kittytune.audio.VolumeCurve
import com.alananasss.kittytune.media.Player
import org.junit.Test
import kotlin.test.assertEquals

class PlayerVolumeEnvelopeTest {
    @Test
    fun sleepTimerTicksPreserveNewMasterAndCancellationRestoresOnlyAttenuation() {
        val player = Player()
        try {
            player.volume = 0.8f
            player.sleepTimerGain = 0.5f
            player.volume = 0.3f
            assertEquals(VolumeCurve.sliderToAmplitude(0.3f) * 0.5f, player.activeEngine.getVolume(), 0.0001f)
            player.sleepTimerGain = 0.2f
            assertEquals(0.3f, player.volume)
            assertEquals(VolumeCurve.sliderToAmplitude(0.3f) * 0.2f, player.activeEngine.getVolume(), 0.0001f)
            player.sleepTimerGain = 1f
            assertEquals(0.3f, player.volume)
            assertEquals(VolumeCurve.sliderToAmplitude(0.3f), player.activeEngine.getVolume(), 0.0001f)
        } finally {
            player.release()
        }
    }

    @Test
    fun muteSurvivesTimerTicksAndExpiryReset() {
        val player = Player()
        try {
            player.sleepTimerGain = 0.5f
            player.volume = 0f
            player.sleepTimerGain = 0.2f
            assertEquals(0f, player.activeEngine.getVolume())
            player.sleepTimerGain = 0f
            player.pause()
            player.sleepTimerGain = 1f
            assertEquals(0f, player.volume)
            assertEquals(0f, player.activeEngine.getVolume())
        } finally {
            player.release()
        }
    }

    @Test
    fun sleepTimerMultipliesTrimAndCrossfadeEnvelopes() {
        val player = Player()
        try {
            player.volume = 0.7f
            player.setFadeGains(0.5f, 0.5f)
            player.trimGain = 0.4f
            player.sleepTimerGain = 0.3f
            assertEquals(VolumeCurve.sliderToAmplitude(0.7f) * 0.5f * 0.4f * 0.3f, player.activeEngine.getVolume(), 0.0001f)
            player.sleepTimerGain = 1f
            assertEquals(VolumeCurve.sliderToAmplitude(0.7f) * 0.5f * 0.4f, player.activeEngine.getVolume(), 0.0001f)
        } finally {
            player.release()
        }
    }

    @Test
    fun invalidSleepGainNeverReachesTheAudioOutput() {
        val player = Player()
        try {
            for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f)) {
                player.sleepTimerGain = invalid
                assertEquals(0f, player.activeEngine.getVolume())
            }
            player.sleepTimerGain = 2f
            assertEquals(1f, player.sleepTimerGain)
        } finally {
            player.release()
        }
    }

    @Test
    fun sliderChangePreservesIncomingFadeGainImmediately() {
        val player = Player()
        try {
            player.setFadeGains(0.4f, 0.8f)
            player.volume = 0.6f
            assertEquals(VolumeCurve.sliderToAmplitude(0.6f) * 0.4f, player.activeEngine.getVolume(), 0.0001f)
            player.setFadeGains(0.7f, 0.5f)
            assertEquals(VolumeCurve.sliderToAmplitude(0.6f) * 0.7f, player.activeEngine.getVolume(), 0.0001f)
        } finally {
            player.release()
        }
    }

    @Test
    fun mutePersistsAcrossSubsequentFadeTicks() {
        val player = Player()
        try {
            player.setFadeGains(0.5f, 0.5f)
            player.volume = 0f
            player.setFadeGains(0.9f, 0.1f)
            assertEquals(0f, player.activeEngine.getVolume())
        } finally {
            player.release()
        }
    }

    @Test
    fun invalidSliderValueCannotReachOutputLine() {
        val player = Player()
        try {
            player.volume = Float.NaN
            assertEquals(0f, player.activeEngine.getVolume())
            player.volume = 2f
            assertEquals(1f, player.activeEngine.getVolume())
        } finally {
            player.release()
        }
    }

    @Test
    fun masterChangeDuringTrimFadeSurvivesRemainingTicksAndCleanup() {
        val player = Player()
        try {
            player.volume = 0.8f
            player.trimGain = 0.5f
            player.volume = 0.4f
            player.trimGain = 0.25f
            assertEquals(0.4f, player.volume)
            assertEquals(VolumeCurve.sliderToAmplitude(0.4f) * 0.25f, player.activeEngine.getVolume(), 0.0001f)
            player.trimGain = 1f
            assertEquals(0.4f, player.volume)
            assertEquals(VolumeCurve.sliderToAmplitude(0.4f), player.activeEngine.getVolume(), 0.0001f)
        } finally {
            player.release()
        }
    }

    @Test
    fun trimAndCrossfadeMultiplyWithoutReplacingEachOther() {
        val player = Player()
        try {
            player.volume = 0.7f
            player.setFadeGains(0.5f, 0.5f)
            player.trimGain = 0.4f
            assertEquals(VolumeCurve.sliderToAmplitude(0.7f) * 0.5f * 0.4f, player.activeEngine.getVolume(), 0.0001f)
            player.trimGain = 1f
            assertEquals(VolumeCurve.sliderToAmplitude(0.7f) * 0.5f, player.activeEngine.getVolume(), 0.0001f)
        } finally {
            player.release()
        }
    }

    @Test
    fun unmutingDuringTrimFadeRestoresOnlyCurrentTrimGain() {
        val player = Player()
        try {
            player.trimGain = 0.5f
            player.volume = 0f
            player.trimGain = 0.7f
            assertEquals(0f, player.activeEngine.getVolume())
            player.volume = 0.6f
            assertEquals(VolumeCurve.sliderToAmplitude(0.6f) * 0.7f, player.activeEngine.getVolume(), 0.0001f)
        } finally {
            player.release()
        }
    }

}
