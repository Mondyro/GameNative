package com.winlator.inputcontrols

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sqrt

class ControllerAxisSettingsTest {

    @Test
    fun testDefaultValues() {
        val settings = ControllerAxisSettings()
        // Left stick
        assertEquals(0.15f, settings.leftStickDeadzone, 0.001f)
        assertEquals(1.0f, settings.leftStickOuterDeadzone, 0.001f)
        assertEquals(1.0f, settings.leftStickSensitivity, 0.001f)
        assertEquals(1.0f, settings.leftStickCurve, 0.001f)

        // Right stick
        assertEquals(0.15f, settings.rightStickDeadzone, 0.001f)
        assertEquals(1.0f, settings.rightStickOuterDeadzone, 0.001f)
        assertEquals(1.0f, settings.rightStickSensitivity, 0.001f)
        assertEquals(1.0f, settings.rightStickCurve, 0.001f)

        // Triggers
        assertFalse(settings.leftTriggerHairTrigger)
        assertEquals(0.10f, settings.leftTriggerThreshold, 0.001f)
        assertEquals(0.02f, settings.leftTriggerDeadzone, 0.001f)

        assertFalse(settings.rightTriggerHairTrigger)
        assertEquals(0.10f, settings.rightTriggerThreshold, 0.001f)
        assertEquals(0.02f, settings.rightTriggerDeadzone, 0.001f)
    }

    @Test
    fun testPresetsTargeting() {
        val settings = ControllerAxisSettings()

        // Apply PRECISE to Right stick only
        settings.applyPreset(ControllerAxisSettings.Preset.PRECISE, ControllerAxisSettings.TargetStick.RIGHT)
        assertEquals(ControllerAxisSettings.LINEAR_DEADZONE, settings.leftStickDeadzone, 0.001f)
        assertEquals(ControllerAxisSettings.LINEAR_CURVE, settings.leftStickCurve, 0.001f)
        assertEquals(ControllerAxisSettings.PRECISE_DEADZONE, settings.rightStickDeadzone, 0.001f)
        assertEquals(ControllerAxisSettings.PRECISE_CURVE, settings.rightStickCurve, 0.001f)

        // Apply AGGRESSIVE to Left stick only
        settings.applyPreset(ControllerAxisSettings.Preset.AGGRESSIVE, ControllerAxisSettings.TargetStick.LEFT)
        assertEquals(ControllerAxisSettings.AGGRESSIVE_DEADZONE, settings.leftStickDeadzone, 0.001f)
        assertEquals(ControllerAxisSettings.PRECISE_DEADZONE, settings.rightStickDeadzone, 0.001f)

        // Apply LINEAR to BOTH
        settings.applyPreset(ControllerAxisSettings.Preset.LINEAR, ControllerAxisSettings.TargetStick.BOTH)
        assertEquals(ControllerAxisSettings.LINEAR_DEADZONE, settings.leftStickDeadzone, 0.001f)
        assertEquals(ControllerAxisSettings.LINEAR_DEADZONE, settings.rightStickDeadzone, 0.001f)
    }

    @Test
    fun testJsonSerializationRoundTrip() {
        val original = ControllerAxisSettings(
            leftStickDeadzone = 0.10f,
            leftStickOuterDeadzone = 0.95f,
            leftStickSensitivity = 1.0f,
            leftStickCurve = 1.0f,
            rightStickDeadzone = 0.08f,
            rightStickOuterDeadzone = 0.90f,
            rightStickSensitivity = 2.5f,
            rightStickCurve = 1.6f,
            leftTriggerHairTrigger = false,
            leftTriggerThreshold = 0.10f,
            leftTriggerDeadzone = 0.02f,
            rightTriggerHairTrigger = true,
            rightTriggerThreshold = 0.05f,
            rightTriggerDeadzone = 0.01f
        )
        val json = original.toJSONObject()
        val deserialized = ControllerAxisSettings.fromJSONObject(json)

        assertEquals(original.leftStickDeadzone, deserialized.leftStickDeadzone, 0.001f)
        assertEquals(original.leftStickOuterDeadzone, deserialized.leftStickOuterDeadzone, 0.001f)
        assertEquals(original.rightStickOuterDeadzone, deserialized.rightStickOuterDeadzone, 0.001f)
        assertEquals(original.rightStickSensitivity, deserialized.rightStickSensitivity, 0.001f)
        assertEquals(original.rightStickCurve, deserialized.rightStickCurve, 0.001f)
        assertFalse(deserialized.leftTriggerHairTrigger)
        assertTrue(deserialized.rightTriggerHairTrigger)
        assertEquals(0.05f, deserialized.rightTriggerThreshold, 0.001f)
    }

