package app.gamenative.ui.screen.xserver

import android.graphics.PointF
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.winlator.inputcontrols.Binding
import com.winlator.inputcontrols.ControlElement
import com.winlator.inputcontrols.ControllerAxisSettings
import com.winlator.inputcontrols.ControlsProfile
import com.winlator.inputcontrols.ExternalController
import com.winlator.inputcontrols.ExternalControllerBinding
import com.winlator.math.Mathf
import com.winlator.xserver.XServer
import java.util.Timer
import java.util.TimerTask

/**
 * Standalone handler for physical controller input that works independently of view visibility.
 * Applies profile bindings to convert physical controller input into virtual gamepad state.
 */
class PhysicalControllerHandler(
    private var profile: ControlsProfile?,
    private val xServer: XServer?,
    private val onOpenNavigationMenu: (() -> Unit)? = null,
    private val onShowKeyboard: (() -> Unit)? = null
) {
    companion object {
        private const val SCROLL_REPEAT_INTERVAL_MS = 90L
    }

    private val TAG = "gncontrol"
    private val mouseMoveOffset = PointF(0f, 0f)
    private var mouseMoveTimer: Timer? = null
    private var scrollRepeatTimer: Timer? = null
    private val scrollRepeatLock = Any()
    private val activeScrollBindings = mutableSetOf<Binding>()
    // track which axis keycodes are currently "pressed" so we only release on actual transitions.
    // accessed only from main thread (MotionEvent dispatch + Compose lifecycle), no sync needed.
    private val activeAxisBindings = mutableSetOf<Int>()

    // Tracks whether SHOW_KEYBOARD is currently held, so onShowKeyboard fires once per press (rising edge only)
    private var showKeyboardPressed = false

    private fun releaseActiveAxes() {
        val controller = profile?.getController("*") ?: return
        for (keyCode in activeAxisBindings) {
            controller.getControllerBinding(keyCode)?.let {
                handleInputEvent(it.binding, false, 0f)
            }
        }
        activeAxisBindings.clear()
    }

    fun setProfile(profile: ControlsProfile?) {
        releaseActiveAxes()
        clearScrollRepeats()
        this.profile = profile
        Log.d(TAG, "PhysicalControllerHandler: Profile set to ${profile?.name}")

        // Cancel mouse movement timer if profile is null
        if (profile == null) {
            mouseMoveTimer?.cancel()
            mouseMoveTimer = null
            mouseMoveOffset.set(0f, 0f)
        }
    }

    /**
     * Clean up resources when handler is destroyed
     */
    fun cleanup() {
        releaseActiveAxes()
        mouseMoveTimer?.cancel()
        mouseMoveTimer = null
        mouseMoveOffset.set(0f, 0f)
        clearScrollRepeats()
        showKeyboardPressed = false
    }

    /**
     * Handle physical controller button events.
     * Extracted from InputControlsView.onKeyEvent()
     */
    fun onKeyEvent(event: KeyEvent): Boolean {
        if (profile != null) {
            val controller = profile?.getController(event.deviceId)
            if (controller != null) {
                val controllerBinding = controller.getControllerBinding(event.keyCode)
                if (controllerBinding != null) {
                    // Consume auto-repeat events so they don't fall through to the keyboard queue and cause rapid-fire toggling
                    if (event.repeatCount > 0) {
                        return true
                    }
                    // Some controllers emit BOTH a digital KeyEvent for L2/R2 and an analog axis value in MotionEvent.
                    // If this physical key is mapped to a virtual trigger AND the device exposes trigger axes,
                    // ignore the KeyEvent to avoid an initial "full press" spike. MotionEvent will provide the analog value.
                    if ((event.keyCode == KeyEvent.KEYCODE_BUTTON_L2 || event.keyCode == KeyEvent.KEYCODE_BUTTON_R2) &&
                        (controllerBinding.binding == Binding.GAMEPAD_BUTTON_L2 || controllerBinding.binding == Binding.GAMEPAD_BUTTON_R2) &&
                        deviceHasTriggerAxis(event.device, event.keyCode)
                    ) {
                        return true
                    }
                    val offset = if (event.action == KeyEvent.ACTION_DOWN &&
                        (controllerBinding.binding == Binding.GAMEPAD_BUTTON_L2 || controllerBinding.binding == Binding.GAMEPAD_BUTTON_R2)
                    ) 1f else 0f
                    handleInputEvent(controllerBinding.binding, event.action == KeyEvent.ACTION_DOWN, offset)

                    val winHandler = xServer?.winHandler
                    val state = profile?.gamepadState
                    if (winHandler != null) {
                        winHandler.sendGamepadState()
                        winHandler.sendVirtualGamepadState(state)
                    }
                    return true
                }
            }
        }
        return false
    }

    private fun deviceHasTriggerAxis(device: InputDevice?, keyCode: Int): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_L2 ->
                hasMotionRange(device, MotionEvent.AXIS_LTRIGGER) || hasMotionRange(device, MotionEvent.AXIS_BRAKE)
            KeyEvent.KEYCODE_BUTTON_R2 ->
                hasMotionRange(device, MotionEvent.AXIS_RTRIGGER) || hasMotionRange(device, MotionEvent.AXIS_GAS)
            else -> false
        }
    }

    private fun hasMotionRange(device: InputDevice?, axis: Int): Boolean {
        if (device == null) return false
        return device.getMotionRange(axis, InputDevice.SOURCE_JOYSTICK) != null ||
            device.getMotionRange(axis, InputDevice.SOURCE_GAMEPAD) != null ||
            device.getMotionRange(axis) != null
    }

    /**
     * Handle physical controller analog stick and trigger events.
     * Extracted from InputControlsView.onGenericMotionEvent()
     */
    fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (profile != null) {
            val controller = profile?.getController(event.deviceId)
            if (controller != null && controller.updateStateFromMotionEvent(event)) {
                val axisSettings = controller.axisSettings ?: ControllerAxisSettings()

                // Process trigger buttons (L2/R2) with analog curve/deadzone/cutoff and hair trigger support
                val rawL = controller.state.triggerL
                val rawR = controller.state.triggerR

                val processedL = if (axisSettings.leftTriggerHairTrigger) {
                    if (rawL >= axisSettings.leftTriggerThreshold) 1.0f else 0.0f
                } else {
                    if (rawL <= axisSettings.leftTriggerDeadzone) {
                        0f
                    } else {
                        val denom = (axisSettings.leftTriggerCutoff - axisSettings.leftTriggerDeadzone).coerceAtLeast(0.01f)
                        val normalized = ((rawL - axisSettings.leftTriggerDeadzone) / denom).coerceIn(0f, 1f)
                        Math.pow(normalized.toDouble(), axisSettings.leftTriggerCurve.toDouble()).toFloat().coerceIn(0f, 1f)
                    }
                }

                val processedR = if (axisSettings.rightTriggerHairTrigger) {
                    if (rawR >= axisSettings.rightTriggerThreshold) 1.0f else 0.0f
                } else {
                    if (rawR <= axisSettings.rightTriggerDeadzone) {
                        0f
                    } else {
                        val denom = (axisSettings.rightTriggerCutoff - axisSettings.rightTriggerDeadzone).coerceAtLeast(0.01f)
                        val normalized = ((rawR - axisSettings.rightTriggerDeadzone) / denom).coerceIn(0f, 1f)
                        Math.pow(normalized.toDouble(), axisSettings.rightTriggerCurve.toDouble()).toFloat().coerceIn(0f, 1f)
                    }
                }

                var controllerBinding = controller.getControllerBinding(KeyEvent.KEYCODE_BUTTON_L2)
                if (controllerBinding != null) {
                    handleInputEvent(
                        controllerBinding.binding,
                        processedL > 0f,
                        processedL
                    )
                }

                val r2Binding = controller.getControllerBinding(KeyEvent.KEYCODE_BUTTON_R2)
                if (r2Binding != null) {
                    handleInputEvent(
                        r2Binding.binding,
                        processedR > 0f,
                        processedR
                    )
                }

                // Process analog stick input and obtain processed stick vectors
                val (procLX, procLY, procRX, procRY) = processJoystickInput(controller)

                // ──── Conditionally sync processed axes to profile.gamepadState ────
                // Only sync axes whose bindings are gamepad bindings (or unbound).
                // If an axis is mapped to mouse/keyboard, do NOT write to gamepadState
                // to avoid "double input" (e.g. mouse move AND virtual stick simultaneously).
                val gamepadState = profile?.gamepadState
                if (gamepadState != null) {
                    // Check if left stick axes have gamepad bindings (or no bindings)
                    val lxPosBinding = controller.getControllerBinding(
                        ExternalControllerBinding.getKeyCodeForAxis(MotionEvent.AXIS_X, 1.toByte()))
                    val lxNegBinding = controller.getControllerBinding(
                        ExternalControllerBinding.getKeyCodeForAxis(MotionEvent.AXIS_X, (-1).toByte()))
                    val leftStickIsGamepad = (lxPosBinding == null || lxPosBinding.binding.isGamepad) &&
                                             (lxNegBinding == null || lxNegBinding.binding.isGamepad)
                    if (leftStickIsGamepad) {
                        gamepadState.thumbLX = procLX
                        gamepadState.thumbLY = procLY
                    }

                    // Check right stick axes
                    val rxPosBinding = controller.getControllerBinding(
                        ExternalControllerBinding.getKeyCodeForAxis(MotionEvent.AXIS_Z, 1.toByte()))
                    val rxNegBinding = controller.getControllerBinding(
                        ExternalControllerBinding.getKeyCodeForAxis(MotionEvent.AXIS_Z, (-1).toByte()))
                    val rightStickIsGamepad = (rxPosBinding == null || rxPosBinding.binding.isGamepad) &&
                                              (rxNegBinding == null || rxNegBinding.binding.isGamepad)
                    if (rightStickIsGamepad) {
                        gamepadState.thumbRX = procRX
                        gamepadState.thumbRY = procRY
                    }

                    // Triggers: sync if binding is default gamepad trigger or unbound
                    val l2Binding = controller.getControllerBinding(KeyEvent.KEYCODE_BUTTON_L2)
                    if (l2Binding == null || l2Binding.binding == Binding.GAMEPAD_BUTTON_L2) {
                        gamepadState.triggerL = processedL
                        gamepadState.setPressed(ExternalController.IDX_BUTTON_L2.toInt(), processedL > 0f)
                    }
                    if (r2Binding == null || r2Binding.binding == Binding.GAMEPAD_BUTTON_R2) {
                        gamepadState.triggerR = processedR
                        gamepadState.setPressed(ExternalController.IDX_BUTTON_R2.toInt(), processedR > 0f)
                    }

                    // ──── Keep WinHandler's controller.state in sync with gamepadState ────
                    // This is the core fix for the dropped-input bug: when WinHandler.onKeyEvent
                    // later runs as a fallback for unmapped buttons and calls sendMemoryFileState(),
                    // controller.state will already have correct stick/trigger values instead of
                    // stale zeroes, so held inputs won't be clobbered.
                    val winHandler = xServer?.winHandler
                    val currentCtrl = winHandler?.getCurrentController()
                    if (currentCtrl != null) {
                        currentCtrl.state.copy(gamepadState)
                    }
                }

                val winHandler = xServer?.winHandler
                val state = profile?.gamepadState
                if (winHandler != null) {
                    winHandler.sendGamepadState()
                    winHandler.sendVirtualGamepadState(state)
                }
                return true
            }
        }
        return false
    }

    /**
     * Create a timer for continuous mouse movement injection.
     * Runs at 60 FPS, injecting mouse deltas based on mouseMoveOffset.
     */
    private fun createMouseMoveTimer() {
        if (profile != null && mouseMoveTimer == null) {
            mouseMoveTimer = Timer()
            mouseMoveTimer?.schedule(object : TimerTask() {
                override fun run() {
                    // Skip injection if movement is below 8% deadzone to save CPU cycles
                    val magnitude = Math.sqrt((mouseMoveOffset.x * mouseMoveOffset.x + mouseMoveOffset.y * mouseMoveOffset.y).toDouble())
                    if (magnitude < 0.08) return

                    // Look up cursor speed dynamically so it updates when profile changes
                    val cursorSpeed = profile?.cursorSpeed ?: 1f
                    val deltaX = (mouseMoveOffset.x * 10 * cursorSpeed).toInt()
                    val deltaY = (mouseMoveOffset.y * 10 * cursorSpeed).toInt()
                    xServer?.injectPointerMoveDelta(deltaX, deltaY)
                }
            }, 0, 1000 / 60)
        }
    }

    private fun handleScrollBinding(binding: Binding, isActionDown: Boolean): Boolean {
        if (binding != Binding.MOUSE_SCROLL_UP && binding != Binding.MOUSE_SCROLL_DOWN) {
            return false
        }

        var pulseImmediately = false
        synchronized(scrollRepeatLock) {
            if (isActionDown) {
                pulseImmediately = activeScrollBindings.add(binding)
                createScrollRepeatTimerLocked()
            } else {
                activeScrollBindings.remove(binding)
                if (activeScrollBindings.isEmpty()) {
                    cancelScrollRepeatTimerLocked()
                }
            }
        }

        if (pulseImmediately) {
            sendScrollPulse(binding)
        }
        return true
    }

    private fun createScrollRepeatTimerLocked() {
        if (scrollRepeatTimer != null) return
        scrollRepeatTimer = Timer()
        scrollRepeatTimer?.schedule(object : TimerTask() {
            override fun run() {
                synchronized(scrollRepeatLock) {
                    for (b in activeScrollBindings) {
                        sendScrollPulse(b)
                    }
                }
            }
        }, SCROLL_REPEAT_INTERVAL_MS, SCROLL_REPEAT_INTERVAL_MS)
    }

    private fun cancelScrollRepeatTimerLocked() {
        scrollRepeatTimer?.cancel()
        scrollRepeatTimer = null
    }

    private fun clearScrollRepeats() {
        synchronized(scrollRepeatLock) {
            activeScrollBindings.clear()
            cancelScrollRepeatTimerLocked()
        }
    }

    private fun sendScrollPulse(binding: Binding) {
        val button = binding.pointerButton ?: return
        xServer?.injectPointerButtonPress(button)
        xServer?.injectPointerButtonRelease(button)
    }

    /**
     * Process physical joystick input, applying deadzone, sensitivity, and response curves.
     * Extracted from InputControlsView.processJoystickInput()
     */
    private fun processJoystickInput(controller: ExternalController): FloatArray {
        // Reset mouse movement offset at the start - contributions will be added during processing
        mouseMoveOffset.set(0f, 0f)

        val axisSettings = controller.axisSettings ?: ControllerAxisSettings()

        // 1. Process Left Analog Stick (2D Radial Vector with Piecewise Midpoint Spline + Anti-Deadzone Floor)
        val rawLX = controller.state.thumbLX
        val rawLY = controller.state.thumbLY
        val (procLX, procLY) = calculateRadialVector(
            rawLX, rawLY,
            axisSettings.leftStickDeadzone,
            axisSettings.leftStickAntiDeadzone,
            axisSettings.leftStickMidpoint,
            axisSettings.leftStickOuterDeadzone,
            axisSettings.leftStickSensitivity
        )

        dispatchAxis(controller, MotionEvent.AXIS_X, procLX)
        dispatchAxis(controller, MotionEvent.AXIS_Y, procLY)

        // 2. Process Right Analog Stick (2D Radial Vector with Piecewise Midpoint Spline + Anti-Deadzone Floor)
        val rawRX = controller.state.thumbRX
        val rawRY = controller.state.thumbRY
        val (procRX, procRY) = calculateRadialVector(
            rawRX, rawRY,
            axisSettings.rightStickDeadzone,
            axisSettings.rightStickAntiDeadzone,
            axisSettings.rightStickMidpoint,
            axisSettings.rightStickOuterDeadzone,
            axisSettings.rightStickSensitivity
        )

        dispatchAxis(controller, MotionEvent.AXIS_Z, procRX)
        dispatchAxis(controller, MotionEvent.AXIS_RZ, procRY)

        // 3. Process D-Pad Hat axes (Discrete digital axes)
        dispatchHatAxis(controller, MotionEvent.AXIS_HAT_X, controller.state.dPadX.toFloat())
        dispatchHatAxis(controller, MotionEvent.AXIS_HAT_Y, controller.state.dPadY.toFloat())

        return floatArrayOf(procLX, procLY, procRX, procRY)
    }

    /**
     * Calculates 2D radial deadzone, outer threshold, midpoint spline, anti-deadzone floor, and sensitivity without cardinal axis snapping.
     */
    private fun calculateRadialVector(
        rawX: Float,
        rawY: Float,
        innerDeadzone: Float,
        antiDeadzone: Float,
        midpoint: Float,
        outerDeadzone: Float,
        sensitivity: Float
    ): Pair<Float, Float> {
        val magnitude = Math.sqrt((rawX * rawX + rawY * rawY).toDouble()).toFloat()

        if (magnitude <= innerDeadzone || magnitude == 0f) {
            return Pair(0f, 0f)
        }

        val denom = (outerDeadzone - innerDeadzone).coerceAtLeast(0.01f)
        val normalized = ((magnitude - innerDeadzone) / denom).coerceIn(0f, 1f)
        val scaledMagnitude = ControllerAxisSettings.evaluateStickCurve(
            normalized,
            antiDeadzone,
            midpoint,
            sensitivity
        )
        val factor = scaledMagnitude / magnitude

        return Pair(
            (rawX * factor).coerceIn(-1f, 1f),
            (rawY * factor).coerceIn(-1f, 1f)
        )
    }

    /**
     * Dispatches processed analog axis values to bindings.
     */
    private fun dispatchAxis(controller: ExternalController, axis: Int, processedVal: Float) {
        val posKeyCode = ExternalControllerBinding.getKeyCodeForAxis(axis, 1.toByte())
        val negKeyCode = ExternalControllerBinding.getKeyCodeForAxis(axis, (-1).toByte())

        if (processedVal != 0f) {
            val activeKey = ExternalControllerBinding.getKeyCodeForAxis(axis, Mathf.sign(processedVal))
            val oppositeKey = if (activeKey == posKeyCode) negKeyCode else posKeyCode

            activeAxisBindings.add(activeKey)
            controller.getControllerBinding(activeKey)?.let {
                handleInputEvent(it.binding, true, processedVal)
            }
            if (activeAxisBindings.remove(oppositeKey)) {
                controller.getControllerBinding(oppositeKey)?.let {
                    handleInputEvent(it.binding, false, 0f)
                }
            }
        } else {
            if (activeAxisBindings.remove(posKeyCode)) {
                controller.getControllerBinding(posKeyCode)?.let {
                    handleInputEvent(it.binding, false, 0f)
                }
            }
            if (activeAxisBindings.remove(negKeyCode)) {
                controller.getControllerBinding(negKeyCode)?.let {
                    handleInputEvent(it.binding, false, 0f)
                }
            }
        }
    }

    /**
     * Dispatches D-pad hat axis values (discrete threshold).
     */
    private fun dispatchHatAxis(controller: ExternalController, axis: Int, rawVal: Float) {
        val posKeyCode = ExternalControllerBinding.getKeyCodeForAxis(axis, 1.toByte())
        val negKeyCode = ExternalControllerBinding.getKeyCodeForAxis(axis, (-1).toByte())

        if (Math.abs(rawVal) > 0.5f) {
            val activeKey = ExternalControllerBinding.getKeyCodeForAxis(axis, Mathf.sign(rawVal))
            val oppositeKey = if (activeKey == posKeyCode) negKeyCode else posKeyCode

            activeAxisBindings.add(activeKey)
            val binding = controller.getControllerBinding(activeKey)
            if (binding != null) {
                handleInputEvent(binding.binding, true, rawVal)
            } else {
                val state = profile?.gamepadState
                if (state != null) {
                    if (axis == MotionEvent.AXIS_HAT_X) {
                        if (rawVal > 0.5f) {
                            state.dpad[1] = true // Right
                            state.dpad[3] = false // Left
                        } else if (rawVal < -0.5f) {
                            state.dpad[3] = true // Left
                            state.dpad[1] = false // Right
                        }
                    } else if (axis == MotionEvent.AXIS_HAT_Y) {
                        if (rawVal > 0.5f) {
                            state.dpad[2] = true // Down
                            state.dpad[0] = false // Up
                        } else if (rawVal < -0.5f) {
                            state.dpad[0] = true // Up
                            state.dpad[2] = false // Down
                        }
                    }
                }
            }
            if (activeAxisBindings.remove(oppositeKey)) {
                val oppBinding = controller.getControllerBinding(oppositeKey)
                if (oppBinding != null) {
                    handleInputEvent(oppBinding.binding, false, 0f)
                }
            }
        } else {
            if (activeAxisBindings.remove(posKeyCode)) {
                val binding = controller.getControllerBinding(posKeyCode)
                if (binding != null) {
                    handleInputEvent(binding.binding, false, 0f)
                } else {
                    val state = profile?.gamepadState
                    if (state != null) {
                        if (axis == MotionEvent.AXIS_HAT_X) state.dpad[1] = false
                        else if (axis == MotionEvent.AXIS_HAT_Y) state.dpad[2] = false
                    }
                }
            }
            if (activeAxisBindings.remove(negKeyCode)) {
                val binding = controller.getControllerBinding(negKeyCode)
                if (binding != null) {
                    handleInputEvent(binding.binding, false, 0f)
                } else {
                    val state = profile?.gamepadState
                    if (state != null) {
                        if (axis == MotionEvent.AXIS_HAT_X) state.dpad[3] = false
                        else if (axis == MotionEvent.AXIS_HAT_Y) state.dpad[0] = false
                    }
                }
            }
        }
    }

    /**
     * Apply a binding to the virtual gamepad state and send to WinHandler.
     * Extracted from InputControlsView.handleInputEvent()
     */
    // offset: analog axis value for presses; must be 0f for releases (triggers use offset > 0f
    // to determine pressed state, sticks gate on isActionDown, everything else ignores offset)
    private fun handleInputEvent(binding: Binding, isActionDown: Boolean, offset: Float = 0f) {
        if (binding == Binding.NONE) return

        if (binding.isGamepad) {
            val winHandler = xServer?.winHandler
            val state = profile?.gamepadState

            if (state != null) {
                val buttonIdx = binding.ordinal - Binding.GAMEPAD_BUTTON_A.ordinal
                if (buttonIdx <= ExternalController.IDX_BUTTON_R2.toInt()) {
                    when (buttonIdx) {
                        ExternalController.IDX_BUTTON_L2.toInt() -> {
                            state.triggerL = offset
                            state.setPressed(ExternalController.IDX_BUTTON_L2.toInt(), offset > 0f)
                        }
                        ExternalController.IDX_BUTTON_R2.toInt() -> {
                            state.triggerR = offset
                            state.setPressed(ExternalController.IDX_BUTTON_R2.toInt(), offset > 0f)
                        }
                        else -> state.setPressed(buttonIdx, isActionDown)
                    }
                }
                else {
                    when (binding) {
                        Binding.GAMEPAD_LEFT_THUMB_UP, Binding.GAMEPAD_LEFT_THUMB_DOWN -> {
                            state.thumbLY = if (isActionDown) offset else 0f
                        }
                        Binding.GAMEPAD_LEFT_THUMB_LEFT, Binding.GAMEPAD_LEFT_THUMB_RIGHT -> {
                            state.thumbLX = if (isActionDown) offset else 0f
                        }
                        Binding.GAMEPAD_RIGHT_THUMB_UP, Binding.GAMEPAD_RIGHT_THUMB_DOWN -> {
                            state.thumbRY = if (isActionDown) offset else 0f
                        }
                        Binding.GAMEPAD_RIGHT_THUMB_LEFT, Binding.GAMEPAD_RIGHT_THUMB_RIGHT -> {
                            state.thumbRX = if (isActionDown) offset else 0f
                        }
                        Binding.GAMEPAD_DPAD_UP  -> {
                            state.dpad[0] = isActionDown
                            if(isActionDown) {
                                state.dpad[Binding.GAMEPAD_DPAD_DOWN.ordinal - Binding.GAMEPAD_DPAD_UP.ordinal ] = false
                            }
                        }
                        Binding.GAMEPAD_DPAD_DOWN -> {
                            state.dpad[binding.ordinal - Binding.GAMEPAD_DPAD_UP.ordinal] = isActionDown
                            if(isActionDown) {
                                state.dpad[0] = false
                            }
                        }
                       Binding.GAMEPAD_DPAD_LEFT -> {
                            state.dpad[binding.ordinal - Binding.GAMEPAD_DPAD_UP.ordinal] = isActionDown
                            if(isActionDown) {
                              state.dpad[Binding.GAMEPAD_DPAD_RIGHT.ordinal - Binding.GAMEPAD_DPAD_UP.ordinal ] = false
                          }
                        }
                        Binding.GAMEPAD_DPAD_RIGHT -> {
                            state.dpad[binding.ordinal - Binding.GAMEPAD_DPAD_UP.ordinal] = isActionDown
                            if(isActionDown) {
                                state.dpad[Binding.GAMEPAD_DPAD_LEFT.ordinal - Binding.GAMEPAD_DPAD_UP.ordinal ] = false
                            }
                        }
                        else -> {}
                    }
                }

                if (winHandler != null) {
                    val controller = winHandler.getCurrentController()
                    if (controller != null) {
                        controller.state.copy(state)
                    }
                }
            }
        } else {
            // Handle special bindings
            if (binding == Binding.OPEN_NAVIGATION_MENU) {
                if (isActionDown) {
                    Log.d(TAG, "Opening navigation menu from controller binding")
                    onOpenNavigationMenu?.invoke()
                }
            } else if (binding == Binding.SHOW_KEYBOARD) {
                if (isActionDown) {
                    if (!showKeyboardPressed) {
                        showKeyboardPressed = true
                        Log.d(TAG, "Showing keyboard from controller binding")
                        onShowKeyboard?.invoke()
                    }
                } else {
                    showKeyboardPressed = false
                }
            } else if (binding == Binding.MOUSE_MOVE_LEFT || binding == Binding.MOUSE_MOVE_RIGHT) {
                // Handle horizontal mouse movement - ADD contribution from this input
                if (isActionDown) {
                    val contribution = if (offset != 0f) offset else if (binding == Binding.MOUSE_MOVE_LEFT) -1f else 1f
                    mouseMoveOffset.x += contribution
                    createMouseMoveTimer()
                }
                // Don't reset when isActionDown=false - mouseMoveOffset is reset at the start of processJoystickInput
            } else if (binding == Binding.MOUSE_MOVE_DOWN || binding == Binding.MOUSE_MOVE_UP) {
                // Handle vertical mouse movement - ADD contribution from this input
                if (isActionDown) {
                    val contribution = if (offset != 0f) offset else if (binding == Binding.MOUSE_MOVE_UP) -1f else 1f
                    mouseMoveOffset.y += contribution
                    createMouseMoveTimer()
                }
                // Don't reset when isActionDown=false - mouseMoveOffset is reset at the start of processJoystickInput
            } else if (handleScrollBinding(binding, isActionDown)) {
                // Mouse wheel events are pulses, not held button state.
            } else {
                // For keyboard/mouse button bindings, inject into XServer
                val pointerButton = binding.pointerButton
                if (isActionDown) {
                    if (pointerButton != null) {
                        xServer?.injectPointerButtonPress(pointerButton)
                    } else {
                        xServer?.let { binding.inject(it, true) }
                    }
                } else {
                    if (pointerButton != null) {
                        xServer?.injectPointerButtonRelease(pointerButton)
                    } else {
                        xServer?.let { binding.inject(it, false) }
                    }
                }
            }
        }
    }
}
