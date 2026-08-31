package com.winlator.inputcontrols

import org.json.JSONException
import org.json.JSONObject

/**
 * Settings for physical controller analog stick and trigger response.
 * Supports independent Left Stick, Right Stick, and L2 / R2 trigger tuning.
 * Uses a Piecewise Midpoint Spline + Anti-Deadzone Floor for stick curves.
 */
data class ControllerAxisSettings(
    // Left Analog Stick
    var leftStickDeadzone: Float = DEFAULT_DEADZONE,
    var leftStickAntiDeadzone: Float = DEFAULT_ANTI_DEADZONE,
    var leftStickMidpoint: Float = DEFAULT_MIDPOINT,
    var leftStickOuterDeadzone: Float = DEFAULT_STICK_OUTER_DEADZONE,
    var leftStickSensitivity: Float = DEFAULT_SENSITIVITY,
    var leftStickCurve: Float = DEFAULT_CURVE, // Kept for backwards compatibility

    // Right Analog Stick
    var rightStickDeadzone: Float = DEFAULT_DEADZONE,
    var rightStickAntiDeadzone: Float = DEFAULT_ANTI_DEADZONE,
    var rightStickMidpoint: Float = DEFAULT_MIDPOINT,
    var rightStickOuterDeadzone: Float = DEFAULT_STICK_OUTER_DEADZONE,
    var rightStickSensitivity: Float = DEFAULT_SENSITIVITY,
    var rightStickCurve: Float = DEFAULT_CURVE, // Kept for backwards compatibility

    // Left Trigger (L2 / LT)
    var leftTriggerHairTrigger: Boolean = false,
    var leftTriggerThreshold: Float = DEFAULT_TRIGGER_THRESHOLD,
    var leftTriggerDeadzone: Float = DEFAULT_TRIGGER_DEADZONE,
    var leftTriggerCutoff: Float = DEFAULT_TRIGGER_CUTOFF,
    var leftTriggerSensitivity: Float = DEFAULT_TRIGGER_SENSITIVITY,
    var leftTriggerCurve: Float = DEFAULT_TRIGGER_CURVE,

    // Right Trigger (R2 / RT)
    var rightTriggerHairTrigger: Boolean = false,
    var rightTriggerThreshold: Float = DEFAULT_TRIGGER_THRESHOLD,
    var rightTriggerDeadzone: Float = DEFAULT_TRIGGER_DEADZONE,
    var rightTriggerCutoff: Float = DEFAULT_TRIGGER_CUTOFF,
    var rightTriggerSensitivity: Float = DEFAULT_TRIGGER_SENSITIVITY,
    var rightTriggerCurve: Float = DEFAULT_TRIGGER_CURVE,

    // UI Link preferences
    var linkSticks: Boolean = true,
    var linkTriggers: Boolean = true,
) {
    companion object {
        const val DEFAULT_DEADZONE = 0.05f
        const val DEFAULT_ANTI_DEADZONE = 0.00f
        const val DEFAULT_MIDPOINT = 0.50f
        const val DEFAULT_STICK_OUTER_DEADZONE = 1.0f
        const val DEFAULT_SENSITIVITY = 1.0f
        const val DEFAULT_CURVE = 1.0f
        const val DEFAULT_TRIGGER_THRESHOLD = 0.10f
        const val DEFAULT_TRIGGER_DEADZONE = 0.02f
        const val DEFAULT_TRIGGER_CUTOFF = 1.0f
        const val DEFAULT_TRIGGER_SENSITIVITY = 1.0f
        const val DEFAULT_TRIGGER_CURVE = 1.0f

        const val PRECISE_DEADZONE = 0.03f
        const val PRECISE_ANTI_DEADZONE = 0.10f
        const val PRECISE_MIDPOINT = 0.35f
        const val PRECISE_SENSITIVITY = 1.00f
        const val PRECISE_CURVE = 1.25f

        const val LINEAR_DEADZONE = 0.05f
        const val LINEAR_ANTI_DEADZONE = 0.00f
        const val LINEAR_MIDPOINT = 0.50f
        const val LINEAR_SENSITIVITY = 1.0f
        const val LINEAR_CURVE = 1.0f

        const val AGGRESSIVE_DEADZONE = 0.02f
        const val AGGRESSIVE_ANTI_DEADZONE = 0.15f
        const val AGGRESSIVE_MIDPOINT = 0.65f
        const val AGGRESSIVE_SENSITIVITY = 1.00f
        const val AGGRESSIVE_CURVE = 0.85f

        /**
         * Evaluates output value for a normalized physical deflection using a Piecewise Midpoint Spline + Anti-Deadzone Floor.
         */
        @JvmStatic
        fun evaluateStickCurve(
            normalizedInput: Float,
            antiDeadzone: Float,
            midpoint: Float,
            sensitivity: Float
        ): Float {
            if (normalizedInput <= 0f) return 0f
            val t = normalizedInput.coerceIn(0f, 1f)
            val m = midpoint.coerceIn(0.10f, 0.90f)
            val s = Math.min(1.0f, 2.0f * Math.min(m, 1.0f - m))
            val curved = if (t <= 0.5f) {
                val a = 2f * s - 4f * m
                val b = 4f * m - s
                (a * t * t + b * t).coerceIn(0f, 1f)
            } else {
                val dt = t - 0.5f
                val c = 4f * (1f - m) - 2f * s
                (m + s * dt + c * dt * dt).coerceIn(0f, 1f)
            }
            val antiDz = antiDeadzone.coerceIn(0f, 0.5f)
            val withAntiDz = antiDz + (1f - antiDz) * curved
            return (withAntiDz * sensitivity).coerceIn(0f, 1f)
        }

        @JvmStatic
        fun fromJSONObject(json: JSONObject?): ControllerAxisSettings {
            if (json == null) return ControllerAxisSettings()

            // Legacy fallback if single stick fields were saved
            val legacyDeadzone = json.optDouble("stickDeadzone", DEFAULT_DEADZONE.toDouble()).toFloat()
            val legacySensitivity = json.optDouble("stickSensitivity", DEFAULT_SENSITIVITY.toDouble()).toFloat()
            val legacyCurve = json.optDouble("stickCurve", DEFAULT_CURVE.toDouble()).toFloat()
            val legacyHair = json.optBoolean("triggerHairTrigger", false)
            val legacyThreshold = json.optDouble("triggerThreshold", DEFAULT_TRIGGER_THRESHOLD.toDouble()).toFloat()
            val legacyTrigDeadzone = json.optDouble("triggerDeadzone", DEFAULT_TRIGGER_DEADZONE.toDouble()).toFloat()

            // For midpoint: if not saved but legacy curve is present, estimate midpoint
            val leftSavedCurve = json.optDouble("leftStickCurve", legacyCurve.toDouble()).toFloat()
            val rightSavedCurve = json.optDouble("rightStickCurve", legacyCurve.toDouble()).toFloat()
            val leftDefaultMid = if (Math.abs(leftSavedCurve - 1.0f) > 0.05f) Math.pow(0.5, leftSavedCurve.toDouble()).toFloat() else DEFAULT_MIDPOINT
            val rightDefaultMid = if (Math.abs(rightSavedCurve - 1.0f) > 0.05f) Math.pow(0.5, rightSavedCurve.toDouble()).toFloat() else DEFAULT_MIDPOINT

            return ControllerAxisSettings(
                leftStickDeadzone = json.optDouble("leftStickDeadzone", legacyDeadzone.toDouble()).toFloat(),
                leftStickAntiDeadzone = json.optDouble("leftStickAntiDeadzone", DEFAULT_ANTI_DEADZONE.toDouble()).toFloat(),
                leftStickMidpoint = json.optDouble("leftStickMidpoint", leftDefaultMid.toDouble()).toFloat(),
                leftStickOuterDeadzone = json.optDouble("leftStickOuterDeadzone", DEFAULT_STICK_OUTER_DEADZONE.toDouble()).toFloat(),
                leftStickSensitivity = json.optDouble("leftStickSensitivity", legacySensitivity.toDouble()).toFloat(),
                leftStickCurve = leftSavedCurve,

                rightStickDeadzone = json.optDouble("rightStickDeadzone", legacyDeadzone.toDouble()).toFloat(),
                rightStickAntiDeadzone = json.optDouble("rightStickAntiDeadzone", DEFAULT_ANTI_DEADZONE.toDouble()).toFloat(),
                rightStickMidpoint = json.optDouble("rightStickMidpoint", rightDefaultMid.toDouble()).toFloat(),
                rightStickOuterDeadzone = json.optDouble("rightStickOuterDeadzone", DEFAULT_STICK_OUTER_DEADZONE.toDouble()).toFloat(),
                rightStickSensitivity = json.optDouble("rightStickSensitivity", legacySensitivity.toDouble()).toFloat(),
                rightStickCurve = rightSavedCurve,

                leftTriggerHairTrigger = json.optBoolean("leftTriggerHairTrigger", legacyHair),
                leftTriggerThreshold = json.optDouble("leftTriggerThreshold", legacyThreshold.toDouble()).toFloat(),
                leftTriggerDeadzone = json.optDouble("leftTriggerDeadzone", legacyTrigDeadzone.toDouble()).toFloat(),
                leftTriggerCutoff = json.optDouble("leftTriggerCutoff", DEFAULT_TRIGGER_CUTOFF.toDouble()).toFloat(),
                leftTriggerSensitivity = json.optDouble("leftTriggerSensitivity", DEFAULT_TRIGGER_SENSITIVITY.toDouble()).toFloat(),
                leftTriggerCurve = json.optDouble("leftTriggerCurve", DEFAULT_TRIGGER_CURVE.toDouble()).toFloat(),

                rightTriggerHairTrigger = json.optBoolean("rightTriggerHairTrigger", legacyHair),
                rightTriggerThreshold = json.optDouble("rightTriggerThreshold", legacyThreshold.toDouble()).toFloat(),
                rightTriggerDeadzone = json.optDouble("rightTriggerDeadzone", legacyTrigDeadzone.toDouble()).toFloat(),
                rightTriggerCutoff = json.optDouble("rightTriggerCutoff", DEFAULT_TRIGGER_CUTOFF.toDouble()).toFloat(),
                rightTriggerSensitivity = json.optDouble("rightTriggerSensitivity", DEFAULT_TRIGGER_SENSITIVITY.toDouble()).toFloat(),
                rightTriggerCurve = json.optDouble("rightTriggerCurve", DEFAULT_TRIGGER_CURVE.toDouble()).toFloat(),

                linkSticks = json.optBoolean("linkSticks", true),
                linkTriggers = json.optBoolean("linkTriggers", true),
            )
        }
    }

    fun toJSONObject(): JSONObject {
        val json = JSONObject()
        try {
            json.put("leftStickDeadzone", leftStickDeadzone.toDouble())
            json.put("leftStickAntiDeadzone", leftStickAntiDeadzone.toDouble())
            json.put("leftStickMidpoint", leftStickMidpoint.toDouble())
            json.put("leftStickOuterDeadzone", leftStickOuterDeadzone.toDouble())
            json.put("leftStickSensitivity", leftStickSensitivity.toDouble())
            json.put("leftStickCurve", leftStickCurve.toDouble())

            json.put("rightStickDeadzone", rightStickDeadzone.toDouble())
            json.put("rightStickAntiDeadzone", rightStickAntiDeadzone.toDouble())
            json.put("rightStickMidpoint", rightStickMidpoint.toDouble())
            json.put("rightStickOuterDeadzone", rightStickOuterDeadzone.toDouble())
            json.put("rightStickSensitivity", rightStickSensitivity.toDouble())
            json.put("rightStickCurve", rightStickCurve.toDouble())

            json.put("leftTriggerHairTrigger", leftTriggerHairTrigger)
            json.put("leftTriggerThreshold", leftTriggerThreshold.toDouble())
            json.put("leftTriggerDeadzone", leftTriggerDeadzone.toDouble())
            json.put("leftTriggerCutoff", leftTriggerCutoff.toDouble())
            json.put("leftTriggerSensitivity", leftTriggerSensitivity.toDouble())
            json.put("leftTriggerCurve", leftTriggerCurve.toDouble())

            json.put("rightTriggerHairTrigger", rightTriggerHairTrigger)
            json.put("rightTriggerThreshold", rightTriggerThreshold.toDouble())
            json.put("rightTriggerDeadzone", rightTriggerDeadzone.toDouble())
            json.put("rightTriggerCutoff", rightTriggerCutoff.toDouble())
            json.put("rightTriggerSensitivity", rightTriggerSensitivity.toDouble())
            json.put("rightTriggerCurve", rightTriggerCurve.toDouble())

            json.put("linkSticks", linkSticks)
            json.put("linkTriggers", linkTriggers)
        } catch (e: JSONException) {
            // Ignore
        }
        return json
    }

    enum class TargetStick {
        BOTH,
        LEFT,
        RIGHT,
    }

    enum class TargetTrigger {
        BOTH,
        LEFT,
        RIGHT,
    }

    enum class Preset {
        PRECISE,
        LINEAR,
        AGGRESSIVE,
    }

    // Genre-level presets (apply to both sticks & triggers)
    enum class GenrePreset {
        LINEAR_DEFAULT,
        FPS_SHOOTER,
        RACING_DRIVING,
        FAST_ACTION,
        FLIGHT_SPACE,
    }

    // Left stick specific movement presets
    enum class LeftStickPreset {
        LINEAR_DEFAULT,
        SMOOTH_STEER,
        FAST_RUN,
        STEALTH_WALK,
    }

    // Right stick specific aiming/camera presets
    enum class RightStickPreset {
        LINEAR_DEFAULT,
        PRECISION_AIM,
        FAST_FLICK,
        SMOOTH_CAM,
    }

    // Trigger presets
    enum class TriggerPreset {
        HAIR_TRIGGER,
        SMOOTH_RACING,
        LINEAR_DEFAULT,
        AGGRESSIVE_THROTTLE,
    }

    fun applyTriggerPreset(preset: TriggerPreset, target: TargetTrigger = TargetTrigger.BOTH) {
        fun applyTo(isLeft: Boolean) {
            when (preset) {
                TriggerPreset.HAIR_TRIGGER -> {
                    if (isLeft) {
                        leftTriggerHairTrigger = true
                        leftTriggerThreshold = 0.08f
                    } else {
                        rightTriggerHairTrigger = true
                        rightTriggerThreshold = 0.08f
                    }
                }
                TriggerPreset.SMOOTH_RACING -> {
                    if (isLeft) {
                        leftTriggerHairTrigger = false
                        leftTriggerDeadzone = 0.02f
                        leftTriggerCutoff = 1.0f
                        leftTriggerSensitivity = 1.0f
                        leftTriggerCurve = 1.4f
                    } else {
                        rightTriggerHairTrigger = false
                        rightTriggerDeadzone = 0.02f
                        rightTriggerCutoff = 1.0f
                        rightTriggerSensitivity = 1.0f
                        rightTriggerCurve = 1.4f
                    }
                }
                TriggerPreset.LINEAR_DEFAULT -> {
                    if (isLeft) {
                        leftTriggerHairTrigger = false
                        leftTriggerDeadzone = 0.02f
                        leftTriggerCutoff = 1.0f
                        leftTriggerSensitivity = 1.0f
                        leftTriggerCurve = 1.0f
                    } else {
                        rightTriggerHairTrigger = false
                        rightTriggerDeadzone = 0.02f
                        rightTriggerCutoff = 1.0f
                        rightTriggerSensitivity = 1.0f
                        rightTriggerCurve = 1.0f
                    }
                }
                TriggerPreset.AGGRESSIVE_THROTTLE -> {
                    if (isLeft) {
                        leftTriggerHairTrigger = false
                        leftTriggerDeadzone = 0.02f
                        leftTriggerCutoff = 0.90f
                        leftTriggerSensitivity = 1.2f
                        leftTriggerCurve = 0.6f
                    } else {
                        rightTriggerHairTrigger = false
                        rightTriggerDeadzone = 0.02f
                        rightTriggerCutoff = 0.90f
                        rightTriggerSensitivity = 1.2f
                        rightTriggerCurve = 0.6f
                    }
                }
            }
        }

        if (target == TargetTrigger.BOTH || target == TargetTrigger.LEFT) {
            applyTo(true)
        }
        if (target == TargetTrigger.BOTH || target == TargetTrigger.RIGHT) {
            applyTo(false)
        }
    }

    fun applyGenrePreset(preset: GenrePreset) {
        when (preset) {
            GenrePreset.LINEAR_DEFAULT -> {
                leftStickDeadzone = LINEAR_DEADZONE
                leftStickAntiDeadzone = LINEAR_ANTI_DEADZONE
                leftStickMidpoint = LINEAR_MIDPOINT
                leftStickSensitivity = LINEAR_SENSITIVITY
                leftStickCurve = LINEAR_CURVE
                rightStickDeadzone = LINEAR_DEADZONE
                rightStickAntiDeadzone = LINEAR_ANTI_DEADZONE
                rightStickMidpoint = LINEAR_MIDPOINT
                rightStickSensitivity = LINEAR_SENSITIVITY
                rightStickCurve = LINEAR_CURVE
                leftTriggerHairTrigger = false
                leftTriggerDeadzone = 0.02f
                leftTriggerCutoff = 1.0f
                leftTriggerSensitivity = 1.0f
                leftTriggerCurve = 1.0f
                rightTriggerHairTrigger = false
                rightTriggerDeadzone = 0.02f
                rightTriggerCutoff = 1.0f
                rightTriggerSensitivity = 1.0f
                rightTriggerCurve = 1.0f
            }
            GenrePreset.FPS_SHOOTER -> {
                leftStickDeadzone = 0.04f
                leftStickAntiDeadzone = 0.08f
                leftStickMidpoint = 0.45f
                leftStickSensitivity = 1.00f
                leftStickCurve = 1.00f
                rightStickDeadzone = 0.03f
                rightStickAntiDeadzone = 0.10f
                rightStickMidpoint = 0.35f
                rightStickSensitivity = 1.00f
                rightStickCurve = 1.25f
                leftTriggerHairTrigger = true
                leftTriggerThreshold = 0.05f
                rightTriggerHairTrigger = true
                rightTriggerThreshold = 0.05f
            }
            GenrePreset.RACING_DRIVING -> {
                leftStickDeadzone = 0.05f
                leftStickAntiDeadzone = 0.05f
                leftStickMidpoint = 0.45f
                leftStickSensitivity = 1.00f
                leftStickCurve = 1.15f
                rightStickDeadzone = 0.05f
                rightStickAntiDeadzone = 0.05f
                rightStickMidpoint = 0.50f
                rightStickSensitivity = 1.00f
                rightStickCurve = 1.00f
                leftTriggerHairTrigger = false
                leftTriggerDeadzone = 0.02f
                leftTriggerCutoff = 1.0f
                leftTriggerSensitivity = 1.0f
                leftTriggerCurve = 1.30f
                rightTriggerHairTrigger = false
                rightTriggerDeadzone = 0.02f
                rightTriggerCutoff = 1.0f
                rightTriggerSensitivity = 1.0f
                rightTriggerCurve = 1.30f
            }
            GenrePreset.FAST_ACTION -> {
                leftStickDeadzone = 0.02f
                leftStickAntiDeadzone = 0.12f
                leftStickMidpoint = 0.60f
                leftStickSensitivity = 1.00f
                leftStickCurve = 0.85f
                rightStickDeadzone = 0.02f
                rightStickAntiDeadzone = 0.12f
                rightStickMidpoint = 0.60f
                rightStickSensitivity = 1.00f
                rightStickCurve = 0.85f
                leftTriggerHairTrigger = true
                leftTriggerThreshold = 0.05f
                rightTriggerHairTrigger = true
                rightTriggerThreshold = 0.05f
            }
            GenrePreset.FLIGHT_SPACE -> {
                leftStickDeadzone = 0.04f
                leftStickAntiDeadzone = 0.05f
                leftStickMidpoint = 0.35f
                leftStickSensitivity = 1.00f
                leftStickCurve = 1.40f
                rightStickDeadzone = 0.04f
                rightStickAntiDeadzone = 0.05f
                rightStickMidpoint = 0.35f
                rightStickSensitivity = 1.00f
                rightStickCurve = 1.40f
                leftTriggerHairTrigger = false
                leftTriggerDeadzone = 0.02f
                leftTriggerCutoff = 1.0f
                leftTriggerSensitivity = 1.0f
                leftTriggerCurve = 1.0f
                rightTriggerHairTrigger = false
                rightTriggerDeadzone = 0.02f
                rightTriggerCutoff = 1.0f
                rightTriggerSensitivity = 1.0f
                rightTriggerCurve = 1.0f
            }
        }
    }

    fun applyLeftStickPreset(preset: LeftStickPreset) {
        when (preset) {
            LeftStickPreset.LINEAR_DEFAULT -> {
                leftStickDeadzone = LINEAR_DEADZONE
                leftStickAntiDeadzone = LINEAR_ANTI_DEADZONE
                leftStickMidpoint = LINEAR_MIDPOINT
                leftStickSensitivity = LINEAR_SENSITIVITY
                leftStickCurve = LINEAR_CURVE
            }
            LeftStickPreset.SMOOTH_STEER -> {
                leftStickDeadzone = 0.05f
                leftStickAntiDeadzone = 0.05f
                leftStickMidpoint = 0.45f
                leftStickSensitivity = 1.00f
                leftStickCurve = 1.15f
            }
            LeftStickPreset.FAST_RUN -> {
                leftStickDeadzone = 0.02f
                leftStickAntiDeadzone = 0.12f
                leftStickMidpoint = 0.60f
                leftStickSensitivity = 1.00f
                leftStickCurve = 0.85f
            }
            LeftStickPreset.STEALTH_WALK -> {
                leftStickDeadzone = 0.06f
                leftStickAntiDeadzone = 0.08f
                leftStickMidpoint = 0.35f
                leftStickSensitivity = 1.00f
                leftStickCurve = 1.35f
            }
        }
    }

    fun applyRightStickPreset(preset: RightStickPreset) {
        when (preset) {
            RightStickPreset.LINEAR_DEFAULT -> {
                rightStickDeadzone = LINEAR_DEADZONE
                rightStickAntiDeadzone = LINEAR_ANTI_DEADZONE
                rightStickMidpoint = LINEAR_MIDPOINT
                rightStickSensitivity = LINEAR_SENSITIVITY
                rightStickCurve = LINEAR_CURVE
            }
            RightStickPreset.PRECISION_AIM -> {
                rightStickDeadzone = 0.03f
                rightStickAntiDeadzone = 0.10f
                rightStickMidpoint = 0.35f
                rightStickSensitivity = 1.00f
                rightStickCurve = 1.25f
            }
            RightStickPreset.FAST_FLICK -> {
                rightStickDeadzone = 0.02f
                rightStickAntiDeadzone = 0.12f
                rightStickMidpoint = 0.65f
                rightStickSensitivity = 1.00f
                rightStickCurve = 0.85f
            }
            RightStickPreset.SMOOTH_CAM -> {
                rightStickDeadzone = 0.04f
                rightStickAntiDeadzone = 0.08f
                rightStickMidpoint = 0.40f
                rightStickSensitivity = 1.00f
                rightStickCurve = 1.15f
            }
        }
    }

    fun applyPreset(preset: Preset, target: TargetStick = TargetStick.BOTH) {
        val (dz, antiDz, mid, sens, crv) = when (preset) {
            Preset.PRECISE -> Tuple5(PRECISE_DEADZONE, PRECISE_ANTI_DEADZONE, PRECISE_MIDPOINT, PRECISE_SENSITIVITY, PRECISE_CURVE)
            Preset.LINEAR -> Tuple5(LINEAR_DEADZONE, LINEAR_ANTI_DEADZONE, LINEAR_MIDPOINT, LINEAR_SENSITIVITY, LINEAR_CURVE)
            Preset.AGGRESSIVE -> Tuple5(AGGRESSIVE_DEADZONE, AGGRESSIVE_ANTI_DEADZONE, AGGRESSIVE_MIDPOINT, AGGRESSIVE_SENSITIVITY, AGGRESSIVE_CURVE)
        }

        if (target == TargetStick.BOTH || target == TargetStick.LEFT) {
            leftStickDeadzone = dz
            leftStickAntiDeadzone = antiDz
            leftStickMidpoint = mid
            leftStickSensitivity = sens
            leftStickCurve = crv
        }
        if (target == TargetStick.BOTH || target == TargetStick.RIGHT) {
            rightStickDeadzone = dz
            rightStickAntiDeadzone = antiDz
            rightStickMidpoint = mid
            rightStickSensitivity = sens
            rightStickCurve = crv
        }
    }

    private data class Tuple5<A, B, C, D, E>(val a: A, val b: B, val c: C, val d: D, val e: E)
}