    @Test
    fun testLegacyJsonFallback() {
        val legacyJson = JSONObject().apply {
            put("stickDeadzone", 0.18)
            put("stickSensitivity", 1.25)
            put("stickCurve", 1.40)
            put("triggerHairTrigger", true)
            put("triggerThreshold", 0.07)
            put("triggerDeadzone", 0.03)
        }
        val settings = ControllerAxisSettings.fromJSONObject(legacyJson)

        assertEquals(0.18f, settings.leftStickDeadzone, 0.001f)
        assertEquals(0.18f, settings.rightStickDeadzone, 0.001f)
        assertEquals(1.25f, settings.leftStickSensitivity, 0.001f)
        assertEquals(1.25f, settings.rightStickSensitivity, 0.001f)
        assertTrue(settings.leftTriggerHairTrigger)
        assertTrue(settings.rightTriggerHairTrigger)
    }

    @Test
    fun test2DRadialVectorMath() {
        fun calculateRadial(rawX: Float, rawY: Float, innerDz: Float, outerDz: Float, sens: Float, curve: Float): Pair<Float, Float> {
            val magnitude = sqrt((rawX * rawX + rawY * rawY).toDouble()).toFloat()
            if (magnitude <= innerDz || magnitude == 0f) return Pair(0f, 0f)
            val denom = (outerDz - innerDz).coerceAtLeast(0.01f)
            val normalized = ((magnitude - innerDz) / denom).coerceIn(0f, 1f)
            val curved = normalized.pow(curve)
            val scaled = (curved * sens).coerceIn(0f, 1f)
            val factor = scaled / magnitude
            return Pair((rawX * factor).coerceIn(-1f, 1f), (rawY * factor).coerceIn(-1f, 1f))
        }

        // Inside deadzone -> 0,0
        val (zeroX, zeroY) = calculateRadial(0.05f, 0.05f, 0.15f, 1.0f, 1.0f, 1.0f)
        assertEquals(0f, zeroX, 0.001f)
        assertEquals(0f, zeroY, 0.001f)

        // Diagonal motion maintains continuous vector angle
        val (diagX, diagY) = calculateRadial(0.6f, 0.8f, 0.15f, 1.0f, 1.0f, 1.0f)
        assertTrue(diagX > 0f)
        assertTrue(diagY > 0f)
        assertEquals(0.6f / 0.8f, diagX / diagY, 0.01f) // Aspect angle perfectly preserved

        // Reaches full 100% output at Outer Deadzone (e.g. 90% physical tilt)
        val (fullX, fullY) = calculateRadial(0.90f, 0f, 0.10f, 0.90f, 1.0f, 1.0f)
        assertEquals(1.0f, fullX, 0.001f)
    }

    @Test
    fun testGenrePresets() {
        val settings = ControllerAxisSettings()

        // FPS Shooter
        settings.applyGenrePreset(ControllerAxisSettings.GenrePreset.FPS_SHOOTER)
        assertEquals(0.10f, settings.leftStickDeadzone, 0.001f)
        assertEquals(1.60f, settings.rightStickCurve, 0.001f)
        assertTrue(settings.leftTriggerHairTrigger)
        assertTrue(settings.rightTriggerHairTrigger)

        // Racing / Driving
        settings.applyGenrePreset(ControllerAxisSettings.GenrePreset.RACING_DRIVING)
        assertEquals(0.06f, settings.leftStickDeadzone, 0.001f)
        assertEquals(1.40f, settings.leftStickCurve, 0.001f)
        assertFalse(settings.leftTriggerHairTrigger)
        assertFalse(settings.rightTriggerHairTrigger)
    }

