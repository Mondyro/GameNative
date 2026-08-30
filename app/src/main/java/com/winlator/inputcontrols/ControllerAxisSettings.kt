package com.winlator.inputcontrols

import org.json.JSONException
import org.json.JSONObject

/**
 * Settings for physical controller analog stick and trigger response.
 * Supports independent Left Stick, Right Stick, and L2 / R2 trigger tuning.
 */
data class ControllerAxisSettings(
    // Left Analog Stick
    var leftStickDeadzone: Float = DEFAULT_DEADZONE,
    var leftStickOuterDeadzone: Float = DEFAULT_STICK_OUTER_DEADZONE,
    var leftStickSensitivity: Float = DEFAULT_SENSITIVITY,
    var leftStickCurve: Float = DEFAULT_CURVE,

    // Right Analog Stick
    var rightStickDeadzone: Float = DEFAULT_DEADZONE,
    var rightStickOuterDeadzone: Float = DEFAULT_STICK_OUTER_DEADZONE,
    var rightStickSensitivity: Float = DEFAULT_SENSITIVITY,
    var rightStickCurve: Float = DEFAULT_CURVE,

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
        const val DEFAULT_DEADZONE = 0.15f
        const val DEFAULT_STICK_OUTER_DEADZONE = 1.0f
        const val DEFAULT_SENSITIVITY = 1.0f
        const val DEFAULT_CURVE = 1.0f
        const val DEFAULT_TRIGGER_THRESHOLD = 0.10f
        const val DEFAULT_TRIGGER_DEADZONE = 0.02f
        const val DEFAULT_TRIGGER_CUTOFF = 1.0f
        const val DEFAULT_TRIGGER_SENSITIVITY = 1.0f
        const val DEFAULT_TRIGGER_CURVE = 1.0f

        const val PRECISE_DEADZONE = 0.12f
        const val PRECISE_SENSITIVITY = 0.85f
        const val PRECISE_CURVE = 1.6f

        const val LINEAR_DEADZONE = 0.15f
        const val LINEAR_SENSITIVITY = 1.0f
        const val LINEAR_CURVE = 1.0f

        const val AGGRESSIVE_DEADZONE = 0.08f
        const val AGGRESSIVE_SENSITIVITY = 1.50f
        const val AGGRESSIVE_CURVE = 0.6f

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

            return ControllerAxisSettings(
                leftStickDeadzone = json.optDouble("leftStickDeadzone", legacyDeadzone.toDouble()).toFloat(),
                leftStickOuterDeadzone = json.optDouble("leftStickOuterDeadzone", DEFAULT_STICK_OUTER_DEADZONE.toDouble()).toFloat(),
                leftStickSensitivity = json.optDouble("leftStickSensitivity", legacySensitivity.toDouble()).toFloat(),
                leftStickCurve = json.optDouble("leftStickCurve", legacyCurve.toDouble()).toFloat(),

                rightStickDeadzone = json.optDouble("rightStickDeadzone", legacyDeadzone.toDouble()).toFloat(),
                rightStickOuterDeadzone = json.optDouble("rightStickOuterDeadzone", DEFAULT_STICK_OUTER_DEADZONE.toDouble()).toFloat(),
                rightStickSensitivity = json.optDouble("rightStickSensitivity", legacySensitivity.toDouble()).toFloat(),
                rightStickCurve = json.optDouble("rightStickCurve", legacyCurve.toDouble()).toFloat(),

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
            json.put("leftStickOuterDeadzone", leftStickOuterDeadzone.toDouble())
            json.put("leftStickSensitivity", leftStickSensitivity.toDouble())
            json.put("leftStickCurve", leftStickCurve.toDouble())

            json.put("rightStickDeadzone", rightStickDeadzone.toDouble())
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

    // Trigger specific presets
    enum class TriggerPreset {
        INSTANT_HAIR_FPS,
        SMOOTH_RACING,
        LINEAR_DEFAULT,
        AGGRESSIVE_THROTTLE,
    }

    fun applyTriggerPreset(preset: TriggerPreset, target: TargetTrigger = TargetTrigger.BOTH) {
        val applyTo = { isLeft: Boolean ->
            when (preset) {
                TriggerPreset.INSTANT_HAIR_FPS -> {
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
                leftStickSensitivity = LINEAR_SENSITIVITY
                leftStickCurve = LINEAR_CURVE
                rightStickDeadzone = LINEAR_DEADZONE
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
                leftStickDeadzone = 0.10f
                leftStickSensitivity = 1.00f
                leftStickCurve = 1.00f
                rightStickDeadzone = 0.08f
                rightStickSensitivity = 1.30f
                rightStickCurve = 1.60f
                leftTriggerHairTrigger = true
                leftTriggerThreshold = 0.08f
                rightTriggerHairTrigger = true
                rightTriggerThreshold = 0.08f
            }
            GenrePreset.RACING_DRIVING -> {
                leftStickDeadzone = 0.06f
                leftStickSensitivity = 1.00f
                leftStickCurve = 1.40f
                rightStickDeadzone = 0.12f
                rightStickSensitivity = 1.00f
                rightStickCurve = 1.00f
                leftTriggerHairTrigger = false
                leftTriggerDeadzone = 0.02f
                leftTriggerCutoff = 1.0f
                leftTriggerSensitivity = 1.0f
                leftTriggerCurve = 1.40f
                rightTriggerHairTrigger = false
                rightTriggerDeadzone = 0.02f
                rightTriggerCutoff = 1.0f
                rightTriggerSensitivity = 1.0f
                rightTriggerCurve = 1.40f
            }
            GenrePreset.FAST_ACTION -> {
                leftStickDeadzone = 0.06f
                leftStickSensitivity = 1.50f
                leftStickCurve = 0.60f
                rightStickDeadzone = 0.06f
                rightStickSensitivity = 1.50f
                rightStickCurve = 0.60f
                leftTriggerHairTrigger = true
                leftTriggerThreshold = 0.10f
                rightTriggerHairTrigger = true
                rightTriggerThreshold = 0.10f
            }
            GenrePreset.FLIGHT_SPACE -> {
                leftStickDeadzone = 0.08f
                leftStickSensitivity = 1.00f
                leftStickCurve = 1.80f
                rightStickDeadzone = 0.08f
                rightStickSensitivity = 1.00f
                rightStickCurve = 1.80f
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
                leftStickSensitivity = LINEAR_SENSITIVITY
                leftStickCurve = LINEAR_CURVE
            }
            LeftStickPreset.SMOOTH_STEER -> {
                leftStickDeadzone = 0.06f
                leftStickSensitivity = 1.00f
                leftStickCurve = 1.40f
            }
            LeftStickPreset.FAST_RUN -> {
                leftStickDeadzone = 0.06f
                leftStickSensitivity = 1.50f
                leftStickCurve = 0.60f
            }
            LeftStickPreset.STEALTH_WALK -> {
                leftStickDeadzone = 0.08f
                leftStickSensitivity = 0.85f
                leftStickCurve = 1.60f
            }
        }
    }

    fun applyRightStickPreset(preset: RightStickPreset) {
        when (preset) {
            RightStickPreset.LINEAR_DEFAULT -> {
                rightStickDeadzone = LINEAR_DEADZONE
                rightStickSensitivity = LINEAR_SENSITIVITY
                rightStickCurve = LINEAR_CURVE
            }
            RightStickPreset.PRECISION_AIM -> {
                rightStickDeadzone = 0.08f
                rightStickSensitivity = 1.30f
                rightStickCurve = 1.60f
            }
            RightStickPreset.FAST_FLICK -> {
                rightStickDeadzone = 0.06f
                rightStickSensitivity = 1.50f
                rightStickCurve = 0.60f
            }
            RightStickPreset.SMOOTH_CAM -> {
                rightStickDeadzone = 0.08f
                rightStickSensitivity = 1.00f
                rightStickCurve = 1.80f
            }
        }
    }

    fun applyPreset(preset: Preset, target: TargetStick = TargetStick.BOTH) {
        val (dz, sens, crv) = when (preset) {
            Preset.PRECISE -> Triple(PRECISE_DEADZONE, PRECISE_SENSITIVITY, PRECISE_CURVE)
            Preset.LINEAR -> Triple(LINEAR_DEADZONE, LINEAR_SENSITIVITY, LINEAR_CURVE)
            Preset.AGGRESSIVE -> Triple(AGGRESSIVE_DEADZONE, AGGRESSIVE_SENSITIVITY, AGGRESSIVE_CURVE)
        }

        if (target == TargetStick.BOTH || target == TargetStick.LEFT) {
            leftStickDeadzone = dz
            leftStickSensitivity = sens
            leftStickCurve = crv
        }
        if (target == TargetStick.BOTH || target == TargetStick.RIGHT) {
            rightStickDeadzone = dz
            rightStickSensitivity = sens
            rightStickCurve = crv
        }
    }
}
