package app.gamenative.ui.screen.xserver

import android.graphics.PointF
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.winlator.inputcontrols.Binding
import com.winlator.inputcontrols.ControllerAxisSettings
import com.winlator.inputcontrols.ControlElement
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
    private val onShowKeyboard: (() -> Unit)? = null,
    private val onRadialMenuButtonStateChanged: ((Boolean, Boolean) -> Unit)? = null,
    private val onRadialMenuVectorChanged: ((Float, Float) -> Unit)? = null,
) {
    companion object {
        private const val SCROLL_REPEAT_INTERVAL_MS = 90L
        private const val UNKNOWN_DEVICE_ID = -1
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
    private var radialMenuPressed = false
    private var radialMenuOpenedFromMotion = false
    private var radialMenuOpenerKeyCode = KeyEvent.KEYCODE_UNKNOWN
    private var radialMenuOpenerDeviceId = UNKNOWN_DEVICE_ID

    private fun releaseActiveAxes(exceptKeyCode: Int = KeyEvent.KEYCODE_UNKNOWN) {
        val controller = profile?.getController("*") ?: return
        for (keyCode in activeAxisBindings.toList()) {
            if (keyCode == exceptKeyCode) continue
            activeAxisBindings.remove(keyCode)
            controller.getControllerBinding(keyCode)?.takeIf { it.binding != Binding.OPEN_RADIAL_MENU }?.let {
                handleInputEvent(it.binding, false, 0f)
            }
        }
    }

    fun setProfile(profile: ControlsProfile?) {
        releaseActiveAxes()
        clearScrollRepeats()
        closeRadialMenuIfOpen(commit = false)
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
        closeRadialMenuIfOpen(commit = false)
    }

    /**
     * Handle physical controller button events.
     * Extracted from InputControlsView.onKeyEvent()
     */
    fun onKeyEvent(event: KeyEvent): Boolean {
        if (profile != null) {
            if (radialMenuPressed && !isRadialMenuOpenerDevice(event.deviceId)) return true
            val controller = profile?.getController(event.deviceId)
            if (controller != null) {
                val controllerBinding = controller.getControllerBinding(event.keyCode)
                if (radialMenuPressed && controllerBinding?.binding == Binding.OPEN_RADIAL_MENU) {
                    if (event.keyCode == radialMenuOpenerKeyCode ||
                        radialMenuOpenerKeyCode == KeyEvent.KEYCODE_UNKNOWN
                    ) {
                        handleInputEvent(
                            controllerBinding.binding,
                            event.action == KeyEvent.ACTION_DOWN,
                            sourceKeyCode = event.keyCode,
                            sourceDeviceId = event.deviceId,
                            sourceController = controller,
                        )
                        return true
                    }
                    handleRadialMenuNavigationKey(event)
                    return true
                }
                if (radialMenuPressed && handleRadialMenuNavigationKey(event)) {
                    return true
                }

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
                    handleInputEvent(
                        controllerBinding.binding,
                        event.action == KeyEvent.ACTION_DOWN,
                        offset,
                        sourceKeyCode = event.keyCode,
                        sourceDeviceId = event.deviceId,
                        sourceController = controller,
                    )

                    sendGamepadState()
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
            if (radialMenuPressed && !isRadialMenuOpenerDevice(event.deviceId)) return true
            val controller = profile?.getController(event.deviceId)
            if (controller != null && controller.updateStateFromMotionEvent(event)) {
                if (radialMenuPressed) {
                    updateRadialMenuVector(controller)
                    if (radialMenuOpenedFromMotion && !isRadialMenuMotionOpenerPressed(controller)) {
                        handleInputEvent(
                            Binding.OPEN_RADIAL_MENU,
                            false,
                            0f,
                            fromMotion = true,
                            sourceKeyCode = radialMenuOpenerKeyCode,
                            sourceDeviceId = event.deviceId,
                            sourceController = controller,
                        )
                    }
                    return true
                }

                val axisSettings = controller.axisSettings ?: ControllerAxisSettings()

                // Process trigger buttons (L2/R2) with analog curve/deadzone/cutoff and hair trigger support
                val rawL = controller.state.triggerL
                val rawR = controller.state.triggerR

                val processedL = if (axisSettings.leftTriggerHairTrigger) {
                    if (rawL >= axisSettings.leftTriggerThreshold) 1.0f else 0.0f
                } else {
                    if (rawL <= axisSettings.leftTriggerDeadzone) 0f
                    else {
                        val denom = (axisSettings.leftTriggerCutoff - axisSettings.leftTriggerDeadzone).coerceAtLeast(0.01f)
                        val normalized = ((rawL - axisSettings.leftTriggerDeadzone) / denom).coerceIn(0f, 1f)
                        Math.pow(normalized.toDouble(), axisSettings.leftTriggerCurve.toDouble()).toFloat().coerceIn(0f, 1f)
                    }
                }

                val processedR = if (axisSettings.rightTriggerHairTrigger) {
                    if (rawR >= axisSettings.rightTriggerThreshold) 1.0f else 0.0f
                } else {
                    if (rawR <= axisSettings.rightTriggerDeadzone) 0f
                    else {
                        val denom = (axisSettings.rightTriggerCutoff - axisSettings.rightTriggerDeadzone).coerceAtLeast(0.01f)
                        val normalized = ((rawR - axisSettings.rightTriggerDeadzone) / denom).coerceIn(0f, 1f)
                        Math.pow(normalized.toDouble(), axisSettings.rightTriggerCurve.toDouble()).toFloat().coerceIn(0f, 1f)
                    }
                }

                val l2Binding = controller.getControllerBinding(KeyEvent.KEYCODE_BUTTON_L2)
                if (l2Binding != null) {
                    handleInputEvent(
                        l2Binding.binding,
                        processedL > 0f,
                        processedL,
                        fromMotion = true,
                        sourceKeyCode = KeyEvent.KEYCODE_BUTTON_L2,
                        sourceDeviceId = event.deviceId,
                        sourceController = controller,
                    )
                    if (radialMenuPressed) {
                        sendGamepadState()
                        return true
                    }
                }

                val r2Binding = controller.getControllerBinding(KeyEvent.KEYCODE_BUTTON_R2)
                if (r2Binding != null) {
                    handleInputEvent(
                        r2Binding.binding,
                        processedR > 0f,
                        processedR,
                        fromMotion = true,
                        sourceKeyCode = KeyEvent.KEYCODE_BUTTON_R2,
                        sourceDeviceId = event.deviceId,
                        sourceController = controller,
                    )
                    if (radialMenuPressed) {
                        sendGamepadState()
                        return true
                    }
                }

                // Process analog stick input and obtain processed stick vectors
                val (procLX, procLY, procRX, procRY) = processJoystickInput(controller, event.deviceId)

                // ──── Conditionally sync processed axes to profile.gamepadState ────
                // Only sync axes whose bindings are gamepad bindings (or unbound).
                // If an axis is mapped to mouse/keyboard, do NOT write to gamepadState
                // to avoid "double input" (e.g. mouse move AND virtual stick simultaneously).
                val gamepadState = profile?.gamepadState
                if (gamepadState != null) {
                    // Check if left stick axes have gamepad bindings (or no bindings)
                    val lxPosBinding = controller.getControllerBinding(
                        ExternalControllerBinding.getKeyCodeForAxis(MotionEvent.AXIS_X, 1)
                    )
                    val lxNegBinding = controller.getControllerBinding(
                        ExternalControllerBinding.getKeyCodeForAxis(MotionEvent.AXIS_X, -1)
                    )
                    val lyPosBinding = controller.getControllerBinding(
                        ExternalControllerBinding.getKeyCodeForAxis(MotionEvent.AXIS_Y, 1)
                    )
                    val lyNegBinding = controller.getControllerBinding(
                        ExternalControllerBinding.getKeyCodeForAxis(MotionEvent.AXIS_Y, -1)
                    )

                    val lxIsGamepad = (lxPosBinding == null || lxPosBinding.binding.isGamepad) && (lxNegBinding == null || lxNegBinding.binding.isGamepad)
                    val lyIsGamepad = (lyPosBinding == null || lyPosBinding.binding.isGamepad) && (lyNegBinding == null || lyNegBinding.binding.isGamepad)

                    if (lxIsGamepad) gamepadState.thumbLX = procLX
                    if (lyIsGamepad) gamepadState.thumbLY = procLY

                    // Check if right stick axes have gamepad bindings (or no bindings)
                    val rxPosBinding = controller.getControllerBinding(
                        ExternalControllerBinding.getKeyCodeForAxis(MotionEvent.AXIS_Z, 1)
                    )
                    val rxNegBinding = controller.getControllerBinding(
                        ExternalControllerBinding.getKeyCodeForAxis(MotionEvent.AXIS_Z, -1)
                    )
                    val ryPosBinding = controller.getControllerBinding(
                        ExternalControllerBinding.getKeyCodeForAxis(MotionEvent.AXIS_RZ, 1)
                    )
                    val ryNegBinding = controller.getControllerBinding(
                        ExternalControllerBinding.getKeyCodeForAxis(MotionEvent.AXIS_RZ, -1)
                    )

                    val rxIsGamepad = (rxPosBinding == null || rxPosBinding.binding.isGamepad) && (rxNegBinding == null || rxNegBinding.binding.isGamepad)
                    val ryIsGamepad = (ryPosBinding == null || ryPosBinding.binding.isGamepad) && (ryNegBinding == null || ryNegBinding.binding.isGamepad)

                    if (rxIsGamepad) gamepadState.thumbRX = procRX
                    if (ryIsGamepad) gamepadState.thumbRY = procRY

                    // Check if trigger axes have gamepad bindings (or no bindings)
                    val l2BindingObj = controller.getControllerBinding(KeyEvent.KEYCODE_BUTTON_L2)
                    val r2BindingObj = controller.getControllerBinding(KeyEvent.KEYCODE_BUTTON_R2)

                    if (l2BindingObj == null || l2BindingObj.binding.isGamepad) {
                        gamepadState.triggerL = processedL
                    }
                    if (r2BindingObj == null || r2BindingObj.binding.isGamepad) {
                        gamepadState.triggerR = processedR
                    }

                    // Directly sync controller state and notify WinHandler
                    xServer?.winHandler?.let { wh ->
                        val currentCtrl = wh.currentController
                        if (currentCtrl != null) {
                            currentCtrl.state.thumbLX = gamepadState.thumbLX
                            currentCtrl.state.thumbLY = gamepadState.thumbLY
                            currentCtrl.state.thumbRX = gamepadState.thumbRX
                            currentCtrl.state.thumbRY = gamepadState.thumbRY
                            currentCtrl.state.triggerL = gamepadState.triggerL
                            currentCtrl.state.triggerR = gamepadState.triggerR
                        }
                    }
                }

                sendGamepadState()
                return true
            }
        }
        return false
    }

    private fun sendGamepadState() {
        val winHandler = xServer?.winHandler ?: return
        winHandler.sendGamepadState()
        winHandler.sendVirtualGamepadState(profile?.gamepadState)
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
                val bindings = synchronized(scrollRepeatLock) {
                    activeScrollBindings.toList()
                }
                bindings.forEach { sendScrollPulse(it) }
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
        val pointerButton = binding.pointerButton ?: return
        xServer?.injectPointerButtonPress(pointerButton)
        xServer?.injectPointerButtonRelease(pointerButton)
    }

    /**
     * Process physical joystick input, applying deadzone, sensitivity, and piecewise midpoint spline curves.
     */
    private fun processJoystickInput(controller: ExternalController, deviceId: Int): FloatArray {
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

        dispatchAxis(controller, MotionEvent.AXIS_X, procLX, deviceId)
        dispatchAxis(controller, MotionEvent.AXIS_Y, procLY, deviceId)

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

        dispatchAxis(controller, MotionEvent.AXIS_Z, procRX, deviceId)
        dispatchAxis(controller, MotionEvent.AXIS_RZ, procRY, deviceId)

        // 3. Process D-Pad Hat axes (Discrete digital axes)
        dispatchHatAxis(controller, MotionEvent.AXIS_HAT_X, controller.state.dPadX.toFloat(), deviceId)
        dispatchHatAxis(controller, MotionEvent.AXIS_HAT_Y, controller.state.dPadY.toFloat(), deviceId)

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
    private fun dispatchAxis(controller: ExternalController, axis: Int, processedVal: Float, deviceId: Int) {
        val sign = Math.signum(processedVal).toInt()
        val posKeyCode = ExternalControllerBinding.getKeyCodeForAxis(axis, 1.toByte())
        val negKeyCode = ExternalControllerBinding.getKeyCodeForAxis(axis, (-1).toByte())

        if (sign != 0) {
            val activeKey = if (sign > 0) posKeyCode else negKeyCode
            val oppositeKey = if (sign > 0) negKeyCode else posKeyCode

            activeAxisBindings.add(activeKey)
            controller.getControllerBinding(activeKey)?.let {
                handleInputEvent(
                    it.binding,
                    true,
                    Math.abs(processedVal),
                    fromMotion = true,
                    sourceKeyCode = activeKey,
                    sourceDeviceId = deviceId,
                    sourceController = controller,
                )
                if (radialMenuPressed) return
            }

            if (activeAxisBindings.remove(oppositeKey)) {
                controller.getControllerBinding(oppositeKey)?.let {
                    handleInputEvent(
                        it.binding,
                        false,
                        0f,
                        fromMotion = true,
                        sourceKeyCode = oppositeKey,
                        sourceDeviceId = deviceId,
                        sourceController = controller,
                    )
                }
            }
        } else {
            if (activeAxisBindings.remove(posKeyCode)) {
                controller.getControllerBinding(posKeyCode)?.let {
                    handleInputEvent(
                        it.binding,
                        false,
                        0f,
                        fromMotion = true,
                        sourceKeyCode = posKeyCode,
                        sourceDeviceId = deviceId,
                        sourceController = controller,
                    )
                }
            }
            if (activeAxisBindings.remove(negKeyCode)) {
                controller.getControllerBinding(negKeyCode)?.let {
                    handleInputEvent(
                        it.binding,
                        false,
                        0f,
                        fromMotion = true,
                        sourceKeyCode = negKeyCode,
                        sourceDeviceId = deviceId,
                        sourceController = controller,
                    )
                }
            }
        }
    }

    /**
     * Dispatches hat/D-pad axes to bindings or updates virtual gamepad D-pad state directly.
     */
    private fun dispatchHatAxis(controller: ExternalController, axis: Int, rawVal: Float, deviceId: Int) {
        val sign = Math.signum(rawVal).toInt()
        val posKeyCode = ExternalControllerBinding.getKeyCodeForAxis(axis, 1.toByte())
        val negKeyCode = ExternalControllerBinding.getKeyCodeForAxis(axis, (-1).toByte())

        if (sign != 0) {
            val activeKey = if (sign > 0) posKeyCode else negKeyCode
            val oppositeKey = if (sign > 0) negKeyCode else posKeyCode

            activeAxisBindings.add(activeKey)
            val binding = controller.getControllerBinding(activeKey)
            if (binding != null) {
                handleInputEvent(
                    binding.binding,
                    true,
                    Math.abs(rawVal),
                    fromMotion = true,
                    sourceKeyCode = activeKey,
                    sourceDeviceId = deviceId,
                    sourceController = controller,
                )
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
                controller.getControllerBinding(oppositeKey)?.let {
                    handleInputEvent(
                        it.binding,
                        false,
                        0f,
                        fromMotion = true,
                        sourceKeyCode = oppositeKey,
                        sourceDeviceId = deviceId,
                        sourceController = controller,
                    )
                }
            }
        } else {
            if (activeAxisBindings.remove(posKeyCode)) {
                controller.getControllerBinding(posKeyCode)?.let {
                    handleInputEvent(
                        it.binding,
                        false,
                        0f,
                        fromMotion = true,
                        sourceKeyCode = posKeyCode,
                        sourceDeviceId = deviceId,
                        sourceController = controller,
                    )
                }
            }
            if (activeAxisBindings.remove(negKeyCode)) {
                controller.getControllerBinding(negKeyCode)?.let {
                    handleInputEvent(
                        it.binding,
                        false,
                        0f,
                        fromMotion = true,
                        sourceKeyCode = negKeyCode,
                        sourceDeviceId = deviceId,
                        sourceController = controller,
                    )
                }
            }
            val state = profile?.gamepadState
            if (state != null) {
                if (axis == MotionEvent.AXIS_HAT_X) {
                    state.dpad[1] = false
                    state.dpad[3] = false
                } else if (axis == MotionEvent.AXIS_HAT_Y) {
                    state.dpad[0] = false
                    state.dpad[2] = false
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
    private fun handleInputEvent(
        binding: Binding,
        isActionDown: Boolean,
        offset: Float = 0f,
        fromMotion: Boolean = false,
        sourceKeyCode: Int = KeyEvent.KEYCODE_UNKNOWN,
        sourceDeviceId: Int = UNKNOWN_DEVICE_ID,
        sourceController: ExternalController? = null,
    ) {
        if (binding == Binding.NONE) return

        if (binding == Binding.OPEN_RADIAL_MENU) {
            if (!isActionDown && radialMenuPressed && !isRadialMenuOpenerDevice(sourceDeviceId)) return
            if (radialMenuPressed != isActionDown) {
                if (isActionDown) {
                    neutralizeMotionInputs(sourceKeyCode, sourceController)
                } else if (fromMotion && sourceKeyCode != KeyEvent.KEYCODE_UNKNOWN) {
                    activeAxisBindings.remove(sourceKeyCode)
                }
                radialMenuPressed = isActionDown
                radialMenuOpenedFromMotion = isActionDown && fromMotion
                radialMenuOpenerKeyCode = if (isActionDown) sourceKeyCode else KeyEvent.KEYCODE_UNKNOWN
                radialMenuOpenerDeviceId = if (isActionDown) sourceDeviceId else UNKNOWN_DEVICE_ID
                onRadialMenuButtonStateChanged?.invoke(isActionDown, true)
                if (!isActionDown) onRadialMenuVectorChanged?.invoke(0f, 0f)
            } else if (!isActionDown) {
                radialMenuOpenedFromMotion = false
                radialMenuOpenerKeyCode = KeyEvent.KEYCODE_UNKNOWN
                radialMenuOpenerDeviceId = UNKNOWN_DEVICE_ID
            }
            return
        }

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

    private fun closeRadialMenuIfOpen(commit: Boolean) {
        if (!radialMenuPressed) return
        radialMenuPressed = false
        radialMenuOpenedFromMotion = false
        radialMenuOpenerKeyCode = KeyEvent.KEYCODE_UNKNOWN
        radialMenuOpenerDeviceId = UNKNOWN_DEVICE_ID
        onRadialMenuVectorChanged?.invoke(0f, 0f)
        onRadialMenuButtonStateChanged?.invoke(false, commit)
    }

    private fun neutralizeMotionInputs(exceptKeyCode: Int, controller: ExternalController?) {
        releaseActiveAxes(exceptKeyCode)
        neutralizeTrigger(controller, KeyEvent.KEYCODE_BUTTON_L2, exceptKeyCode)
        neutralizeTrigger(controller, KeyEvent.KEYCODE_BUTTON_R2, exceptKeyCode)
        mouseMoveOffset.set(0f, 0f)
        clearScrollRepeats()
    }

    private fun neutralizeTrigger(controller: ExternalController?, keyCode: Int, exceptKeyCode: Int) {
        if (keyCode == exceptKeyCode) return
        val activeController = controller ?: profile?.getController("*") ?: return
        val triggerValue = when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_L2 -> activeController.state.triggerL
            KeyEvent.KEYCODE_BUTTON_R2 -> activeController.state.triggerR
            else -> 0f
        }
        if (triggerValue <= 0f) return
        activeController.getControllerBinding(keyCode)?.takeIf { it.binding != Binding.OPEN_RADIAL_MENU }?.let {
            handleInputEvent(it.binding, false, 0f, fromMotion = true, sourceKeyCode = keyCode)
        }
    }

    private fun isRadialMenuOpenerDevice(deviceId: Int): Boolean {
        return radialMenuOpenerDeviceId == UNKNOWN_DEVICE_ID || deviceId == radialMenuOpenerDeviceId
    }

    private fun handleRadialMenuNavigationKey(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) {
            return when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT,
                -> true
                else -> false
            }
        }
        val vector = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> 0f to -1f
            KeyEvent.KEYCODE_DPAD_DOWN -> 0f to 1f
            KeyEvent.KEYCODE_DPAD_LEFT -> -1f to 0f
            KeyEvent.KEYCODE_DPAD_RIGHT -> 1f to 0f
            else -> return false
        }
        onRadialMenuVectorChanged?.invoke(vector.first, vector.second)
        return true
    }

    private fun updateRadialMenuVector(controller: ExternalController) {
        val vector = radialSelectionVectorForAxes(
            controller.state.thumbLX,
            controller.state.thumbLY,
            MotionEvent.AXIS_X,
            MotionEvent.AXIS_Y,
        ) ?: radialSelectionVectorForAxes(
            controller.state.thumbRX,
            controller.state.thumbRY,
            MotionEvent.AXIS_Z,
            MotionEvent.AXIS_RZ,
        ) ?: radialSelectionVectorForAxes(
            controller.state.dPadX.toFloat(),
            controller.state.dPadY.toFloat(),
            MotionEvent.AXIS_HAT_X,
            MotionEvent.AXIS_HAT_Y,
        )

        if (vector == null) {
            onRadialMenuVectorChanged?.invoke(0f, 0f)
        } else {
            onRadialMenuVectorChanged?.invoke(vector.first, vector.second)
        }
    }

    private fun radialSelectionVectorForAxes(
        x: Float,
        y: Float,
        xAxis: Int,
        yAxis: Int,
    ): Pair<Float, Float>? {
        val filteredX = radialSelectionComponent(x, xAxis)
        val filteredY = radialSelectionComponent(y, yAxis)
        return if (Math.abs(filteredX) <= ControlElement.STICK_DEAD_ZONE &&
            Math.abs(filteredY) <= ControlElement.STICK_DEAD_ZONE
        ) {
            null
        } else {
            filteredX to filteredY
        }
    }

    private fun radialSelectionComponent(value: Float, axis: Int): Float {
        if (Math.abs(value) <= ControlElement.STICK_DEAD_ZONE) return 0f
        val keyCode = ExternalControllerBinding.getKeyCodeForAxis(axis, Mathf.sign(value))
        return if (keyCode == radialMenuOpenerKeyCode) 0f else value
    }

    private fun isRadialMenuMotionOpenerPressed(controller: ExternalController): Boolean {
        val axes = intArrayOf(
            MotionEvent.AXIS_X,
            MotionEvent.AXIS_Y,
            MotionEvent.AXIS_Z,
            MotionEvent.AXIS_RZ,
            MotionEvent.AXIS_HAT_X,
            MotionEvent.AXIS_HAT_Y,
        )
        val values = floatArrayOf(
            controller.state.thumbLX,
            controller.state.thumbLY,
            controller.state.thumbRX,
            controller.state.thumbRY,
            controller.state.dPadX.toFloat(),
            controller.state.dPadY.toFloat(),
        )

        for (i in axes.indices) {
            if (Math.abs(values[i]) <= ControlElement.STICK_DEAD_ZONE) continue
            val keyCode = ExternalControllerBinding.getKeyCodeForAxis(axes[i], Mathf.sign(values[i]))
            if (keyCode == radialMenuOpenerKeyCode) {
                return true
            }
        }

        return (radialMenuOpenerKeyCode == KeyEvent.KEYCODE_BUTTON_L2 && controller.state.triggerL > 0f) ||
            (radialMenuOpenerKeyCode == KeyEvent.KEYCODE_BUTTON_R2 && controller.state.triggerR > 0f)
    }
}