    @Test
    fun testStickSpecificPresets() {
        val settings = ControllerAxisSettings()

        // Left stick - Smooth Steer
        settings.applyLeftStickPreset(ControllerAxisSettings.LeftStickPreset.SMOOTH_STEER)
        assertEquals(0.06f, settings.leftStickDeadzone, 0.001f)
        assertEquals(1.40f, settings.leftStickCurve, 0.001f)
        assertEquals(ControllerAxisSettings.LINEAR_DEADZONE, settings.rightStickDeadzone, 0.001f) // Right unaffected

        // Right stick - Precision Aim
        settings.applyRightStickPreset(ControllerAxisSettings.RightStickPreset.PRECISION_AIM)
        assertEquals(0.08f, settings.rightStickDeadzone, 0.001f)
        assertEquals(1.60f, settings.rightStickCurve, 0.001f)
        assertEquals(0.06f, settings.leftStickDeadzone, 0.001f) // Left still smooth steer
    }

    @Test
    fun testTriggerPresets() {
        val settings = ControllerAxisSettings()

        // Instant Hair (FPS)
        settings.applyTriggerPreset(ControllerAxisSettings.TriggerPreset.INSTANT_HAIR_FPS)
        assertTrue(settings.leftTriggerHairTrigger)
        assertTrue(settings.rightTriggerHairTrigger)
        assertEquals(0.08f, settings.leftTriggerThreshold, 0.001f)

        // Smooth Racing on L2 only
        settings.applyTriggerPreset(ControllerAxisSettings.TriggerPreset.SMOOTH_RACING, ControllerAxisSettings.TargetTrigger.LEFT)
        assertFalse(settings.leftTriggerHairTrigger)
        assertEquals(1.4f, settings.leftTriggerCurve, 0.001f)
        assertEquals(1.0f, settings.leftTriggerCutoff, 0.001f)
        assertTrue(settings.rightTriggerHairTrigger) // R2 untouched

        // Aggressive Throttle on R2 only
        settings.applyTriggerPreset(ControllerAxisSettings.TriggerPreset.AGGRESSIVE_THROTTLE, ControllerAxisSettings.TargetTrigger.RIGHT)
        assertFalse(settings.rightTriggerHairTrigger)
        assertEquals(0.90f, settings.rightTriggerCutoff, 0.001f)
        assertEquals(1.2f, settings.rightTriggerSensitivity, 0.001f)
        assertEquals(0.6f, settings.rightTriggerCurve, 0.001f)
    }

    @Test
    fun testAnalogTriggerMath() {
        fun processTrigger(raw: Float, dz: Float, cutoff: Float, curve: Float): Float {
            if (raw <= dz) return 0f
            val denom = (cutoff - dz).coerceAtLeast(0.01f)
            val norm = ((raw - dz) / denom).coerceIn(0f, 1f)
            return norm.pow(curve).coerceIn(0f, 1f)
        }

        // Inside deadzone -> 0
        assertEquals(0f, processTrigger(0.01f, 0.02f, 1.0f, 1.0f), 0.001f)

        // Linear midpoint
        assertEquals(0.5f, processTrigger(0.51f, 0.02f, 1.0f, 1.0f), 0.02f)

        // Beyond cutoff -> full 1.0 output
        assertEquals(1.0f, processTrigger(0.90f, 0.02f, 0.90f, 1.0f), 0.001f)
        assertEquals(1.0f, processTrigger(0.95f, 0.02f, 0.90f, 1.0f), 0.001f)
    }
}
