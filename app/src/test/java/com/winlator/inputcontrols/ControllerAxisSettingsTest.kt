package com.winlator.inputcontrols

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerAxisSettingsTest {

    @Test
    fun testDefaultValues() {
        val settings = ControllerAxisSettings()
        // Left stick
        assertEquals(0.05f, settings.leftStickDeadzone, 0.001f)
        assertEquals(0.00f, settings.leftStickAntiDeadzone, 0.001f)
        assertEquals(0.50f, settings.leftStickMidpoint, 0.001f)
        assertEquals(1.0f, settings.leftStickOuterDeadzone, 0.001f)
        assertEquals(1.0f, settings.leftStickSensitivity, 0.001f)

        // Right stick
        assertEquals(0.05f, settings.rightStickDeadzone, 0.001f)
        assertEquals(0.00f, settings.rightStickAntiDeadzone, 0.001f)
        assertEquals(0.50f, settings.rightStickMidpoint, 0.001f)
        assertEquals(1.0f, settings.rightStickOuterDeadzone, 0.001f)
        assertEquals(1.0f, settings.rightStickSensitivity, 0.001f)

        // Triggers
        assertFalse(settings.leftTriggerHairTrigger)
        assertEquals(0.10f, settings.leftTriggerThreshold, 0.001f)
        assertEquals(0.02f, settings.leftTriggerDeadzone, 0.001f)

        assertFalse(settings.rightTriggerHairTrigger)
        assertEquals(0.10f, settings.rightTriggerThreshold, 0.001f)
        assertEquals(0.02f, settings.rightTriggerDeadzone, 0.001f)
    }

    @Test
    fun testEvaluateStickCurveLinear() {
        // Linear mode: Midpoint = 0.50, AntiDeadzone = 0.0
        assertEquals(0.00f, ControllerAxisSettings.evaluateStickCurve(0.00f, 0.0f, 0.50f, 1.0f), 0.001f)
        assertEquals(0.25f, ControllerAxisSettings.evaluateStickCurve(0.25f, 0.0f, 0.50f, 1.0f), 0.001f)
        assertEquals(0.50f, ControllerAxisSettings.evaluateStickCurve(0.50f, 0.0f, 0.50f, 1.0f), 0.001f)
        assertEquals(0.75f, ControllerAxisSettings.evaluateStickCurve(0.75f, 0.0f, 0.50f, 1.0f), 0.001f)
        assertEquals(1.00f, ControllerAxisSettings.evaluateStickCurve(1.00f, 0.0f, 0.50f, 1.0f), 0.001f)
    }

    @Test
    fun testEvaluateStickCurveGentleAim() {
        // FPS Aim mode: Midpoint = 0.35, AntiDeadzone = 0.10
        // Small input 0.10 should not flatline at 0
        val outSmall = ControllerAxisSettings.evaluateStickCurve(0.10f, 0.10f, 0.35f, 1.0f)
        assertTrue(outSmall >= 0.10f) // Guaranteed above anti-deadzone floor

        // Midpoint 0.50 input should yield exact midpoint with anti-deadzone blend
        val outMid = ControllerAxisSettings.evaluateStickCurve(0.50f, 0.10f, 0.35f, 1.0f)
        assertEquals(0.10f + 0.90f * 0.35f, outMid, 0.01f)

        // Full deflection always reaches 100% saturation
        val outMax = ControllerAxisSettings.evaluateStickCurve(1.00f, 0.10f, 0.35f, 1.0f)
        assertEquals(1.00f, outMax, 0.001f)
    }

    @Test
    fun testEvaluateStickCurveAggressive() {
        // Aggressive mode: Midpoint = 0.65, AntiDeadzone = 0.15
        val outMid = ControllerAxisSettings.evaluateStickCurve(0.50f, 0.15f, 0.65f, 1.0f)
        assertEquals(0.15f + 0.85f * 0.65f, outMid, 0.01f)

        val outMax = ControllerAxisSettings.evaluateStickCurve(1.00f, 0.15f, 0.65f, 1.0f)
        assertEquals(1.00f, outMax, 0.001f)
    }

    @Test
    fun testPresetsTargeting() {
        val settings = ControllerAxisSettings()

        // Apply PRECISE to Right stick only
        settings.applyPreset(ControllerAxisSettings.Preset.PRECISE, ControllerAxisSettings.TargetStick.RIGHT)
        assertEquals(ControllerAxisSettings.LINEAR_DEADZONE, settings.leftStickDeadzone, 0.001f)
        assertEquals(ControllerAxisSettings.LINEAR_MIDPOINT, settings.leftStickMidpoint, 0.001f)
        assertEquals(ControllerAxisSettings.PRECISE_DEADZONE, settings.rightStickDeadzone, 0.001f)
        assertEquals(ControllerAxisSettings.PRECISE_MIDPOINT, settings.rightStickMidpoint, 0.001f)
        assertEquals(ControllerAxisSettings.PRECISE_ANTI_DEADZONE, settings.rightStickAntiDeadzone, 0.001f)

        // Apply AGGRESSIVE to Left stick only
        settings.applyPreset(ControllerAxisSettings.Preset.AGGRESSIVE, ControllerAxisSettings.TargetStick.LEFT)
        assertEquals(ControllerAxisSettings.AGGRESSIVE_DEADZONE, settings.leftStickDeadzone, 0.001f)
        assertEquals(ControllerAxisSettings.AGGRESSIVE_MIDPOINT, settings.leftStickMidpoint, 0.001f)
        assertEquals(ControllerAxisSettings.PRECISE_DEADZONE, settings.rightStickDeadzone, 0.001f)

        // Apply LINEAR to BOTH
        settings.applyPreset(ControllerAxisSettings.Preset.LINEAR, ControllerAxisSettings.TargetStick.BOTH)
        assertEquals(ControllerAxisSettings.LINEAR_DEADZONE, settings.leftStickDeadzone, 0.001f)
        assertEquals(ControllerAxisSettings.LINEAR_MIDPOINT, settings.leftStickMidpoint, 0.001f)
        assertEquals(ControllerAxisSettings.LINEAR_DEADZONE, settings.rightStickDeadzone, 0.001f)
        assertEquals(ControllerAxisSettings.LINEAR_MIDPOINT, settings.rightStickMidpoint, 0.001f)
    }

    @Test
    fun testJsonSerializationRoundTrip() {
        val original = ControllerAxisSettings(
            leftStickDeadzone = 0.04f,
            leftStickAntiDeadzone = 0.08f,
            leftStickMidpoint = 0.40f,
            leftStickOuterDeadzone = 0.95f,
            leftStickSensitivity = 1.0f,
            rightStickDeadzone = 0.03f,
            rightStickAntiDeadzone = 0.10f,
            rightStickMidpoint = 0.35f,
            rightStickOuterDeadzone = 0.90f,
            rightStickSensitivity = 2.5f,
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
        assertEquals(original.leftStickAntiDeadzone, deserialized.leftStickAntiDeadzone, 0.001f)
        assertEquals(original.leftStickMidpoint, deserialized.leftStickMidpoint, 0.001f)
        assertEquals(original.leftStickOuterDeadzone, deserialized.leftStickOuterDeadzone, 0.001f)
        assertEquals(original.rightStickAntiDeadzone, deserialized.rightStickAntiDeadzone, 0.001f)
        assertEquals(original.rightStickMidpoint, deserialized.rightStickMidpoint, 0.001f)
        assertEquals(original.rightStickOuterDeadzone, deserialized.rightStickOuterDeadzone, 0.001f)
        assertEquals(original.rightStickSensitivity, deserialized.rightStickSensitivity, 0.001f)
        assertFalse(deserialized.leftTriggerHairTrigger)
        assertTrue(deserialized.rightTriggerHairTrigger)
        assertEquals(0.05f, deserialized.rightTriggerThreshold, 0.001f)
    }
}
