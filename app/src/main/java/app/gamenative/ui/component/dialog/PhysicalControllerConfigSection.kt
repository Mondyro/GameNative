package app.gamenative.ui.component.dialog

import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gamenative.R
import com.winlator.inputcontrols.Binding
import com.winlator.inputcontrols.ControllerAxisSettings
import com.winlator.inputcontrols.ControlsProfile
import com.winlator.inputcontrols.ExternalControllerBinding

/**
 * Data classes for controller configuration
 */
private data class ButtonConfig(val label: String, val keyCode: Int)
private data class AnalogConfig(val label: String, val axis: Int, val sign: Int)

/**
 * Physical Controller Configuration with two-column categorized layout
 *
 * Shows controller bindings organized by categories (Buttons, Left Stick, Right Stick, D-Pad, Analog Settings)
 * with a UI matching the ControllerBindingDialog design.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PhysicalControllerConfigSection(
    profile: ControlsProfile,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    val context = LocalContext.current

    // Ensure a wildcard controller exists for all physical controllers
    val controller = remember {
        var ctrl = profile.getController("*")
        if (ctrl == null) {
            Log.d("gncontrol", "=== Physical Controller Init: Creating wildcard controller for profile: ${profile.name} (ID: ${profile.id}) ===")
            ctrl = profile.addController("*")

            // Copy default bindings from the Physical Controller Default profile (ID 0)
            val manager = com.winlator.inputcontrols.InputControlsManager(context)
            val defaultProfile = manager.getProfile(0)
            if (defaultProfile != null) {
                Log.d("gncontrol", "Loading defaults from profile: ${defaultProfile.name} (ID: ${defaultProfile.id})")
                val defaultControllers = defaultProfile.getControllers()
                if (defaultControllers.isNotEmpty()) {
                    val defaultController = defaultControllers[0]
                    val bindingCount = defaultController.getControllerBindings().size
                    Log.d("gncontrol", "Copying $bindingCount default controller bindings from ${defaultProfile.name}")
                    for (binding in defaultController.getControllerBindings()) {
                        val newBinding = ExternalControllerBinding()
                        newBinding.setKeyCode(binding.getKeyCodeForAxis())
                        newBinding.setBinding(binding.getBinding())
                        ctrl.addControllerBinding(newBinding)
                    }

                    // Ensure Home/Guide/PS button is always set to OPEN_NAVIGATION_MENU
                    val homeButtonBinding = ExternalControllerBinding()
                    homeButtonBinding.setKeyCode(KeyEvent.KEYCODE_BUTTON_MODE)
                    homeButtonBinding.setBinding(com.winlator.inputcontrols.Binding.OPEN_NAVIGATION_MENU)
                    // Remove any existing home button binding first
                    val existingHomeBinding = ctrl.getControllerBindings().find {
                        it.getKeyCodeForAxis() == KeyEvent.KEYCODE_BUTTON_MODE
                    }
                    if (existingHomeBinding != null) {
                        ctrl.removeControllerBinding(existingHomeBinding)
                    }
                    ctrl.addControllerBinding(homeButtonBinding)
                    Log.d("gncontrol", "Set Home button (KEYCODE_BUTTON_MODE) to OPEN_NAVIGATION_MENU")
                } else {
                    Log.w("gncontrol", "No controllers found in default profile ${defaultProfile.name}")
                }

                // Copy on-screen elements from default profile if current profile has empty/NONE elements
                copyElementsIfNeeded(context, profile, defaultProfile)
            } else {
                Log.w("gncontrol", "Default profile 0 not found, wildcard controller will be empty")
            }

            profile.save()
        }
        ctrl
    }

    // Create a snapshot of original bindings and axis settings for cancel behavior
    val originalBindings = remember {
        controller?.getControllerBindings()?.map {
            it.getKeyCodeForAxis() to it.getBinding()
        }?.toMap() ?: emptyMap()
    }
    val originalAxisSettings = remember {
        controller?.axisSettings?.copy() ?: ControllerAxisSettings()
    }

    // Working copy of bindings (memory only until Save is clicked)
    val workingBindings = remember { mutableStateMapOf<Int, com.winlator.inputcontrols.Binding?>() }
    var workingAxisSettings by remember {
        mutableStateOf(controller?.axisSettings?.copy() ?: ControllerAxisSettings())
    }

    // Initialize working copy with current bindings
    LaunchedEffect(controller) {
        controller?.getControllerBindings()?.forEach {
            workingBindings[it.getKeyCodeForAxis()] = it.getBinding()
        }
    }

    var selectedCategory by remember { mutableStateOf(0) } // 0 = Face, 1 = Shoulder, 2 = Menu, 3 = Thumbstick, 4 = Left Stick, 5 = Right Stick, 6 = D-Pad, 7 = Analog Settings
    var showBindingDialog by remember { mutableStateOf<Pair<Int, String>?>(null) }
    var showAnalogHelpDialog by remember { mutableStateOf(false) }
    var showUnsavedChangesDialog by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableIntStateOf(0) }

    fun hasUnsavedChanges(): Boolean {
        val currentBindingsMap = workingBindings.filterValues { it != null && it != com.winlator.inputcontrols.Binding.NONE }
        val origBindingsMap = originalBindings.filterValues { it != com.winlator.inputcontrols.Binding.NONE }
        val bindingsChanged = currentBindingsMap != origBindingsMap
        val axisChanged = workingAxisSettings != originalAxisSettings
        return bindingsChanged || axisChanged
    }

    fun performSave() {
        Log.d("gncontrol", "=== Save: Applying ${workingBindings.size} bindings and axis settings ===")
        controller?.let { ctrl ->
            val existingBindings = ctrl.getControllerBindings().toList()
            for (binding in existingBindings) {
                ctrl.removeControllerBinding(binding)
            }

            for ((keyCode, binding) in workingBindings) {
                if (binding != null && binding != com.winlator.inputcontrols.Binding.NONE) {
                    val newBinding = ExternalControllerBinding()
                    newBinding.setKeyCode(keyCode)
                    newBinding.setBinding(binding)
                    ctrl.addControllerBinding(newBinding)
                }
            }

            ctrl.axisSettings = workingAxisSettings.copy()

            val manager = com.winlator.inputcontrols.InputControlsManager(context)
            val defaultProfile = manager.getProfile(0)
            if (defaultProfile != null) {
                copyElementsIfNeeded(context, profile, defaultProfile)
            }

            profile.save()
            Log.d("gncontrol", "Saved profile ${profile.name}")
        }
        onSave()
    }

    fun performDiscard() {
        controller?.let { ctrl ->
            val existingBindings = ctrl.getControllerBindings().toList()
            for (binding in existingBindings) {
                ctrl.removeControllerBinding(binding)
            }
            for ((keyCode, binding) in originalBindings) {
                val newBinding = ExternalControllerBinding()
                newBinding.setKeyCode(keyCode)
                newBinding.setBinding(binding)
                ctrl.addControllerBinding(newBinding)
            }
            ctrl.axisSettings = originalAxisSettings.copy()
        }
        onDismiss()
    }

    fun handleBackPress() {
        if (hasUnsavedChanges()) {
            showUnsavedChangesDialog = true
        } else {
            performDiscard()
        }
    }

    // Pre-compute all button configurations
    // Face buttons
    val faceButtons = remember {
        listOf(
            ButtonConfig(context.getString(R.string.button_a), KeyEvent.KEYCODE_BUTTON_A),
            ButtonConfig(context.getString(R.string.button_b), KeyEvent.KEYCODE_BUTTON_B),
            ButtonConfig(context.getString(R.string.button_x), KeyEvent.KEYCODE_BUTTON_X),
            ButtonConfig(context.getString(R.string.button_y), KeyEvent.KEYCODE_BUTTON_Y)
        )
    }

    // Shoulder buttons
    val shoulderButtons = remember {
        listOf(
            ButtonConfig(context.getString(R.string.button_l1), KeyEvent.KEYCODE_BUTTON_L1),
            ButtonConfig(context.getString(R.string.button_r1), KeyEvent.KEYCODE_BUTTON_R1),
            ButtonConfig(context.getString(R.string.button_l2), KeyEvent.KEYCODE_BUTTON_L2),
            ButtonConfig(context.getString(R.string.button_r2), KeyEvent.KEYCODE_BUTTON_R2)
        )
    }

    // Menu buttons
    val menuButtons = remember {
        listOf(
            ButtonConfig(context.getString(R.string.button_start), KeyEvent.KEYCODE_BUTTON_START),
            ButtonConfig(context.getString(R.string.button_select), KeyEvent.KEYCODE_BUTTON_SELECT),
            ButtonConfig(context.getString(R.string.button_home), KeyEvent.KEYCODE_BUTTON_MODE)
        )
    }

    // Thumbstick buttons
    val thumbstickButtons = remember {
        listOf(
            ButtonConfig(context.getString(R.string.button_l3), KeyEvent.KEYCODE_BUTTON_THUMBL),
            ButtonConfig(context.getString(R.string.button_r3), KeyEvent.KEYCODE_BUTTON_THUMBR)
        )
    }

    // D-Pad
    val dpadButtons = remember {
        listOf(
            ButtonConfig(context.getString(R.string.dpad_up), KeyEvent.KEYCODE_DPAD_UP),
            ButtonConfig(context.getString(R.string.dpad_down), KeyEvent.KEYCODE_DPAD_DOWN),
            ButtonConfig(context.getString(R.string.dpad_left), KeyEvent.KEYCODE_DPAD_LEFT),
            ButtonConfig(context.getString(R.string.dpad_right), KeyEvent.KEYCODE_DPAD_RIGHT)
        )
    }

    // Left analog stick
    val leftStickAxes = remember {
        listOf(
            AnalogConfig(context.getString(R.string.left_stick_up), MotionEvent.AXIS_Y, -1),
            AnalogConfig(context.getString(R.string.left_stick_down), MotionEvent.AXIS_Y, 1),
            AnalogConfig(context.getString(R.string.left_stick_left), MotionEvent.AXIS_X, -1),
            AnalogConfig(context.getString(R.string.left_stick_right), MotionEvent.AXIS_X, 1)
        )
    }

    // Right analog stick
    val rightStickAxes = remember {
        listOf(
            AnalogConfig(context.getString(R.string.right_stick_up), MotionEvent.AXIS_RZ, -1),
            AnalogConfig(context.getString(R.string.right_stick_down), MotionEvent.AXIS_RZ, 1),
            AnalogConfig(context.getString(R.string.right_stick_left), MotionEvent.AXIS_Z, -1),
            AnalogConfig(context.getString(R.string.right_stick_right), MotionEvent.AXIS_Z, 1)
        )
    }

    Dialog(
        onDismissRequest = {
            handleBackPress()
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        val view = LocalView.current
        var liveStickLX by remember { mutableFloatStateOf(0f) }
        var liveStickLY by remember { mutableFloatStateOf(0f) }
        var liveStickRX by remember { mutableFloatStateOf(0f) }
        var liveStickRY by remember { mutableFloatStateOf(0f) }
        var liveTriggerL by remember { mutableFloatStateOf(0f) }
        var liveTriggerR by remember { mutableFloatStateOf(0f) }
        var isTestModeActive by remember { mutableStateOf(false) }

        val firstCategoryFocusRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(150)
            try {
                firstCategoryFocusRequester.requestFocus()
            } catch (_: Exception) {}
        }

        val currentCategoryState = rememberUpdatedState(selectedCategory)

        LaunchedEffect(selectedCategory) {
            if (selectedCategory != 7 && isTestModeActive) {
                isTestModeActive = false
            }
        }

        DisposableEffect(view, isTestModeActive, selectedCategory) {
            val motionListener = android.view.View.OnGenericMotionListener { _, event ->
                if ((event.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK ||
                    (event.source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                ) {
                    // Check if event is from HAT axes (D-pad emulation on some controllers)
                    val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
                    val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
                    val isHatMotion = Math.abs(hatX) > 0.01f || Math.abs(hatY) > 0.01f

                    liveStickLX = event.getAxisValue(MotionEvent.AXIS_X)
                    liveStickLY = event.getAxisValue(MotionEvent.AXIS_Y)

                    // Determine Right Stick axes using device motion ranges (prevents trigger cross-talk)
                    val device = event.device
                    val hasZ = device?.getMotionRange(MotionEvent.AXIS_Z, event.source) != null
                    val hasRZ = device?.getMotionRange(MotionEvent.AXIS_RZ, event.source) != null
                    val hasRX = device?.getMotionRange(MotionEvent.AXIS_RX, event.source) != null
                    val hasRY = device?.getMotionRange(MotionEvent.AXIS_RY, event.source) != null

                    liveStickRX = if (hasZ) event.getAxisValue(MotionEvent.AXIS_Z) else if (hasRX) event.getAxisValue(MotionEvent.AXIS_RX) else 0f
                    liveStickRY = if (hasRZ) event.getAxisValue(MotionEvent.AXIS_RZ) else if (hasRY) event.getAxisValue(MotionEvent.AXIS_RY) else 0f

                    val lTrig = event.getAxisValue(MotionEvent.AXIS_LTRIGGER)
                    val brake = event.getAxisValue(MotionEvent.AXIS_BRAKE)
                    val trigL = if (Math.abs(lTrig) > 0.001f) lTrig else brake

                    val rTrig = event.getAxisValue(MotionEvent.AXIS_RTRIGGER)
                    val gas = event.getAxisValue(MotionEvent.AXIS_GAS)
                    val trigR = if (Math.abs(rTrig) > 0.001f) rTrig else gas

                    liveTriggerL = trigL.coerceIn(0f, 1f)
                    liveTriggerR = trigR.coerceIn(0f, 1f)

                    if (isHatMotion) {
                        false // Always allow HAT D-pad to navigate UI!
                    } else {
                        isTestModeActive
                    }
                } else {
                    false
                }
            }

            val keyListener = android.view.View.OnKeyListener { _, keyCode, event ->
                // Y / Triangle button toggles Test Mode ONLY when on Analog Settings tab (selectedCategory == 7)
                if (currentCategoryState.value == 7 && keyCode == KeyEvent.KEYCODE_BUTTON_Y && event.action == KeyEvent.ACTION_UP) {
                    isTestModeActive = !isTestModeActive
                    return@OnKeyListener true
                }
                // B / Back button prompts to save if changes were made
                if ((keyCode == KeyEvent.KEYCODE_BUTTON_B || keyCode == KeyEvent.KEYCODE_BACK) && event.action == KeyEvent.ACTION_UP) {
                    if (showBindingDialog == null && !showAnalogHelpDialog && !showUnsavedChangesDialog && !isTestModeActive) {
                        handleBackPress()
                        return@OnKeyListener true
                    }
                }
                false
            }

            view.isFocusable = true
            view.isFocusableInTouchMode = true
            view.setOnGenericMotionListener(motionListener)
            view.rootView?.setOnGenericMotionListener(motionListener)
            view.setOnKeyListener(keyListener)
            view.rootView?.setOnKeyListener(keyListener)

            onDispose {
                view.setOnGenericMotionListener(null)
                view.rootView?.setOnGenericMotionListener(null)
                view.setOnKeyListener(null)
                view.rootView?.setOnKeyListener(null)
            }
        }

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            text = stringResource(R.string.physical_controller_config),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            handleBackPress()
                        }) {
                            Icon(Icons.Default.Close, null)
                        }
                    },
                    actions = {
                        // Info Guide Button (only when in Analog Settings tab)
                        if (selectedCategory == 7) {
                            IconButton(onClick = { showAnalogHelpDialog = true }) {
                                Icon(Icons.Default.Info, contentDescription = "Analog Guide")
                            }
                        }

                        // Reset button
                        IconButton(onClick = {
                            Log.d("gncontrol", "=== Reset: Resetting controller bindings ===")
                            workingBindings.clear()

                            val manager = com.winlator.inputcontrols.InputControlsManager(context)
                            val defaultProfile = manager.getProfile(0)
                            if (defaultProfile != null) {
                                val defaultControllers = defaultProfile.getControllers()
                                if (defaultControllers.isNotEmpty()) {
                                    val defaultController = defaultControllers[0]
                                    for (binding in defaultController.getControllerBindings()) {
                                        workingBindings[binding.getKeyCodeForAxis()] = binding.getBinding()
                                    }
                                }
                            }

                            // Ensure Home/Guide/PS button is always set to OPEN_NAVIGATION_MENU
                            workingBindings[KeyEvent.KEYCODE_BUTTON_MODE] = com.winlator.inputcontrols.Binding.OPEN_NAVIGATION_MENU
                            Log.d("gncontrol", "Set Home button (KEYCODE_BUTTON_MODE) to OPEN_NAVIGATION_MENU")

                            workingAxisSettings = ControllerAxisSettings()

                            refreshKey++
                        }) {
                            Icon(Icons.Default.Refresh, null)
                        }

                        // Save button
                        IconButton(onClick = {
                            performSave()
                        }) {
                            Icon(Icons.Default.Save, null)
                        }
                    }
                )
            }
        ) { padding ->
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                color = MaterialTheme.colorScheme.surface
            ) {
                // Two-column layout: Left = Categories, Right = Bindings list / settings
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Left column: Category selection (compact 22% width)
                    Column(
                        modifier = Modifier
                            .weight(0.22f)
                            .fillMaxHeight()
                            .padding(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Scrollable categories
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            // Face Buttons category
                        CategoryButton(
                            label = stringResource(R.string.face_buttons_category),
                            isSelected = selectedCategory == 0,
                            onClick = { selectedCategory = 0 },
                            modifier = Modifier.focusRequester(firstCategoryFocusRequester)
                        )

                        // Shoulder Buttons category
                        CategoryButton(
                            label = stringResource(R.string.shoulder_buttons_category),
                            isSelected = selectedCategory == 1,
                            onClick = { selectedCategory = 1 }
                        )

                        // Menu Buttons category
                        CategoryButton(
                            label = stringResource(R.string.menu_buttons_category),
                            isSelected = selectedCategory == 2,
                            onClick = { selectedCategory = 2 }
                        )

                        // Thumbstick Buttons category
                        CategoryButton(
                            label = stringResource(R.string.thumbstick_buttons_category),
                            isSelected = selectedCategory == 3,
                            onClick = { selectedCategory = 3 }
                        )

                        // Left Stick category
                        CategoryButton(
                            label = stringResource(R.string.left_stick),
                            isSelected = selectedCategory == 4,
                            onClick = { selectedCategory = 4 }
                        )

                        // Right Stick category
                        CategoryButton(
                            label = stringResource(R.string.right_stick),
                            isSelected = selectedCategory == 5,
                            onClick = { selectedCategory = 5 }
                        )

                        // D-Pad category
                        CategoryButton(
                            label = stringResource(R.string.dpad_category),
                            isSelected = selectedCategory == 6,
                            onClick = { selectedCategory = 6 }
                        )

                        // Analog Settings category
                        CategoryButton(
                            label = stringResource(R.string.analog_settings_category),
                            isSelected = selectedCategory == 7,
                            onClick = { selectedCategory = 7 }
                        )
                        }
                    }

                    // Right column: Bindings list / settings (expanded 78% width)
                    key(refreshKey) {
                        if (selectedCategory == 7) {
                            // Analog Settings: Pinned Live HUD at top, scrollable accordions below
                            Box(
                                modifier = Modifier
                                    .weight(0.78f)
                                    .fillMaxHeight()
                                    .padding(vertical = 4.dp)
                            ) {
                                AnalogSettingsSection(
                                    settings = workingAxisSettings,
                                    onSettingsChanged = { updated ->
                                        workingAxisSettings = updated
                                    },
                                    liveStickLX = liveStickLX,
                                    liveStickLY = liveStickLY,
                                    liveStickRX = liveStickRX,
                                    liveStickRY = liveStickRY,
                                    liveTriggerL = liveTriggerL,
                                    liveTriggerR = liveTriggerR,
                                    isTestModeActive = isTestModeActive,
                                    onToggleTestMode = { isTestModeActive = !isTestModeActive }
                                )
                            }
                        } else {
                            Column(
                                modifier = Modifier
                                    .weight(0.78f)
                                    .fillMaxHeight()
                                    .verticalScroll(rememberScrollState())
                                    .padding(vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                when (selectedCategory) {
                                    0 -> {
                                    // Face Buttons
                                    faceButtons.forEach { buttonConfig ->
                                        ControllerBindingItem(
                                            label = buttonConfig.label,
                                            keyCode = buttonConfig.keyCode,
                                            workingBindings = workingBindings,
                                            onClick = {
                                                showBindingDialog = Pair(buttonConfig.keyCode, buttonConfig.label)
                                            }
                                        )
                                    }
                                }
                                1 -> {
                                    // Shoulder Buttons
                                    shoulderButtons.forEach { buttonConfig ->
                                        ControllerBindingItem(
                                            label = buttonConfig.label,
                                            keyCode = buttonConfig.keyCode,
                                            workingBindings = workingBindings,
                                            onClick = {
                                                showBindingDialog = Pair(buttonConfig.keyCode, buttonConfig.label)
                                            }
                                        )
                                    }
                                }
                                2 -> {
                                    // Menu Buttons
                                    menuButtons.forEach { buttonConfig ->
                                        ControllerBindingItem(
                                            label = buttonConfig.label,
                                            keyCode = buttonConfig.keyCode,
                                            workingBindings = workingBindings,
                                            onClick = {
                                                showBindingDialog = Pair(buttonConfig.keyCode, buttonConfig.label)
                                            }
                                        )
                                    }
                                }
                                3 -> {
                                    // Thumbstick Buttons
                                    thumbstickButtons.forEach { buttonConfig ->
                                        ControllerBindingItem(
                                            label = buttonConfig.label,
                                            keyCode = buttonConfig.keyCode,
                                            workingBindings = workingBindings,
                                            onClick = {
                                                showBindingDialog = Pair(buttonConfig.keyCode, buttonConfig.label)
                                            }
                                        )
                                    }
                                }
                                4 -> {
                                    // Left Stick - Quick Presets
                                    PhysicalControlPresets(
                                        presetType = PhysicalPresetTarget.LEFT_STICK,
                                        leftStickAxes = leftStickAxes,
                                        rightStickAxes = rightStickAxes,
                                        dpadButtons = dpadButtons,
                                        workingBindings = workingBindings,
                                        onPresetsApplied = { refreshKey++ }
                                    )

                                    // Left Stick bindings
                                    leftStickAxes.forEach { analogConfig ->
                                        val keyCode = ExternalControllerBinding.getKeyCodeForAxis(
                                            analogConfig.axis,
                                            analogConfig.sign.toByte()
                                        )
                                        ControllerBindingItem(
                                            label = analogConfig.label,
                                            keyCode = keyCode,
                                            workingBindings = workingBindings,
                                            onClick = {
                                                showBindingDialog = Pair(keyCode, analogConfig.label)
                                            }
                                        )
                                    }
                                }
                                5 -> {
                                    // Right Stick - Quick Presets
                                    PhysicalControlPresets(
                                        presetType = PhysicalPresetTarget.RIGHT_STICK,
                                        leftStickAxes = leftStickAxes,
                                        rightStickAxes = rightStickAxes,
                                        dpadButtons = dpadButtons,
                                        workingBindings = workingBindings,
                                        onPresetsApplied = { refreshKey++ }
                                    )

                                    // Right Stick bindings
                                    rightStickAxes.forEach { analogConfig ->
                                        val keyCode = ExternalControllerBinding.getKeyCodeForAxis(
                                            analogConfig.axis,
                                            analogConfig.sign.toByte()
                                        )
                                        ControllerBindingItem(
                                            label = analogConfig.label,
                                            keyCode = keyCode,
                                            workingBindings = workingBindings,
                                            onClick = {
                                                showBindingDialog = Pair(keyCode, analogConfig.label)
                                            }
                                        )
                                    }
                                }
                                6 -> {
                                    // D-Pad - Quick Presets
                                    PhysicalControlPresets(
                                        presetType = PhysicalPresetTarget.DPAD,
                                        leftStickAxes = leftStickAxes,
                                        rightStickAxes = rightStickAxes,
                                        dpadButtons = dpadButtons,
                                        workingBindings = workingBindings,
                                        onPresetsApplied = { refreshKey++ }
                                    )

                                    // D-Pad bindings
                                    dpadButtons.forEach { buttonConfig ->
                                        ControllerBindingItem(
                                            label = buttonConfig.label,
                                            keyCode = buttonConfig.keyCode,
                                            workingBindings = workingBindings,
                                            onClick = {
                                                showBindingDialog = Pair(buttonConfig.keyCode, buttonConfig.label)
                                            }
                                        )
                                    }
                                }
                                    7 -> {
                                        // Handled above in dedicated pinned box
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Binding selector dialog
    showBindingDialog?.let { (keyCode, label) ->
        val currentBinding = workingBindings[keyCode]

        ControllerBindingDialog(
            buttonName = label,
            currentBinding = currentBinding,
            onDismiss = { showBindingDialog = null },
            onBindingSelected = { binding ->
                if (binding != null) {
                    workingBindings[keyCode] = binding
                    Log.d("gncontrol", "Updated binding for keyCode $keyCode to $binding")
                } else {
                    workingBindings.remove(keyCode)
                    Log.d("gncontrol", "Removed binding for keyCode $keyCode")
                }

                refreshKey++
                showBindingDialog = null
            }
        )
    }

    // Analog Settings Info / Help Guide Dialog
    if (showAnalogHelpDialog) {
        AlertDialog(
            onDismissRequest = { showAnalogHelpDialog = false },
            title = {
                Text(
                    text = stringResource(R.string.analog_help_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = stringResource(R.string.analog_help_deadzone),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = stringResource(R.string.analog_help_sensitivity),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = stringResource(R.string.analog_help_curve),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = stringResource(R.string.analog_help_hair_trigger),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showAnalogHelpDialog = false }) {
                    Text("Got it")
                }
            }
        )
    }

    // Unsaved Changes Confirmation Dialog
    if (showUnsavedChangesDialog) {
        AlertDialog(
            onDismissRequest = { showUnsavedChangesDialog = false },
            title = {
                Text(
                    text = "Save Controller Changes?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "You have unsaved controller mappings or analog settings. Would you like to save your changes before returning to the game?",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showUnsavedChangesDialog = false
                        performSave()
                    }
                ) {
                    Text("Save & Exit", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = { showUnsavedChangesDialog = false }
                    ) {
                        Text("Cancel")
                    }
                    TextButton(
                        onClick = {
                            showUnsavedChangesDialog = false
                            performDiscard()
                        },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Discard")
                    }
                }
            }
        )
    }
}

@Composable
private fun CategoryButton(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isFocused = remember { mutableStateOf(false) }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused.value = it.isFocused }
            .border(
                width = if (isFocused.value) 2.dp else if (isSelected) 1.5.dp else 0.dp,
                color = if (isFocused.value) MaterialTheme.colorScheme.primary else if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f) else Color.Transparent,
                shape = MaterialTheme.shapes.small
            )
            .clickable(onClick = onClick)
            .focusable(),
        color = if (isSelected)
            MaterialTheme.colorScheme.primaryContainer
        else
            MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (isSelected || isFocused.value) FontWeight.Bold else FontWeight.Normal,
            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun ControllerBindingItem(
    label: String,
    keyCode: Int,
    workingBindings: Map<Int, com.winlator.inputcontrols.Binding?>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val binding = workingBindings[keyCode]
    val bindingText = binding?.toString() ?: stringResource(R.string.not_set)
    val isFocused = remember { mutableStateOf(false) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused.value = it.isFocused }
            .border(
                width = if (isFocused.value) 2.dp else 0.dp,
                color = if (isFocused.value) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = MaterialTheme.shapes.small
            )
            .clickable(onClick = onClick)
            .focusable(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = bindingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Quick preset buttons for physical controller stick/dpad bindings
 */
@Composable
private fun PhysicalControlPresets(
    presetType: PhysicalPresetTarget,
    leftStickAxes: List<AnalogConfig>,
    rightStickAxes: List<AnalogConfig>,
    dpadButtons: List<ButtonConfig>,
    workingBindings: MutableMap<Int, Binding?>,
    onPresetsApplied: () -> Unit = {}
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = stringResource(R.string.quick_presets),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )

            // Keyboard/Mouse presets
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        applyPhysicalPreset(
                            presetType,
                            PhysicalPresetBinding.WASD,
                            leftStickAxes,
                            rightStickAxes,
                            dpadButtons,
                            workingBindings
                        )
                        onPresetsApplied()
                    },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp)
                ) {
                    Text(stringResource(R.string.preset_wasd), style = MaterialTheme.typography.labelSmall)
                }
                OutlinedButton(
                    onClick = {
                        applyPhysicalPreset(
                            presetType,
                            PhysicalPresetBinding.ARROW_KEYS,
                            leftStickAxes,
                            rightStickAxes,
                            dpadButtons,
                            workingBindings
                        )
                        onPresetsApplied()
                    },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp)
                ) {
                    Text(stringResource(R.string.preset_arrows), style = MaterialTheme.typography.labelSmall)
                }
                OutlinedButton(
                    onClick = {
                        applyPhysicalPreset(
                            presetType,
                            PhysicalPresetBinding.MOUSE_MOVE,
                            leftStickAxes,
                            rightStickAxes,
                            dpadButtons,
                            workingBindings
                        )
                        onPresetsApplied()
                    },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp)
                ) {
                    Text(stringResource(R.string.preset_mouse), style = MaterialTheme.typography.labelSmall)
                }
            }

            // Gamepad presets
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        applyPhysicalPreset(
                            presetType,
                            PhysicalPresetBinding.DPAD,
                            leftStickAxes,
                            rightStickAxes,
                            dpadButtons,
                            workingBindings
                        )
                        onPresetsApplied()
                    },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp)
                ) {
                    Text(stringResource(R.string.preset_dpad), style = MaterialTheme.typography.labelSmall)
                }
                OutlinedButton(
                    onClick = {
                        applyPhysicalPreset(
                            presetType,
                            PhysicalPresetBinding.LEFT_STICK,
                            leftStickAxes,
                            rightStickAxes,
                            dpadButtons,
                            workingBindings
                        )
                        onPresetsApplied()
                    },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp)
                ) {
                    Text(stringResource(R.string.preset_left_stick), style = MaterialTheme.typography.labelSmall)
                }
                OutlinedButton(
                    onClick = {
                        applyPhysicalPreset(
                            presetType,
                            PhysicalPresetBinding.RIGHT_STICK,
                            leftStickAxes,
                            rightStickAxes,
                            dpadButtons,
                            workingBindings
                        )
                        onPresetsApplied()
                    },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp)
                ) {
                    Text(stringResource(R.string.preset_right_stick), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

/**
 * Target for physical controller presets
 */
private enum class PhysicalPresetTarget {
    LEFT_STICK, RIGHT_STICK, DPAD
}

/**
 * Binding presets for physical controller inputs
 */
private enum class PhysicalPresetBinding {
    WASD, ARROW_KEYS, MOUSE_MOVE, DPAD, LEFT_STICK, RIGHT_STICK
}

/**
 * Apply a preset binding to physical controller inputs
 */
private fun applyPhysicalPreset(
    target: PhysicalPresetTarget,
    preset: PhysicalPresetBinding,
    leftStickAxes: List<AnalogConfig>,
    rightStickAxes: List<AnalogConfig>,
    dpadButtons: List<ButtonConfig>,
    workingBindings: MutableMap<Int, com.winlator.inputcontrols.Binding?>
) {
    // Define bindings for each preset (Up, Down, Left, Right order for sticks; Up, Down, Left, Right for dpad buttons)
    val bindings = when (preset) {
        PhysicalPresetBinding.WASD -> listOf(
            com.winlator.inputcontrols.Binding.KEY_W,
            com.winlator.inputcontrols.Binding.KEY_S,
            com.winlator.inputcontrols.Binding.KEY_A,
            com.winlator.inputcontrols.Binding.KEY_D
        )
        PhysicalPresetBinding.ARROW_KEYS -> listOf(
            com.winlator.inputcontrols.Binding.KEY_UP,
            com.winlator.inputcontrols.Binding.KEY_DOWN,
            com.winlator.inputcontrols.Binding.KEY_LEFT,
            com.winlator.inputcontrols.Binding.KEY_RIGHT
        )
        PhysicalPresetBinding.MOUSE_MOVE -> listOf(
            com.winlator.inputcontrols.Binding.MOUSE_MOVE_UP,
            com.winlator.inputcontrols.Binding.MOUSE_MOVE_DOWN,
            com.winlator.inputcontrols.Binding.MOUSE_MOVE_LEFT,
            com.winlator.inputcontrols.Binding.MOUSE_MOVE_RIGHT
        )
        PhysicalPresetBinding.DPAD -> listOf(
            com.winlator.inputcontrols.Binding.GAMEPAD_DPAD_UP,
            com.winlator.inputcontrols.Binding.GAMEPAD_DPAD_DOWN,
            com.winlator.inputcontrols.Binding.GAMEPAD_DPAD_LEFT,
            com.winlator.inputcontrols.Binding.GAMEPAD_DPAD_RIGHT
        )
        PhysicalPresetBinding.LEFT_STICK -> listOf(
            com.winlator.inputcontrols.Binding.GAMEPAD_LEFT_THUMB_UP,
            com.winlator.inputcontrols.Binding.GAMEPAD_LEFT_THUMB_DOWN,
            com.winlator.inputcontrols.Binding.GAMEPAD_LEFT_THUMB_LEFT,
            com.winlator.inputcontrols.Binding.GAMEPAD_LEFT_THUMB_RIGHT
        )
        PhysicalPresetBinding.RIGHT_STICK -> listOf(
            com.winlator.inputcontrols.Binding.GAMEPAD_RIGHT_THUMB_UP,
            com.winlator.inputcontrols.Binding.GAMEPAD_RIGHT_THUMB_DOWN,
            com.winlator.inputcontrols.Binding.GAMEPAD_RIGHT_THUMB_LEFT,
            com.winlator.inputcontrols.Binding.GAMEPAD_RIGHT_THUMB_RIGHT
        )
    }

    // Get keyCodes based on target
    val keyCodes = when (target) {
        PhysicalPresetTarget.LEFT_STICK -> {
            // Up, Down, Left, Right
            leftStickAxes.map { config ->
                ExternalControllerBinding.getKeyCodeForAxis(config.axis, config.sign.toByte())
            }
        }
        PhysicalPresetTarget.RIGHT_STICK -> {
            // Up, Down, Left, Right
            rightStickAxes.map { config ->
                ExternalControllerBinding.getKeyCodeForAxis(config.axis, config.sign.toByte())
            }
        }
        PhysicalPresetTarget.DPAD -> {
            // Up, Down, Left, Right
            dpadButtons.map { config ->
                config.keyCode
            }
        }
    }

    // Apply bindings
    keyCodes.forEachIndexed { index, keyCode ->
        if (keyCode != 0 && index < bindings.size) {
            workingBindings[keyCode] = bindings[index]
        }
    }
}

/**
 * Copies on-screen elements from source profile to destination profile if needed.
 */
private fun copyElementsIfNeeded(context: android.content.Context, destProfile: ControlsProfile, sourceProfile: ControlsProfile) {
    try {
        val destFile = ControlsProfile.getProfileFile(context, destProfile.id)
        val sourceFile = ControlsProfile.getProfileFile(context, sourceProfile.id)

        if (!sourceFile.isFile()) {
            Log.w("gncontrol", "copyElements: Source profile file not found")
            return
        }

        val sourceJson = org.json.JSONObject(com.winlator.core.FileUtils.readString(sourceFile))
        if (!sourceJson.has("elements")) {
            Log.w("gncontrol", "copyElements: Source profile has no elements")
            return
        }
        val sourceElements = sourceJson.getJSONArray("elements")

        var needsCopy = false
        if (!destFile.isFile()) {
            needsCopy = true
        } else {
            val destJson = org.json.JSONObject(com.winlator.core.FileUtils.readString(destFile))
            if (!destJson.has("elements") || destJson.getJSONArray("elements").length() == 0) {
                needsCopy = true
            } else {
                val destElements = destJson.getJSONArray("elements")
                var hasGamepadBindings = false
                for (i in 0 until destElements.length()) {
                    val element = destElements.getJSONObject(i)
                    if (element.has("bindings")) {
                        val bindings = element.getJSONArray("bindings")
                        for (j in 0 until bindings.length()) {
                            val binding = bindings.getString(j)
                            if (binding.startsWith("GAMEPAD_")) {
                                hasGamepadBindings = true
                                break
                            }
                        }
                    }
                    if (hasGamepadBindings) break
                }
                if (!hasGamepadBindings) {
                    needsCopy = true
                }
            }
        }

        if (needsCopy) {
            val destJson = if (destFile.isFile()) {
                org.json.JSONObject(com.winlator.core.FileUtils.readString(destFile))
            } else {
                org.json.JSONObject().apply {
                    put("id", destProfile.id)
                    put("name", destProfile.name)
                    put("cursorSpeed", destProfile.cursorSpeed)
                }
            }

            destJson.put("elements", sourceElements)
            com.winlator.core.FileUtils.writeString(destFile, destJson.toString())
            Log.d("gncontrol", "Copied ${sourceElements.length()} elements")
        }
    } catch (e: Exception) {
        Log.e("gncontrol", "copyElements: Failed", e)
    }
}

/**
 * Ultra-compact Analog Settings section optimized for handheld and phone screens.
 * Features a single-row live HUD with vertical trigger meters, consolidated stick card,
 * single-line label+slider+badge rows, and a collapsible response curve preview.
 */
@Composable
private fun AnalogSettingsSection(
    settings: ControllerAxisSettings,
    onSettingsChanged: (ControllerAxisSettings) -> Unit,
    liveStickLX: Float = 0f,
    liveStickLY: Float = 0f,
    liveStickRX: Float = 0f,
    liveStickRY: Float = 0f,
    liveTriggerL: Float = 0f,
    liveTriggerR: Float = 0f,
    isTestModeActive: Boolean = false,
    onToggleTestMode: () -> Unit = {}
) {
    var targetStick by remember { mutableStateOf(ControllerAxisSettings.TargetStick.BOTH) }
    var targetTrigger by remember { mutableIntStateOf(0) } // 0 = Both, 1 = L2, 2 = R2
    var lastFocusedSection by remember { mutableStateOf("sticks") } // "sticks" or "triggers"
    var isStickSectionExpanded by remember { mutableStateOf(true) }
    var isTriggerSectionExpanded by remember { mutableStateOf(true) }
    var isHudCollapsed by remember { mutableStateOf(true) } // DEFAULTS TO COMPACT VALUES RIBBON
    var curveFocusMode by remember { mutableStateOf("auto") } // "auto", "ls", "rs", "lt", "rt"
    var lastActiveComponent by remember { mutableStateOf("ls") } // "ls", "rs", "lt", "rt"
    var showCurveDropdown by remember { mutableStateOf(false) }
    var showStickPresetsMenu by remember { mutableStateOf(false) }
    var showTriggerPresetsMenu by remember { mutableStateOf(false) }
    var showExpandedHudDialog by remember { mutableStateOf(false) }
    var testInput by remember { mutableFloatStateOf(0.5f) }

    LaunchedEffect(isTestModeActive) {
        if (!isTestModeActive) {
            isHudCollapsed = true
        }
    }

    // Check active hardware input
    val leftMagnitude = Math.sqrt((liveStickLX * liveStickLX + liveStickLY * liveStickLY).toDouble()).toFloat()
    val rightMagnitude = Math.sqrt((liveStickRX * liveStickRX + liveStickRY * liveStickRY).toDouble()).toFloat()
    val activeHardwareInput = Math.max(leftMagnitude, rightMagnitude)
    val effectiveInput = if (activeHardwareInput > 0.02f) activeHardwareInput else testInput

    // Active stick properties based on selected tab
    val currentDeadzone = when (targetStick) {
        ControllerAxisSettings.TargetStick.LEFT -> settings.leftStickDeadzone
        ControllerAxisSettings.TargetStick.RIGHT -> settings.rightStickDeadzone
        ControllerAxisSettings.TargetStick.BOTH -> settings.leftStickDeadzone
    }
    val currentOuterDeadzone = when (targetStick) {
        ControllerAxisSettings.TargetStick.LEFT -> settings.leftStickOuterDeadzone
        ControllerAxisSettings.TargetStick.RIGHT -> settings.rightStickOuterDeadzone
        ControllerAxisSettings.TargetStick.BOTH -> settings.leftStickOuterDeadzone
    }
    val currentSensitivity = when (targetStick) {
        ControllerAxisSettings.TargetStick.LEFT -> settings.leftStickSensitivity
        ControllerAxisSettings.TargetStick.RIGHT -> settings.rightStickSensitivity
        ControllerAxisSettings.TargetStick.BOTH -> settings.leftStickSensitivity
    }
    val currentCurve = when (targetStick) {
        ControllerAxisSettings.TargetStick.LEFT -> settings.leftStickCurve
        ControllerAxisSettings.TargetStick.RIGHT -> settings.rightStickCurve
        ControllerAxisSettings.TargetStick.BOTH -> settings.leftStickCurve
    }

    // Active trigger properties based on selected tab
    val currentHairTrigger = when (targetTrigger) {
        1 -> settings.leftTriggerHairTrigger
        2 -> settings.rightTriggerHairTrigger
        else -> settings.leftTriggerHairTrigger
    }
    val currentTriggerThreshold = when (targetTrigger) {
        1 -> settings.leftTriggerThreshold
        2 -> settings.rightTriggerThreshold
        else -> settings.leftTriggerThreshold
    }
    val currentTriggerDeadzone = when (targetTrigger) {
        1 -> settings.leftTriggerDeadzone
        2 -> settings.rightTriggerDeadzone
        else -> settings.leftTriggerDeadzone
    }
    val currentTriggerCutoff = when (targetTrigger) {
        1 -> settings.leftTriggerCutoff
        2 -> settings.rightTriggerCutoff
        else -> settings.leftTriggerCutoff
    }
    val currentTriggerSensitivity = 1.0f
    val currentTriggerCurve = when (targetTrigger) {
        1 -> settings.leftTriggerCurve
        2 -> settings.rightTriggerCurve
        else -> settings.leftTriggerCurve
    }

    // Push Constraints
    fun updateDeadzone(newVal: Float) {
        val rounded = Math.round(newVal * 100f) / 100f
        val updated = settings.copy()
        if (targetStick == ControllerAxisSettings.TargetStick.BOTH || targetStick == ControllerAxisSettings.TargetStick.LEFT) {
            updated.leftStickDeadzone = rounded
            if (rounded > updated.leftStickOuterDeadzone - 0.05f) {
                updated.leftStickOuterDeadzone = (rounded + 0.05f).coerceIn(0.70f, 1.00f)
            }
        }
        if (targetStick == ControllerAxisSettings.TargetStick.BOTH || targetStick == ControllerAxisSettings.TargetStick.RIGHT) {
            updated.rightStickDeadzone = rounded
            if (rounded > updated.rightStickOuterDeadzone - 0.05f) {
                updated.rightStickOuterDeadzone = (rounded + 0.05f).coerceIn(0.70f, 1.00f)
            }
        }
        onSettingsChanged(updated)
    }

    fun updateStickOuterDeadzone(newVal: Float) {
        val rounded = Math.round(newVal * 100f) / 100f
        val updated = settings.copy()
        if (targetStick == ControllerAxisSettings.TargetStick.BOTH || targetStick == ControllerAxisSettings.TargetStick.LEFT) {
            updated.leftStickOuterDeadzone = rounded
            if (rounded < updated.leftStickDeadzone + 0.05f) {
                updated.leftStickDeadzone = (rounded - 0.05f).coerceIn(0.00f, 0.50f)
            }
        }
        if (targetStick == ControllerAxisSettings.TargetStick.BOTH || targetStick == ControllerAxisSettings.TargetStick.RIGHT) {
            updated.rightStickOuterDeadzone = rounded
            if (rounded < updated.rightStickDeadzone + 0.05f) {
                updated.rightStickDeadzone = (rounded - 0.05f).coerceIn(0.00f, 0.50f)
            }
        }
        onSettingsChanged(updated)
    }

    fun updateSensitivity(newVal: Float) {
        val rounded = Math.round(newVal * 20f) / 20f
        val updated = settings.copy()
        if (targetStick == ControllerAxisSettings.TargetStick.BOTH || targetStick == ControllerAxisSettings.TargetStick.LEFT) {
            updated.leftStickSensitivity = rounded
        }
        if (targetStick == ControllerAxisSettings.TargetStick.BOTH || targetStick == ControllerAxisSettings.TargetStick.RIGHT) {
            updated.rightStickSensitivity = rounded
        }
        onSettingsChanged(updated)
    }

    fun updateCurve(newVal: Float) {
        val rounded = Math.round(newVal * 20f) / 20f
        val updated = settings.copy()
        if (targetStick == ControllerAxisSettings.TargetStick.BOTH || targetStick == ControllerAxisSettings.TargetStick.LEFT) {
            updated.leftStickCurve = rounded
        }
        if (targetStick == ControllerAxisSettings.TargetStick.BOTH || targetStick == ControllerAxisSettings.TargetStick.RIGHT) {
            updated.rightStickCurve = rounded
        }
        onSettingsChanged(updated)
    }

    fun updateHairTrigger(enabled: Boolean) {
        val updated = settings.copy()
        if (targetTrigger == 0 || targetTrigger == 1) {
            updated.leftTriggerHairTrigger = enabled
        }
        if (targetTrigger == 0 || targetTrigger == 2) {
            updated.rightTriggerHairTrigger = enabled
        }
        onSettingsChanged(updated)
    }

    fun updateTriggerThreshold(newVal: Float) {
        val rounded = Math.round(newVal * 100f) / 100f
        val updated = settings.copy()
        if (targetTrigger == 0 || targetTrigger == 1) {
            updated.leftTriggerThreshold = rounded
        }
        if (targetTrigger == 0 || targetTrigger == 2) {
            updated.rightTriggerThreshold = rounded
        }
        onSettingsChanged(updated)
    }

    fun updateTriggerDeadzone(newVal: Float) {
        val rounded = Math.round(newVal * 100f) / 100f
        val updated = settings.copy()
        if (targetTrigger == 0 || targetTrigger == 1) {
            updated.leftTriggerDeadzone = rounded
            if (rounded > updated.leftTriggerCutoff - 0.05f) {
                updated.leftTriggerCutoff = (rounded + 0.05f).coerceIn(0.50f, 1.00f)
            }
        }
        if (targetTrigger == 0 || targetTrigger == 2) {
            updated.rightTriggerDeadzone = rounded
            if (rounded > updated.rightTriggerCutoff - 0.05f) {
                updated.rightTriggerCutoff = (rounded + 0.05f).coerceIn(0.50f, 1.00f)
            }
        }
        onSettingsChanged(updated)
    }

    fun updateTriggerCutoff(newVal: Float) {
        val rounded = Math.round(newVal * 100f) / 100f
        val updated = settings.copy()
        if (targetTrigger == 0 || targetTrigger == 1) {
            updated.leftTriggerCutoff = rounded
            if (rounded < updated.leftTriggerDeadzone + 0.05f) {
                updated.leftTriggerDeadzone = (rounded - 0.05f).coerceIn(0.00f, 0.40f)
            }
        }
        if (targetTrigger == 0 || targetTrigger == 2) {
            updated.rightTriggerCutoff = rounded
            if (rounded < updated.rightTriggerDeadzone + 0.05f) {
                updated.rightTriggerDeadzone = (rounded - 0.05f).coerceIn(0.00f, 0.40f)
            }
        }
        onSettingsChanged(updated)
    }

    fun updateTriggerCurve(newVal: Float) {
        val rounded = Math.round(newVal * 20f) / 20f
        val updated = settings.copy()
        if (targetTrigger == 0 || targetTrigger == 1) {
            updated.leftTriggerCurve = rounded
        }
        if (targetTrigger == 0 || targetTrigger == 2) {
            updated.rightTriggerCurve = rounded
        }
        onSettingsChanged(updated)
    }

    // Auto-detect currently manipulated hardware component if in motion
    val maxHardwareInput = Math.max(Math.max(leftMagnitude, rightMagnitude), Math.max(liveTriggerL, liveTriggerR))
    if (maxHardwareInput > 0.05f) {
        lastActiveComponent = when (maxHardwareInput) {
            liveTriggerL -> "lt"
            liveTriggerR -> "rt"
            rightMagnitude -> "rs"
            else -> "ls"
        }
    }
    val activeComponent = if (curveFocusMode == "auto") lastActiveComponent else curveFocusMode
    val isTriggerComponent = activeComponent == "lt" || activeComponent == "rt"

    // Component-specific parameters for curve display & real-time dot
    val compDeadzone: Float = when (activeComponent) {
        "ls" -> settings.leftStickDeadzone
        "rs" -> settings.rightStickDeadzone
        "lt" -> settings.leftTriggerDeadzone
        else -> settings.rightTriggerDeadzone
    }
    val compCutoff: Float = when (activeComponent) {
        "ls" -> settings.leftStickOuterDeadzone
        "rs" -> settings.rightStickOuterDeadzone
        "lt" -> settings.leftTriggerCutoff
        else -> settings.rightTriggerCutoff
    }
    val compSensitivity: Float = when (activeComponent) {
        "ls" -> settings.leftStickSensitivity
        "rs" -> settings.rightStickSensitivity
        else -> 1.0f
    }
    val compCurve: Float = when (activeComponent) {
        "ls" -> settings.leftStickCurve
        "rs" -> settings.rightStickCurve
        "lt" -> settings.leftTriggerCurve
        else -> settings.rightTriggerCurve
    }
    val compHairTrigger: Boolean = when (activeComponent) {
        "lt" -> settings.leftTriggerHairTrigger
        "rt" -> settings.rightTriggerHairTrigger
        else -> false
    }
    val compThreshold: Float = when (activeComponent) {
        "lt" -> settings.leftTriggerThreshold
        "rt" -> settings.rightTriggerThreshold
        else -> 0f
    }
    val compInput: Float = when (activeComponent) {
        "ls" -> leftMagnitude
        "rs" -> rightMagnitude
        "lt" -> liveTriggerL
        else -> liveTriggerR
    }
    val compColor: Color = when (activeComponent) {
        "ls" -> MaterialTheme.colorScheme.primary
        "rs" -> MaterialTheme.colorScheme.tertiary
        "lt" -> Color(0xFF10B981)
        else -> Color(0xFF06B6D4)
    }

    val compOutput = if (isTriggerComponent) {
        if (compHairTrigger) {
            if (compInput >= compThreshold) 1.0f else 0.0f
        } else {
            if (compInput <= compDeadzone) 0f
            else {
                val denom = (compCutoff - compDeadzone).coerceAtLeast(0.01f)
                val norm = ((compInput - compDeadzone) / denom).coerceIn(0f, 1f)
                Math.pow(norm.toDouble(), compCurve.toDouble()).toFloat().coerceIn(0f, 1f)
            }
        }
    } else {
        if (compInput <= compDeadzone) 0f
        else {
            val denom = (compCutoff - compDeadzone).coerceAtLeast(0.01f)
            val norm = ((compInput - compDeadzone) / denom).coerceIn(0f, 1f)
            (Math.pow(norm.toDouble(), compCurve.toDouble()).toFloat() * compSensitivity).coerceIn(0f, 1f)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { keyEvent ->
                val keyCode = keyEvent.nativeKeyEvent.keyCode
                val action = keyEvent.nativeKeyEvent.action

                // 1. Y / Triangle toggles Expand & Test mode immediately
                if (keyCode == KeyEvent.KEYCODE_BUTTON_Y && action == KeyEvent.ACTION_UP) {
                    if (isHudCollapsed) {
                        isHudCollapsed = false
                        if (!isTestModeActive) onToggleTestMode()
                    } else {
                        isHudCollapsed = true
                        if (isTestModeActive) onToggleTestMode()
                    }
                    return@onPreviewKeyEvent true
                }

                // 2. L1 / LB cycles target tabs leftwards
                if (keyCode == KeyEvent.KEYCODE_BUTTON_L1 && action == KeyEvent.ACTION_UP) {
                    if (lastFocusedSection == "triggers") {
                        targetTrigger = when (targetTrigger) {
                            2 -> 1
                            1 -> 0
                            0 -> 2
                            else -> 0
                        }
                    } else {
                        targetStick = when (targetStick) {
                            ControllerAxisSettings.TargetStick.RIGHT -> ControllerAxisSettings.TargetStick.LEFT
                            ControllerAxisSettings.TargetStick.LEFT -> ControllerAxisSettings.TargetStick.BOTH
                            ControllerAxisSettings.TargetStick.BOTH -> ControllerAxisSettings.TargetStick.RIGHT
                        }
                    }
                    return@onPreviewKeyEvent true
                }

                // 3. R1 / RB cycles target tabs rightwards
                if (keyCode == KeyEvent.KEYCODE_BUTTON_R1 && action == KeyEvent.ACTION_UP) {
                    if (lastFocusedSection == "triggers") {
                        targetTrigger = when (targetTrigger) {
                            0 -> 1
                            1 -> 2
                            2 -> 0
                            else -> 0
                        }
                    } else {
                        targetStick = when (targetStick) {
                            ControllerAxisSettings.TargetStick.BOTH -> ControllerAxisSettings.TargetStick.LEFT
                            ControllerAxisSettings.TargetStick.LEFT -> ControllerAxisSettings.TargetStick.RIGHT
                            ControllerAxisSettings.TargetStick.RIGHT -> ControllerAxisSettings.TargetStick.BOTH
                        }
                    }
                    return@onPreviewKeyEvent true
                }

                false
            },
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // ==================== 1. PINNED TOP LIVE HUD CARD ====================
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Header Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "🎮 Live HUD",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )

                        // 1. ⛶ Fullscreen Tester Button (White text, high contrast)
                        val isTesterFocused = remember { mutableStateOf(false) }
                        Surface(
                            color = if (isTesterFocused.value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
                            shape = MaterialTheme.shapes.extraSmall,
                            border = BorderStroke(1.dp, if (isTesterFocused.value) Color.White else Color.Transparent),
                            modifier = Modifier
                                .onFocusChanged { isTesterFocused.value = it.isFocused }
                                .clickable { showExpandedHudDialog = true }
                                .focusable()
                        ) {
                            Text(
                                text = "⛶ Fullscreen Tester",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }

                        // 2. UNIFIED EXPAND & TEST MODE TOGGLE (with Y shortcut hint)
                        val isExpandTestActive = !isHudCollapsed && isTestModeActive
                        val isExpandBtnFocused = remember { mutableStateOf(false) }
                        Surface(
                            color = if (isExpandTestActive) Color(0xFF10B981) else MaterialTheme.colorScheme.surface,
                            shape = MaterialTheme.shapes.extraSmall,
                            border = BorderStroke(1.dp, if (isExpandBtnFocused.value) MaterialTheme.colorScheme.primary else if (!isExpandTestActive) MaterialTheme.colorScheme.outline.copy(alpha = 0.4f) else Color(0xFF10B981)),
                            modifier = Modifier
                                .onFocusChanged { isExpandBtnFocused.value = it.isFocused }
                                .clickable {
                                    if (isHudCollapsed) {
                                        isHudCollapsed = false
                                        if (!isTestModeActive) onToggleTestMode()
                                    } else {
                                        isHudCollapsed = true
                                        if (isTestModeActive) onToggleTestMode()
                                    }
                                }
                                .focusable()
                        ) {
                            Text(
                                text = if (isExpandTestActive) "🎮 Testing [Y to Stop]" else "▾ Expand & Test [Y]",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (isExpandTestActive) Color.White else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }

                        // 3. Dropdown to Lock or Auto-switch curve focus
                        if (!isHudCollapsed) {
                            Box {
                                Surface(
                                    color = if (curveFocusMode == "auto") MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
                                    shape = MaterialTheme.shapes.extraSmall,
                                    modifier = Modifier.clickable { showCurveDropdown = true }
                                ) {
                                    Text(
                                        text = when (curveFocusMode) {
                                            "auto" -> "⚡ Auto (${activeComponent.uppercase()}) ▾"
                                            "ls" -> "🔒 LS ▾"
                                            "rs" -> "🔒 RS ▾"
                                            "lt" -> "🔒 LT ▾"
                                            "rt" -> "🔒 RT ▾"
                                            else -> "Curve ▾"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (curveFocusMode == "auto") MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onTertiaryContainer,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                    )
                                }

                                DropdownMenu(
                                    expanded = showCurveDropdown,
                                    onDismissRequest = { showCurveDropdown = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("⚡ Auto (Follow Input)", fontWeight = if (curveFocusMode == "auto") FontWeight.Bold else FontWeight.Normal) },
                                        onClick = {
                                            curveFocusMode = "auto"
                                            showCurveDropdown = false
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("🔒 Lock: Left Stick (LS)", fontWeight = if (curveFocusMode == "ls") FontWeight.Bold else FontWeight.Normal) },
                                        onClick = {
                                            curveFocusMode = "ls"
                                            showCurveDropdown = false
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("🔒 Lock: Right Stick (RS)", fontWeight = if (curveFocusMode == "rs") FontWeight.Bold else FontWeight.Normal) },
                                        onClick = {
                                            curveFocusMode = "rs"
                                            showCurveDropdown = false
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("🔒 Lock: Left Trigger (LT)", fontWeight = if (curveFocusMode == "lt") FontWeight.Bold else FontWeight.Normal) },
                                        onClick = {
                                            curveFocusMode = "lt"
                                            showCurveDropdown = false
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("🔒 Lock: Right Trigger (RT)", fontWeight = if (curveFocusMode == "rt") FontWeight.Bold else FontWeight.Normal) },
                                        onClick = {
                                            curveFocusMode = "rt"
                                            showCurveDropdown = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    Text(
                        text = if (compInput > 0.01f) {
                            if (isTriggerComponent && compHairTrigger) "${activeComponent.uppercase()}: ${(compInput * 100).toInt()}% ➜ ${if (compOutput >= 1.0f) "FIRE!" else "OFF"}"
                            else "${activeComponent.uppercase()}: ${(compInput * 100).toInt()}% ➜ ${(compOutput * 100).toInt()}%"
                        } else "${activeComponent.uppercase()} Curve",
                        style = MaterialTheme.typography.labelSmall,
                        color = compColor,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (!isHudCollapsed) {
                    // EXPANDED MODE: Visual Radars, Meters & Response Curve Visualizer
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Left Side: Live Crosshairs & Triggers
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            StickRadarCrosshair(
                                label = "LS",
                                rawX = liveStickLX,
                                rawY = liveStickLY,
                                deadzone = settings.leftStickDeadzone,
                                outerDeadzone = settings.leftStickOuterDeadzone,
                                sensitivity = settings.leftStickSensitivity,
                                curve = settings.leftStickCurve,
                                badgeColor = MaterialTheme.colorScheme.primary,
                                onDeadzoneChanged = {
                                    targetStick = ControllerAxisSettings.TargetStick.LEFT
                                    updateDeadzone(it)
                                },
                                onOuterDeadzoneChanged = {
                                    targetStick = ControllerAxisSettings.TargetStick.LEFT
                                    updateStickOuterDeadzone(it)
                                }
                            )

                            StickRadarCrosshair(
                                label = "RS",
                                rawX = liveStickRX,
                                rawY = liveStickRY,
                                deadzone = settings.rightStickDeadzone,
                                outerDeadzone = settings.rightStickOuterDeadzone,
                                sensitivity = settings.rightStickSensitivity,
                                curve = settings.rightStickCurve,
                                badgeColor = MaterialTheme.colorScheme.tertiary,
                                onDeadzoneChanged = {
                                    targetStick = ControllerAxisSettings.TargetStick.RIGHT
                                    updateDeadzone(it)
                                },
                                onOuterDeadzoneChanged = {
                                    targetStick = ControllerAxisSettings.TargetStick.RIGHT
                                    updateStickOuterDeadzone(it)
                                }
                            )

                            val triggerLActive = if (settings.leftTriggerHairTrigger) liveTriggerL >= settings.leftTriggerThreshold else liveTriggerL > settings.leftTriggerDeadzone
                            val triggerRActive = if (settings.rightTriggerHairTrigger) liveTriggerR >= settings.rightTriggerThreshold else liveTriggerR > settings.rightTriggerDeadzone

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(start = 2.dp)
                            ) {
                                VerticalTriggerBar(
                                    label = "LT",
                                    rawVal = liveTriggerL,
                                    deadzone = settings.leftTriggerDeadzone,
                                    cutoff = settings.leftTriggerCutoff,
                                    threshold = settings.leftTriggerThreshold,
                                    isActive = triggerLActive,
                                    isHairTrigger = settings.leftTriggerHairTrigger,
                                    activeColor = Color(0xFF10B981)
                                )
                                VerticalTriggerBar(
                                    label = "RT",
                                    rawVal = liveTriggerR,
                                    deadzone = settings.rightTriggerDeadzone,
                                    cutoff = settings.rightTriggerCutoff,
                                    threshold = settings.rightTriggerThreshold,
                                    isActive = triggerRActive,
                                    isHairTrigger = settings.rightTriggerHairTrigger,
                                    activeColor = Color(0xFF06B6D4)
                                )
                            }
                        }

                        // Right Side: Dynamic Curve Graph
                        val onSurfaceColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        val deadzoneColor = MaterialTheme.colorScheme.error.copy(alpha = 0.25f)

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(65.dp)
                                .padding(start = 6.dp)
                        ) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val w = size.width
                                val h = size.height

                                // Draw Grid
                                drawLine(color = onSurfaceColor, start = Offset(0f, h), end = Offset(w, h), strokeWidth = 1.5f)
                                drawLine(color = onSurfaceColor, start = Offset(0f, 0f), end = Offset(0f, h), strokeWidth = 1.5f)
                                drawLine(color = onSurfaceColor.copy(alpha = 0.2f), start = Offset(0f, h), end = Offset(w, 0f), strokeWidth = 1f)

                                // 1. Draw Shaded Deadzone Region (Left)
                                val dzX = compDeadzone * w
                                if (dzX > 0f) {
                                    drawRect(color = deadzoneColor, topLeft = Offset(0f, 0f), size = Size(dzX, h))
                                    drawLine(color = Color(0xFFEF4444).copy(alpha = 0.5f), start = Offset(dzX, 0f), end = Offset(dzX, h), strokeWidth = 1f)
                                }

                                // 2. Draw Shaded Cutoff / Outer Threshold Region (Right)
                                val cutX = compCutoff * w
                                if (cutX < w) {
                                    drawRect(color = Color(0xFFEF4444).copy(alpha = 0.15f), topLeft = Offset(cutX, 0f), size = Size(w - cutX, h))
                                    drawLine(color = Color(0xFFEF4444).copy(alpha = 0.85f), start = Offset(cutX, 0f), end = Offset(cutX, h), strokeWidth = 3.0f)
                                }

                                if (isTriggerComponent && compHairTrigger) {
                                    val threshX = compThreshold * w
                                    drawLine(color = Color(0xFFF59E0B), start = Offset(0f, h), end = Offset(threshX, h), strokeWidth = 2.5f)
                                    drawLine(color = Color(0xFFF59E0B), start = Offset(threshX, h), end = Offset(threshX, 2f), strokeWidth = 2.5f)
                                    drawLine(color = Color(0xFFF59E0B), start = Offset(threshX, 2f), end = Offset(w, 2f), strokeWidth = 2.5f)
                                } else {
                                    val path = Path()
                                    val steps = 30
                                    val denom = (compCutoff - compDeadzone).coerceAtLeast(0.01f)
                                    for (i in 0..steps) {
                                        val input = i / steps.toFloat()
                                        val output = if (input <= compDeadzone) 0f
                                        else {
                                            val norm = ((input - compDeadzone) / denom).coerceIn(0f, 1f)
                                            val curved = Math.pow(norm.toDouble(), compCurve.toDouble()).toFloat()
                                            (curved * compSensitivity).coerceIn(0f, 1f)
                                        }
                                        val x = input * w
                                        val y = h - (output * (h - 4f)) - 2f
                                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                                    }
                                    drawPath(path = path, color = compColor, style = Stroke(width = 2.5f))
                                }

                                // Live moving dot on curve
                                if (compInput > 0.01f) {
                                    val dotX = (compInput.coerceIn(0f, 1f) * w)
                                    val dotY = h - (compOutput.coerceIn(0f, 1f) * (h - 4f)) - 2f
                                    drawCircle(color = compColor.copy(alpha = 0.35f), radius = 7f, center = Offset(dotX, dotY))
                                    drawCircle(color = compColor, radius = 4f, center = Offset(dotX, dotY))
                                }
                            }
                        }
                    }
                } else {
                    // COLLAPSED SENSOR RIBBON
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 1. LS Badge
                        val lsActive = leftMagnitude > settings.leftStickDeadzone
                        Surface(
                            color = if (lsActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.extraSmall,
                            border = BorderStroke(1.dp, if (activeComponent == "ls") MaterialTheme.colorScheme.primary else Color.Transparent),
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    targetStick = ControllerAxisSettings.TargetStick.LEFT
                                    isStickSectionExpanded = true
                                    lastActiveComponent = "ls"
                                    lastFocusedSection = "sticks"
                                }
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 4.dp, vertical = 3.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("🕹️ LS", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                    Text(
                                        text = if (leftMagnitude <= settings.leftStickDeadzone) "${(leftMagnitude * 100).toInt()}% (DZ)" else "${(leftMagnitude * 100).toInt()}%",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (leftMagnitude <= settings.leftStickDeadzone) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f) else MaterialTheme.colorScheme.primary
                                    )
                                }
                                Text(
                                    text = "DZ:${(settings.leftStickDeadzone * 100).toInt()}% • Max:${(settings.leftStickOuterDeadzone * 100).toInt()}% • S:${settings.leftStickSensitivity}x • C:${settings.leftStickCurve}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = androidx.compose.ui.unit.TextUnit(9f, androidx.compose.ui.unit.TextUnitType.Sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // 2. RS Badge
                        val rsActive = rightMagnitude > settings.rightStickDeadzone
                        Surface(
                            color = if (rsActive) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.extraSmall,
                            border = BorderStroke(1.dp, if (activeComponent == "rs") MaterialTheme.colorScheme.tertiary else Color.Transparent),
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    targetStick = ControllerAxisSettings.TargetStick.RIGHT
                                    isStickSectionExpanded = true
                                    lastActiveComponent = "rs"
                                    lastFocusedSection = "sticks"
                                }
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 4.dp, vertical = 3.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("🕹️ RS", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.tertiary)
                                    Text(
                                        text = if (rightMagnitude <= settings.rightStickDeadzone) "${(rightMagnitude * 100).toInt()}% (DZ)" else "${(rightMagnitude * 100).toInt()}%",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (rightMagnitude <= settings.rightStickDeadzone) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f) else MaterialTheme.colorScheme.tertiary
                                    )
                                }
                                Text(
                                    text = "DZ:${(settings.rightStickDeadzone * 100).toInt()}% • Max:${(settings.rightStickOuterDeadzone * 100).toInt()}% • S:${settings.rightStickSensitivity}x • C:${settings.rightStickCurve}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = androidx.compose.ui.unit.TextUnit(9f, androidx.compose.ui.unit.TextUnitType.Sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // 3. LT Badge
                        val ltTriggerActive = if (settings.leftTriggerHairTrigger) liveTriggerL >= settings.leftTriggerThreshold else liveTriggerL > settings.leftTriggerDeadzone
                        Surface(
                            color = if (ltTriggerActive) Color(0xFF10B981).copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.extraSmall,
                            border = BorderStroke(1.dp, if (activeComponent == "lt") Color(0xFF10B981) else Color.Transparent),
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    targetTrigger = 1
                                    isTriggerSectionExpanded = true
                                    lastActiveComponent = "lt"
                                    lastFocusedSection = "triggers"
                                }
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 4.dp, vertical = 3.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("⚡ LT", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFF10B981))
                                    Text(
                                        text = if (settings.leftTriggerHairTrigger && ltTriggerActive) "FIRE!" else "${(liveTriggerL * 100).toInt()}%",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (ltTriggerActive) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                    )
                                }
                                Text(
                                    text = if (settings.leftTriggerHairTrigger) "Hair: ${(settings.leftTriggerThreshold * 100).toInt()}% click"
                                           else "DZ:${(settings.leftTriggerDeadzone * 100).toInt()}% • Max:${(settings.leftTriggerCutoff * 100).toInt()}% • C:${settings.leftTriggerCurve}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = androidx.compose.ui.unit.TextUnit(9f, androidx.compose.ui.unit.TextUnitType.Sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // 4. RT Badge
                        val rtTriggerActive = if (settings.rightTriggerHairTrigger) liveTriggerR >= settings.rightTriggerThreshold else liveTriggerR > settings.rightTriggerDeadzone
                        Surface(
                            color = if (rtTriggerActive) Color(0xFF06B6D4).copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.extraSmall,
                            border = BorderStroke(1.dp, if (activeComponent == "rt") Color(0xFF06B6D4) else Color.Transparent),
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    targetTrigger = 2
                                    isTriggerSectionExpanded = true
                                    lastActiveComponent = "rt"
                                    lastFocusedSection = "triggers"
                                }
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 4.dp, vertical = 3.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("⚡ RT", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFF06B6D4))
                                    Text(
                                        text = if (settings.rightTriggerHairTrigger && rtTriggerActive) "FIRE!" else "${(liveTriggerR * 100).toInt()}%",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (rtTriggerActive) Color(0xFF06B6D4) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                    )
                                }
                                Text(
                                    text = if (settings.rightTriggerHairTrigger) "Hair: ${(settings.rightTriggerThreshold * 100).toInt()}% click"
                                           else "DZ:${(settings.rightTriggerDeadzone * 100).toInt()}% • Max:${(settings.rightTriggerCutoff * 100).toInt()}% • C:${settings.rightTriggerCurve}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = androidx.compose.ui.unit.TextUnit(9f, androidx.compose.ui.unit.TextUnitType.Sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        // ==================== 2. SCROLLABLE ACCORDIONS AREA ====================
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // STICKS ACCORDION CARD
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { if (it.hasFocus || it.isFocused) lastFocusedSection = "sticks" }
                    .onKeyEvent { keyEvent ->
                        if (keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_BUTTON_X && keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_UP) {
                            showStickPresetsMenu = true
                            true
                        } else false
                    },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Header & Target Stick Tabs
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "🕹️ Analog Sticks",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable { isStickSectionExpanded = !isStickSectionExpanded }
                            )

                            val isStickActive = lastFocusedSection == "sticks"

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val stickTabs = listOf(
                                    Pair(ControllerAxisSettings.TargetStick.BOTH, "Both"),
                                    Pair(ControllerAxisSettings.TargetStick.LEFT, "Left"),
                                    Pair(ControllerAxisSettings.TargetStick.RIGHT, "Right")
                                )
                                stickTabs.forEach { (target, label) ->
                                    val isSelected = targetStick == target
                                    val isTabFocused = remember { mutableStateOf(false) }
                                    Surface(
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                                        shape = MaterialTheme.shapes.extraSmall,
                                        border = BorderStroke(1.dp, if (isTabFocused.value) MaterialTheme.colorScheme.primary else Color.Transparent),
                                        modifier = Modifier
                                            .onFocusChanged {
                                                isTabFocused.value = it.isFocused
                                                if (it.isFocused) lastFocusedSection = "sticks"
                                            }
                                            .clickable {
                                                targetStick = target
                                                lastActiveComponent = if (target == ControllerAxisSettings.TargetStick.RIGHT) "rs" else "ls"
                                                lastFocusedSection = "sticks"
                                            }
                                            .focusable()
                                    ) {
                                        Text(
                                            text = label,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                        )
                                    }
                                }

                                if (isStickActive) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                                        shape = MaterialTheme.shapes.extraSmall,
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                                    ) {
                                        Text(
                                            text = "L1 / R1",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // Context-Aware Stick Presets Dropdown (with [X] shortcut badge, white text)
                        val isStickActive = lastFocusedSection == "sticks"
                        Box {
                            val isPresetBtnFocused = remember { mutableStateOf(false) }
                            Surface(
                                color = if (isPresetBtnFocused.value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
                                shape = MaterialTheme.shapes.extraSmall,
                                border = BorderStroke(1.dp, if (isPresetBtnFocused.value) Color.White else Color.Transparent),
                                modifier = Modifier
                                    .onFocusChanged {
                                        isPresetBtnFocused.value = it.isFocused
                                        if (it.isFocused) lastFocusedSection = "sticks"
                                    }
                                    .clickable { showStickPresetsMenu = true }
                                    .focusable()
                            ) {
                                Text(
                                    text = if (isStickActive) "[X] Presets ▾" else "Presets ▾",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }

                            DropdownMenu(
                                expanded = showStickPresetsMenu,
                                onDismissRequest = { showStickPresetsMenu = false },
                                modifier = Modifier.widthIn(min = 320.dp)
                            ) {
                                fun applyStickPreset(dz: Float, outer: Float, sens: Float, crv: Float) {
                                    val updated = settings.copy()
                                    if (targetStick == ControllerAxisSettings.TargetStick.BOTH || targetStick == ControllerAxisSettings.TargetStick.LEFT) {
                                        updated.leftStickDeadzone = dz
                                        updated.leftStickOuterDeadzone = outer
                                        updated.leftStickSensitivity = sens
                                        updated.leftStickCurve = crv
                                    }
                                    if (targetStick == ControllerAxisSettings.TargetStick.BOTH || targetStick == ControllerAxisSettings.TargetStick.RIGHT) {
                                        updated.rightStickDeadzone = dz
                                        updated.rightStickOuterDeadzone = outer
                                        updated.rightStickSensitivity = sens
                                        updated.rightStickCurve = crv
                                    }
                                    onSettingsChanged(updated)
                                    showStickPresetsMenu = false
                                }

                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text("🎯 Default Linear", fontWeight = FontWeight.Bold)
                                            Text("DZ 5% • Outer 100% • Sens 1.00x • Curve 1.00 (100% Output)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    },
                                    onClick = { applyStickPreset(0.05f, 1.00f, 1.00f, 1.00f) }
                                )

                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text("⚔️ Action RPG & Free Look", fontWeight = FontWeight.Bold)
                                            Text("DZ 4% • Outer 98% • Sens 1.00x • Curve 1.15 (Smooth 360°)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    },
                                    onClick = { applyStickPreset(0.04f, 0.98f, 1.00f, 1.15f) }
                                )

                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text("🏎️ Racing / Smooth Steer", fontWeight = FontWeight.Bold)
                                            Text("DZ 6% • Outer 98% • Sens 1.00x • Curve 0.80 (Full Wheel Lock)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    },
                                    onClick = { applyStickPreset(0.06f, 0.98f, 1.00f, 0.80f) }
                                )

                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text("🔫 FPS Balanced Aim", fontWeight = FontWeight.Bold)
                                            Text("DZ 3% • Outer 95% • Sens 1.00x • Curve 1.25 (Steady Tracking)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    },
                                    onClick = { applyStickPreset(0.03f, 0.95f, 1.00f, 1.25f) }
                                )

                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text("🎯 Micro-Aim Sniper", fontWeight = FontWeight.Bold)
                                            Text("DZ 2% • Outer 95% • Sens 1.00x • Curve 1.85 (Pixel Micro-Aim)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    },
                                    onClick = { applyStickPreset(0.02f, 0.95f, 1.00f, 1.85f) }
                                )

                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text("🍄 2D Platformer & Retro", fontWeight = FontWeight.Bold)
                                            Text("DZ 2% • Outer 85% • Sens 1.00x • Curve 0.90 (Early 100% Run)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    },
                                    onClick = { applyStickPreset(0.02f, 0.85f, 1.00f, 0.90f) }
                                )

                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text("🥊 Fighting & Arcade", fontWeight = FontWeight.Bold)
                                            Text("DZ 2% • Outer 80% • Sens 1.00x • Curve 0.85 (Fast Specials 236/623)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    },
                                    onClick = { applyStickPreset(0.02f, 0.80f, 1.00f, 0.85f) }
                                )

                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text("✈️ Flight & Space Sim", fontWeight = FontWeight.Bold)
                                            Text("DZ 4% • Outer 98% • Sens 1.00x • Curve 1.50 (Gentle Center/100% Pitch)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    },
                                    onClick = { applyStickPreset(0.04f, 0.98f, 1.00f, 1.50f) }
                                )

                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text("⚡ High-Speed Flick Aim", fontWeight = FontWeight.Bold)
                                            Text("DZ 1% • Outer 95% • Sens 1.35x • Curve 0.90 (Aggressive Twitch)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    },
                                    onClick = { applyStickPreset(0.01f, 0.95f, 1.35f, 0.90f) }
                                )

                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text("🏃 Fast Sprint & Strafe", fontWeight = FontWeight.Bold)
                                            Text("DZ 2% • Outer 88% • Sens 1.00x • Curve 1.00 (Instant 100% Sprint)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    },
                                    onClick = { applyStickPreset(0.02f, 0.88f, 1.00f, 1.00f) }
                                )

                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text("🚶 Precision Walk / Sneak", fontWeight = FontWeight.Bold)
                                            Text("DZ 6% • Outer 98% • Sens 1.00x • Curve 1.45 (Wide Sneak to 100% Run)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    },
                                    onClick = { applyStickPreset(0.06f, 0.98f, 1.00f, 1.45f) }
                                )

                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text("🛡️ Anti-Drift (Worn Sticks)", fontWeight = FontWeight.Bold)
                                            Text("DZ 15% • Outer 100% • Sens 1.00x • Curve 1.00 (No Drift / Full 100%)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    },
                                    onClick = { applyStickPreset(0.15f, 1.00f, 1.00f, 1.00f) }
                                )
                            }
                        }
                    }

                    if (isStickSectionExpanded) {
                        // 1. Controller-Navigable Deadzone Slider (0% - 50%)
                        ControllerSliderRow(
                            label = "Deadzone",
                            value = currentDeadzone,
                            onValueChange = {
                                updateDeadzone(it)
                                lastActiveComponent = if (targetStick == ControllerAxisSettings.TargetStick.RIGHT) "rs" else "ls"
                            },
                            valueRange = 0.00f..0.50f,
                            stepSize = 0.01f,
                            displayValue = "${(currentDeadzone * 100).toInt()}%",
                            dialogTitle = "Set Stick Deadzone (%)",
                            isPercentage = true,
                            minVal = 0.00f,
                            maxVal = 0.50f,
                            activeColor = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.onFocusChanged { if (it.isFocused) lastFocusedSection = "sticks" }
                        )

                        // 2. Controller-Navigable Outer Deadzone Slider (70% - 100%)
                        ControllerSliderRow(
                            label = "Outer DZ",
                            value = currentOuterDeadzone,
                            onValueChange = {
                                updateStickOuterDeadzone(it)
                                lastActiveComponent = if (targetStick == ControllerAxisSettings.TargetStick.RIGHT) "rs" else "ls"
                            },
                            valueRange = 0.70f..1.00f,
                            stepSize = 0.01f,
                            displayValue = "${(currentOuterDeadzone * 100).toInt()}%",
                            dialogTitle = "Set Stick Outer Deadzone (%)",
                            isPercentage = true,
                            minVal = 0.70f,
                            maxVal = 1.00f,
                            activeColor = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.onFocusChanged { if (it.isFocused) lastFocusedSection = "sticks" }
                        )

                        // 3. Controller-Navigable Sensitivity Slider (0.25x - 3.00x)
                        ControllerSliderRow(
                            label = "Sensitivity",
                            value = currentSensitivity,
                            onValueChange = {
                                updateSensitivity(it)
                                lastActiveComponent = if (targetStick == ControllerAxisSettings.TargetStick.RIGHT) "rs" else "ls"
                            },
                            valueRange = 0.25f..3.00f,
                            stepSize = 0.05f,
                            displayValue = String.format(java.util.Locale.US, "%.2fx", currentSensitivity),
                            dialogTitle = "Set Stick Sensitivity",
                            isPercentage = false,
                            minVal = 0.25f,
                            maxVal = 3.00f,
                            activeColor = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.onFocusChanged { if (it.isFocused) lastFocusedSection = "sticks" }
                        )

                        // 4. Controller-Navigable Response Curve Slider (0.20 - 4.00)
                        ControllerSliderRow(
                            label = "Curve",
                            value = currentCurve,
                            onValueChange = {
                                updateCurve(it)
                                lastActiveComponent = if (targetStick == ControllerAxisSettings.TargetStick.RIGHT) "rs" else "ls"
                            },
                            valueRange = 0.20f..4.00f,
                            stepSize = 0.05f,
                            displayValue = String.format(java.util.Locale.US, "%.2f", currentCurve),
                            dialogTitle = "Set Stick Response Curve",
                            isPercentage = false,
                            minVal = 0.20f,
                            maxVal = 4.00f,
                            activeColor = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.onFocusChanged { if (it.isFocused) lastFocusedSection = "sticks" }
                        )
                    }
                }
            }

            // TRIGGERS ACCORDION CARD
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { if (it.hasFocus || it.isFocused) lastFocusedSection = "triggers" }
                    .onKeyEvent { keyEvent ->
                        if (keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_BUTTON_X && keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_UP) {
                            showTriggerPresetsMenu = true
                            true
                        } else false
                    },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Header & Target Trigger Tabs + Context-Aware Trigger Presets
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "⚡ Triggers (LT / RT)",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable { isTriggerSectionExpanded = !isTriggerSectionExpanded }
                            )

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val triggerTabs = listOf(
                                    Pair(0, "Both"),
                                    Pair(1, "L2"),
                                    Pair(2, "R2")
                                )
                                triggerTabs.forEach { (target, label) ->
                                    val isSelected = targetTrigger == target
                                    val isTabFocused = remember { mutableStateOf(false) }
                                    Surface(
                                        color = if (isSelected) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.surface,
                                        shape = MaterialTheme.shapes.extraSmall,
                                        border = BorderStroke(1.dp, if (isTabFocused.value) MaterialTheme.colorScheme.tertiary else Color.Transparent),
                                        modifier = Modifier
                                            .onFocusChanged {
                                                isTabFocused.value = it.isFocused
                                                if (it.isFocused) lastFocusedSection = "triggers"
                                            }
                                            .clickable {
                                                targetTrigger = target
                                                lastActiveComponent = if (target == 2) "rt" else "lt"
                                                lastFocusedSection = "triggers"
                                            }
                                            .focusable()
                                    ) {
                                        Text(
                                            text = label,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isSelected) MaterialTheme.colorScheme.onTertiary else MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                        )
                                    }
                                }

                                val isTriggerActive = lastFocusedSection == "triggers"
                                if (isTriggerActive) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.25f),
                                        shape = MaterialTheme.shapes.extraSmall,
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.5f))
                                    ) {
                                        Text(
                                            text = "L1 / R1",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // Context-Aware Trigger Presets Dropdown (with [X] shortcut badge, white text)
                        val isTriggerActive = lastFocusedSection == "triggers"
                        Box {
                            val isPresetBtnFocused = remember { mutableStateOf(false) }
                            Surface(
                                color = if (isPresetBtnFocused.value) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.tertiary.copy(alpha = 0.9f),
                                shape = MaterialTheme.shapes.extraSmall,
                                border = BorderStroke(1.dp, if (isPresetBtnFocused.value) Color.White else Color.Transparent),
                                modifier = Modifier
                                    .onFocusChanged {
                                        isPresetBtnFocused.value = it.isFocused
                                        if (it.isFocused) lastFocusedSection = "triggers"
                                    }
                                    .clickable { showTriggerPresetsMenu = true }
                                    .focusable()
                            ) {
                                Text(
                                    text = if (isTriggerActive) "[X] Presets ▾" else "Presets ▾",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }

                            DropdownMenu(
                                expanded = showTriggerPresetsMenu,
                                onDismissRequest = { showTriggerPresetsMenu = false },
                                modifier = Modifier.widthIn(min = 320.dp)
                            ) {
                                fun applyTriggerAnalogPreset(dz: Float, cut: Float, crv: Float) {
                                    val updated = settings.copy()
                                    if (targetTrigger == 0 || targetTrigger == 1) {
                                        updated.leftTriggerHairTrigger = false
                                        updated.leftTriggerDeadzone = dz
                                        updated.leftTriggerCutoff = cut
                                        updated.leftTriggerCurve = crv
                                    }
                                    if (targetTrigger == 0 || targetTrigger == 2) {
                                        updated.rightTriggerHairTrigger = false
                                        updated.rightTriggerDeadzone = dz
                                        updated.rightTriggerCutoff = cut
                                        updated.rightTriggerCurve = crv
                                    }
                                    onSettingsChanged(updated)
                                    showTriggerPresetsMenu = false
                                }

                                fun applyTriggerHairPreset(thresh: Float) {
                                    val updated = settings.copy()
                                    if (targetTrigger == 0 || targetTrigger == 1) {
                                        updated.leftTriggerHairTrigger = true
                                        updated.leftTriggerThreshold = thresh
                                    }
                                    if (targetTrigger == 0 || targetTrigger == 2) {
                                        updated.rightTriggerHairTrigger = true
                                        updated.rightTriggerThreshold = thresh
                                    }
                                    onSettingsChanged(updated)
                                    showTriggerPresetsMenu = false
                                }

                                when (targetTrigger) {
                                    0 -> { // Both Triggers
                                        DropdownMenuItem(
                                            text = {
                                                Column {
                                                    Text("⚡ Feather Hair Trigger", fontWeight = FontWeight.Bold)
                                                    Text("Instant digital 100% click at 3% touch", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                            },
                                            onClick = { applyTriggerHairPreset(0.03f) }
                                        )
                                        DropdownMenuItem(
                                            text = {
                                                Column {
                                                    Text("🎯 Default Linear Full Stroke", fontWeight = FontWeight.Bold)
                                                    Text("Start DZ 0% • Max Cutoff 100% • Curve 1.00 (100% Saturation)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                            },
                                            onClick = { applyTriggerAnalogPreset(0.00f, 1.00f, 1.00f) }
                                        )
                                        DropdownMenuItem(
                                            text = {
                                                Column {
                                                    Text("🏎️ Progressive Racing Pedals", fontWeight = FontWeight.Bold)
                                                    Text("Start DZ 3% • Max Cutoff 95% • Curve 1.30 (Smooth Throttle & Brake)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                            },
                                            onClick = { applyTriggerAnalogPreset(0.03f, 0.95f, 1.30f) }
                                        )
                                        DropdownMenuItem(
                                            text = {
                                                Column {
                                                    Text("⏱️ Short-Pull Quick Action", fontWeight = FontWeight.Bold)
                                                    Text("Start DZ 2% • Max Cutoff 75% • Curve 1.00 (100% on Short Squeeze)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                            },
                                            onClick = { applyTriggerAnalogPreset(0.02f, 0.75f, 1.00f) }
                                        )
                                    }
                                    1 -> { // L2 (Left Trigger / Aim / Brake)
                                        DropdownMenuItem(
                                            text = {
                                                Column {
                                                    Text("⚡ Instant Hair ADS / Snap Aim", fontWeight = FontWeight.Bold)
                                                    Text("Instant digital aim-down-sights at 3% touch", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                            },
                                            onClick = { applyTriggerHairPreset(0.03f) }
                                        )
                                        DropdownMenuItem(
                                            text = {
                                                Column {
                                                    Text("🎯 Default Linear Full Stroke", fontWeight = FontWeight.Bold)
                                                    Text("Start DZ 0% • Max Cutoff 100% • Curve 1.00", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                            },
                                            onClick = { applyTriggerAnalogPreset(0.00f, 1.00f, 1.00f) }
                                        )
                                        DropdownMenuItem(
                                            text = {
                                                Column {
                                                    Text("🛑 Firm Sim Brake", fontWeight = FontWeight.Bold)
                                                    Text("Start DZ 4% • Max Cutoff 95% • Curve 1.45 (Progressive 100% Threshold)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                            },
                                            onClick = { applyTriggerAnalogPreset(0.04f, 0.95f, 1.45f) }
                                        )
                                        DropdownMenuItem(
                                            text = {
                                                Column {
                                                    Text("🏎️ Racing Standard Brake", fontWeight = FontWeight.Bold)
                                                    Text("Start DZ 2% • Max Cutoff 95% • Curve 1.15", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                            },
                                            onClick = { applyTriggerAnalogPreset(0.02f, 0.95f, 1.15f) }
                                        )
                                    }
                                    2 -> { // R2 (Right Trigger / Fire / Gas)
                                        DropdownMenuItem(
                                            text = {
                                                Column {
                                                    Text("⚡ Feather Hair Trigger Rapid Fire", fontWeight = FontWeight.Bold)
                                                    Text("Instant firing at 2% lightest touch", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                            },
                                            onClick = { applyTriggerHairPreset(0.02f) }
                                        )
                                        DropdownMenuItem(
                                            text = {
                                                Column {
                                                    Text("🎯 Default Linear Full Throttle", fontWeight = FontWeight.Bold)
                                                    Text("Start DZ 0% • Max Cutoff 100% • Curve 1.00", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                            },
                                            onClick = { applyTriggerAnalogPreset(0.00f, 1.00f, 1.00f) }
                                        )
                                        DropdownMenuItem(
                                            text = {
                                                Column {
                                                    Text("🔫 Short-Stroke Rapid Burst", fontWeight = FontWeight.Bold)
                                                    Text("Start DZ 2% • Max Cutoff 65% • Curve 1.00 (Instant 100% on Short Pull)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                            },
                                            onClick = { applyTriggerAnalogPreset(0.02f, 0.65f, 1.00f) }
                                        )
                                        DropdownMenuItem(
                                            text = {
                                                Column {
                                                    Text("🏎️ Progressive Gas Throttle", fontWeight = FontWeight.Bold)
                                                    Text("Start DZ 2% • Max Cutoff 95% • Curve 1.25 (Fine Traction to 100% Throttle)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                            },
                                            onClick = { applyTriggerAnalogPreset(0.02f, 0.95f, 1.25f) }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (isTriggerSectionExpanded) {
                        // Hair Trigger Toggle Row (with focus indicator)
                        val isHairToggleFocused = remember { mutableStateOf(false) }
                        Surface(
                            color = if (isHairToggleFocused.value) MaterialTheme.colorScheme.tertiary.copy(alpha = 0.08f) else Color.Transparent,
                            shape = MaterialTheme.shapes.extraSmall,
                            border = BorderStroke(1.dp, if (isHairToggleFocused.value) MaterialTheme.colorScheme.tertiary.copy(alpha = 0.6f) else Color.Transparent),
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged {
                                    isHairToggleFocused.value = it.isFocused
                                    if (it.isFocused) lastFocusedSection = "triggers"
                                }
                                .onKeyEvent { keyEvent ->
                                    if ((keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_BUTTON_A || keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER) && keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_UP) {
                                        updateHairTrigger(!currentHairTrigger)
                                        lastActiveComponent = if (targetTrigger == 2) "rt" else "lt"
                                        true
                                    } else false
                                }
                                .clickable {
                                    updateHairTrigger(!currentHairTrigger)
                                    lastActiveComponent = if (targetTrigger == 2) "rt" else "lt"
                                    lastFocusedSection = "triggers"
                                }
                                .focusable()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 6.dp, vertical = 3.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Feather / Hair Trigger",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFF59E0B)
                                    )
                                    Text(
                                        text = "Instant digital click on light press",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = currentHairTrigger,
                                    onCheckedChange = {
                                        updateHairTrigger(it)
                                        lastActiveComponent = if (targetTrigger == 2) "rt" else "lt"
                                        lastFocusedSection = "triggers"
                                    }
                                )
                            }
                        }

                        if (currentHairTrigger) {
                            // Controller-Navigable Click Point Slider (2% - 80%)
                            ControllerSliderRow(
                                label = "Click Point",
                                value = currentTriggerThreshold,
                                onValueChange = {
                                    updateTriggerThreshold(it)
                                    lastActiveComponent = if (targetTrigger == 2) "rt" else "lt"
                                },
                                valueRange = 0.02f..0.80f,
                                stepSize = 0.01f,
                                displayValue = "${(currentTriggerThreshold * 100).toInt()}%",
                                dialogTitle = "Set Hair Trigger Threshold (%)",
                                isPercentage = true,
                                minVal = 0.02f,
                                maxVal = 0.80f,
                                activeColor = Color(0xFFF59E0B),
                                modifier = Modifier.onFocusChanged { if (it.isFocused) lastFocusedSection = "triggers" }
                            )
                        } else {
                            // Controller-Navigable ANALOG CONTROLS
                            // 1. Start Deadzone Slider (0% - 40%)
                            ControllerSliderRow(
                                label = "Start DZ",
                                value = currentTriggerDeadzone,
                                onValueChange = {
                                    updateTriggerDeadzone(it)
                                    lastActiveComponent = if (targetTrigger == 2) "rt" else "lt"
                                },
                                valueRange = 0.00f..0.40f,
                                stepSize = 0.01f,
                                displayValue = "${(currentTriggerDeadzone * 100).toInt()}%",
                                dialogTitle = "Set Trigger Start Deadzone (%)",
                                isPercentage = true,
                                minVal = 0.00f,
                                maxVal = 0.40f,
                                activeColor = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.onFocusChanged { if (it.isFocused) lastFocusedSection = "triggers" }
                            )

                            // 2. Max Cutoff Slider (50% - 100%)
                            ControllerSliderRow(
                                label = "Max Cutoff",
                                value = currentTriggerCutoff,
                                onValueChange = {
                                    updateTriggerCutoff(it)
                                    lastActiveComponent = if (targetTrigger == 2) "rt" else "lt"
                                },
                                valueRange = 0.50f..1.00f,
                                stepSize = 0.01f,
                                displayValue = "${(currentTriggerCutoff * 100).toInt()}%",
                                dialogTitle = "Set Trigger Max Cutoff (%)",
                                isPercentage = true,
                                minVal = 0.50f,
                                maxVal = 1.00f,
                                activeColor = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.onFocusChanged { if (it.isFocused) lastFocusedSection = "triggers" }
                            )

                            // 3. Response Curve Slider (0.20 - 4.00)
                            ControllerSliderRow(
                                label = "Curve",
                                value = currentTriggerCurve,
                                onValueChange = {
                                    updateTriggerCurve(it)
                                    lastActiveComponent = if (targetTrigger == 2) "rt" else "lt"
                                },
                                valueRange = 0.20f..4.00f,
                                stepSize = 0.05f,
                                displayValue = String.format(java.util.Locale.US, "%.2f", currentTriggerCurve),
                                dialogTitle = "Set Trigger Curve",
                                isPercentage = false,
                                minVal = 0.20f,
                                maxVal = 4.00f,
                                activeColor = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.onFocusChanged { if (it.isFocused) lastFocusedSection = "triggers" }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showExpandedHudDialog) {
        ExpandedLiveHudDialog(
            settings = settings,
            liveStickLX = liveStickLX,
            liveStickLY = liveStickLY,
            liveStickRX = liveStickRX,
            liveStickRY = liveStickRY,
            liveTriggerL = liveTriggerL,
            liveTriggerR = liveTriggerR,
            onSettingsChanged = onSettingsChanged,
            onDismiss = { showExpandedHudDialog = false }
        )
    }
}

/**
 * Fullscreen / Expanded Live HUD Dialog for high-resolution testing and inspecting response curves.
 * Features dedicated MotionEvent capturing, auto-resizing via BoxWithConstraints, and full-word configuration details.
 */
@Composable
private fun ExpandedLiveHudDialog(
    settings: ControllerAxisSettings,
    liveStickLX: Float,
    liveStickLY: Float,
    liveStickRX: Float,
    liveStickRY: Float,
    liveTriggerL: Float,
    liveTriggerR: Float,
    onSettingsChanged: (ControllerAxisSettings) -> Unit = {},
    onDismiss: () -> Unit
) {
    var curveFocusMode by remember { mutableStateOf("auto") } // "auto", "ls", "rs", "lt", "rt"
    var lastActiveComponent by remember { mutableStateOf("ls") } // "ls", "rs", "lt", "rt"
    var isTestModeActive by remember { mutableStateOf(true) } // TESTING ACTIVE BY DEFAULT ON ENTERING FULLSCREEN TEST!

    // Local live state actively updated inside the Dialog window
    var dialogStickLX by remember { mutableFloatStateOf(liveStickLX) }
    var dialogStickLY by remember { mutableFloatStateOf(liveStickLY) }
    var dialogStickRX by remember { mutableFloatStateOf(liveStickRX) }
    var dialogStickRY by remember { mutableFloatStateOf(liveStickRY) }
    var dialogTriggerL by remember { mutableFloatStateOf(liveTriggerL) }
    var dialogTriggerR by remember { mutableFloatStateOf(liveTriggerR) }

    val initialFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(100)
        try {
            initialFocusRequester.requestFocus()
        } catch (_: Exception) {}
    }

    // Determine currently displayed component on the response curve
    val activeComponentKey = if (curveFocusMode == "auto") lastActiveComponent else curveFocusMode
    val activeDeadzone = when (activeComponentKey) {
        "ls" -> settings.leftStickDeadzone
        "rs" -> settings.rightStickDeadzone
        "lt" -> settings.leftTriggerDeadzone
        else -> settings.rightTriggerDeadzone
    }
    val activeCutoff = when (activeComponentKey) {
        "ls" -> settings.leftStickOuterDeadzone
        "rs" -> settings.rightStickOuterDeadzone
        "lt" -> settings.leftTriggerCutoff
        else -> settings.rightTriggerCutoff
    }
    val activeSensitivity = when (activeComponentKey) {
        "ls" -> settings.leftStickSensitivity
        "rs" -> settings.rightStickSensitivity
        else -> 1.0f
    }
    val activeCurve = when (activeComponentKey) {
        "ls" -> settings.leftStickCurve
        "rs" -> settings.rightStickCurve
        "lt" -> settings.leftTriggerCurve
        else -> settings.rightTriggerCurve
    }
    val isTrigger = activeComponentKey == "lt" || activeComponentKey == "rt"
    val isHair = when (activeComponentKey) {
        "lt" -> settings.leftTriggerHairTrigger
        "rt" -> settings.rightTriggerHairTrigger
        else -> false
    }
    val activeThreshold = when (activeComponentKey) {
        "lt" -> settings.leftTriggerThreshold
        "rt" -> settings.rightTriggerThreshold
        else -> 0f
    }
    val activeComponentColor = when (activeComponentKey) {
        "ls" -> MaterialTheme.colorScheme.primary
        "rs" -> MaterialTheme.colorScheme.tertiary
        "lt" -> Color(0xFF10B981)
        else -> Color(0xFF06B6D4)
    }

    val leftMag = Math.sqrt((dialogStickLX * dialogStickLX + dialogStickLY * dialogStickLY).toDouble()).toFloat()
    val rightMag = Math.sqrt((dialogStickRX * dialogStickRX + dialogStickRY * dialogStickRY).toDouble()).toFloat()
    val inspectInput = when (activeComponentKey) {
        "ls" -> leftMag
        "rs" -> rightMag
        "lt" -> dialogTriggerL
        else -> dialogTriggerR
    }
    val inspectOutput = if (isTrigger) {
        if (isHair) {
            if (inspectInput >= activeThreshold) 1.0f else 0.0f
        } else {
            if (inspectInput <= activeDeadzone) 0f
            else {
                val denom = (activeCutoff - activeDeadzone).coerceAtLeast(0.01f)
                val norm = ((inspectInput - activeDeadzone) / denom).coerceIn(0f, 1f)
                Math.pow(norm.toDouble(), activeCurve.toDouble()).toFloat().coerceIn(0f, 1f)
            }
        }
    } else {
        if (inspectInput <= activeDeadzone) 0f
        else {
            val denom = (activeCutoff - activeDeadzone).coerceAtLeast(0.01f)
            val norm = ((inspectInput - activeDeadzone) / denom).coerceIn(0f, 1f)
            (Math.pow(norm.toDouble(), activeCurve.toDouble()).toFloat() * activeSensitivity).coerceIn(0f, 1f)
        }
    }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        val dialogView = LocalView.current

        DisposableEffect(dialogView, isTestModeActive) {
            val motionListener = android.view.View.OnGenericMotionListener { _, event ->
                if ((event.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK ||
                    (event.source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                ) {
                    dialogStickLX = event.getAxisValue(MotionEvent.AXIS_X)
                    dialogStickLY = event.getAxisValue(MotionEvent.AXIS_Y)

                    val device = event.device
                    val hasZ = device?.getMotionRange(MotionEvent.AXIS_Z, event.source) != null
                    val hasRZ = device?.getMotionRange(MotionEvent.AXIS_RZ, event.source) != null
                    val hasRX = device?.getMotionRange(MotionEvent.AXIS_RX, event.source) != null
                    val hasRY = device?.getMotionRange(MotionEvent.AXIS_RY, event.source) != null

                    dialogStickRX = if (hasZ) event.getAxisValue(MotionEvent.AXIS_Z) else if (hasRX) event.getAxisValue(MotionEvent.AXIS_RX) else 0f
                    dialogStickRY = if (hasRZ) event.getAxisValue(MotionEvent.AXIS_RZ) else if (hasRY) event.getAxisValue(MotionEvent.AXIS_RY) else 0f

                    val lTrig = event.getAxisValue(MotionEvent.AXIS_LTRIGGER)
                    val brake = event.getAxisValue(MotionEvent.AXIS_BRAKE)
                    val trigL = if (Math.abs(lTrig) > 0.001f) lTrig else brake

                    val rTrig = event.getAxisValue(MotionEvent.AXIS_RTRIGGER)
                    val gas = event.getAxisValue(MotionEvent.AXIS_GAS)
                    val trigR = if (Math.abs(rTrig) > 0.001f) rTrig else gas

                    dialogTriggerL = trigL.coerceIn(0f, 1f)
                    dialogTriggerR = trigR.coerceIn(0f, 1f)

                    val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
                    val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
                    val isHatMotion = Math.abs(hatX) > 0.01f || Math.abs(hatY) > 0.01f

                    // Auto-detect active component during motion
                    val lMag = Math.sqrt((dialogStickLX * dialogStickLX + dialogStickLY * dialogStickLY).toDouble()).toFloat()
                    val rMag = Math.sqrt((dialogStickRX * dialogStickRX + dialogStickRY * dialogStickRY).toDouble()).toFloat()
                    if (lMag >= rMag && lMag >= dialogTriggerL && lMag >= dialogTriggerR && lMag > 0.04f) {
                        lastActiveComponent = "ls"
                    } else if (rMag > lMag && rMag >= dialogTriggerL && rMag >= dialogTriggerR && rMag > 0.04f) {
                        lastActiveComponent = "rs"
                    } else if (dialogTriggerL > lMag && dialogTriggerL > rMag && dialogTriggerL >= dialogTriggerR && dialogTriggerL > 0.04f) {
                        lastActiveComponent = "lt"
                    } else if (dialogTriggerR > lMag && dialogTriggerR > rMag && dialogTriggerR > dialogTriggerL && dialogTriggerR > 0.04f) {
                        lastActiveComponent = "rt"
                    }

                    true // In Fullscreen Tester: always capture stick and trigger motions for telemetry!
                } else {
                    false
                }
            }

            val keyListener = android.view.View.OnKeyListener { _, keyCode, event ->
                // In Fullscreen mode: B, Y, or Back cleanly exits back to the settings editor!
                if ((keyCode == KeyEvent.KEYCODE_BUTTON_B || keyCode == KeyEvent.KEYCODE_BUTTON_Y || keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) && event.action == KeyEvent.ACTION_UP) {
                    onDismiss()
                    return@OnKeyListener true
                }
                false
            }

            dialogView.isFocusable = true
            dialogView.isFocusableInTouchMode = true
            dialogView.requestFocus()
            dialogView.setOnGenericMotionListener(motionListener)
            dialogView.rootView?.setOnGenericMotionListener(motionListener)
            dialogView.setOnKeyListener(keyListener)
            dialogView.rootView?.setOnKeyListener(keyListener)

            onDispose {
                dialogView.setOnGenericMotionListener(null)
                dialogView.rootView?.setOnGenericMotionListener(null)
                dialogView.setOnKeyListener(null)
                dialogView.rootView?.setOnKeyListener(null)
            }
        }

        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val availableW = maxWidth
                val availableH = maxHeight
                val isLandscape = availableW > availableH

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Header Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "🔍 Fullscreen Controller Cockpit",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )

                            Surface(
                                color = Color(0xFF10B981),
                                shape = MaterialTheme.shapes.extraSmall
                            ) {
                                Text(
                                    text = "🎮 Live Testing Active [B / Y to Exit]",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }

                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close"
                            )
                        }
                    }

                    if (isLandscape) {
                        // Landscape Two-Column Layout
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // Left Column: Cockpit Radars & Trigger Meters
                            Card(
                                modifier = Modifier
                                    .weight(1.02f)
                                    .fillMaxHeight(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(8.dp)
                                        .verticalScroll(rememberScrollState()),
                                    verticalArrangement = Arrangement.SpaceEvenly
                                ) {
                                    // Cockpit Radars Row (Dynamic Scaling up to 135dp)
                                    val radarSize = if (availableH >= 480.dp) 135.dp else if (availableH < 380.dp) 72.dp else 88.dp
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceEvenly,
                                        verticalAlignment = Alignment.Top
                                    ) {
                                        // Left Stick Details
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            StickRadarCrosshair(
                                                label = "Left Stick (LS)",
                                                rawX = dialogStickLX,
                                                rawY = dialogStickLY,
                                                deadzone = settings.leftStickDeadzone,
                                                outerDeadzone = settings.leftStickOuterDeadzone,
                                                sensitivity = settings.leftStickSensitivity,
                                                curve = settings.leftStickCurve,
                                                badgeColor = MaterialTheme.colorScheme.primary,
                                                radarSize = radarSize,
                                                showCoordinates = true,
                                                onDeadzoneChanged = {
                                                    val updated = settings.copy(leftStickDeadzone = it)
                                                    if (it > updated.leftStickOuterDeadzone - 0.05f) {
                                                        updated.leftStickOuterDeadzone = (it + 0.05f).coerceIn(0.70f, 1.0f)
                                                    }
                                                    onSettingsChanged(updated)
                                                },
                                                onOuterDeadzoneChanged = {
                                                    val updated = settings.copy(leftStickOuterDeadzone = it)
                                                    if (it < updated.leftStickDeadzone + 0.05f) {
                                                        updated.leftStickDeadzone = (it - 0.05f).coerceIn(0.0f, 0.50f)
                                                    }
                                                    onSettingsChanged(updated)
                                                }
                                            )
                                            Column(horizontalAlignment = Alignment.Start) {
                                                Text("Deadzone: ${(settings.leftStickDeadzone * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                                                Text("Outer DZ: ${(settings.leftStickOuterDeadzone * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                                                Text("Sensitivity: ${String.format(java.util.Locale.US, "%.2fx", settings.leftStickSensitivity)}", style = MaterialTheme.typography.labelSmall)
                                                Text("Curve: ${String.format(java.util.Locale.US, "%.2f", settings.leftStickCurve)}", style = MaterialTheme.typography.labelSmall)
                                            }
                                        }

                                        // Right Stick Details
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            StickRadarCrosshair(
                                                label = "Right Stick (RS)",
                                                rawX = dialogStickRX,
                                                rawY = dialogStickRY,
                                                deadzone = settings.rightStickDeadzone,
                                                outerDeadzone = settings.rightStickOuterDeadzone,
                                                sensitivity = settings.rightStickSensitivity,
                                                curve = settings.rightStickCurve,
                                                badgeColor = MaterialTheme.colorScheme.tertiary,
                                                radarSize = radarSize,
                                                showCoordinates = true,
                                                onDeadzoneChanged = {
                                                    val updated = settings.copy(rightStickDeadzone = it)
                                                    if (it > updated.rightStickOuterDeadzone - 0.05f) {
                                                        updated.rightStickOuterDeadzone = (it + 0.05f).coerceIn(0.70f, 1.0f)
                                                    }
                                                    onSettingsChanged(updated)
                                                },
                                                onOuterDeadzoneChanged = {
                                                    val updated = settings.copy(rightStickOuterDeadzone = it)
                                                    if (it < updated.rightStickDeadzone + 0.05f) {
                                                        updated.rightStickDeadzone = (it - 0.05f).coerceIn(0.0f, 0.50f)
                                                    }
                                                    onSettingsChanged(updated)
                                                }
                                            )
                                            Column(horizontalAlignment = Alignment.Start) {
                                                Text("Deadzone: ${(settings.rightStickDeadzone * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                                                Text("Outer DZ: ${(settings.rightStickOuterDeadzone * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                                                Text("Sensitivity: ${String.format(java.util.Locale.US, "%.2fx", settings.rightStickSensitivity)}", style = MaterialTheme.typography.labelSmall)
                                                Text("Curve: ${String.format(java.util.Locale.US, "%.2f", settings.rightStickCurve)}", style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                    }

                                    HorizontalDivider(
                                        modifier = Modifier.padding(vertical = 4.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                    )

                                    // Triggers Row
                                    val triggerLActive = if (settings.leftTriggerHairTrigger) dialogTriggerL >= settings.leftTriggerThreshold else dialogTriggerL > settings.leftTriggerDeadzone
                                    val triggerRActive = if (settings.rightTriggerHairTrigger) dialogTriggerR >= settings.rightTriggerThreshold else dialogTriggerR > settings.rightTriggerDeadzone
                                    val triggerHeight = if (availableH >= 480.dp) 85.dp else if (availableH < 380.dp) 62.dp else 78.dp

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceEvenly,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // Left Trigger (LT)
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            VerticalTriggerBar(
                                                label = "LT",
                                                rawVal = dialogTriggerL,
                                                deadzone = settings.leftTriggerDeadzone,
                                                cutoff = settings.leftTriggerCutoff,
                                                threshold = settings.leftTriggerThreshold,
                                                isActive = triggerLActive,
                                                isHairTrigger = settings.leftTriggerHairTrigger,
                                                activeColor = Color(0xFF10B981),
                                                barWidth = 20.dp,
                                                barHeight = triggerHeight
                                            )
                                            Column(horizontalAlignment = Alignment.Start) {
                                                if (settings.leftTriggerHairTrigger) {
                                                    Text("Feather / Hair Trigger", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFFF59E0B))
                                                    Text("Click Point: ${(settings.leftTriggerThreshold * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                                                } else {
                                                    Text("Start Deadzone: ${(settings.leftTriggerDeadzone * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                                                    Text("Max Cutoff: ${(settings.leftTriggerCutoff * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                                                    Text("Curve: ${String.format(java.util.Locale.US, "%.2f", settings.leftTriggerCurve)}", style = MaterialTheme.typography.labelSmall)
                                                }
                                            }
                                        }

                                        // Right Trigger (RT)
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            VerticalTriggerBar(
                                                label = "RT",
                                                rawVal = dialogTriggerR,
                                                deadzone = settings.rightTriggerDeadzone,
                                                cutoff = settings.rightTriggerCutoff,
                                                threshold = settings.rightTriggerThreshold,
                                                isActive = triggerRActive,
                                                isHairTrigger = settings.rightTriggerHairTrigger,
                                                activeColor = Color(0xFF06B6D4),
                                                barWidth = 20.dp,
                                                barHeight = triggerHeight
                                            )
                                            Column(horizontalAlignment = Alignment.Start) {
                                                if (settings.rightTriggerHairTrigger) {
                                                    Text("Feather / Hair Trigger", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFFF59E0B))
                                                    Text("Click Point: ${(settings.rightTriggerThreshold * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                                                } else {
                                                    Text("Start Deadzone: ${(settings.rightTriggerDeadzone * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                                                    Text("Max Cutoff: ${(settings.rightTriggerCutoff * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                                                    Text("Curve: ${String.format(java.util.Locale.US, "%.2f", settings.rightTriggerCurve)}", style = MaterialTheme.typography.labelSmall)
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            // Right Column: Auto-Detecting Dynamic Response Curve Visualizer & Inspector
                            Column(
                                modifier = Modifier
                                    .weight(1.18f)
                                    .fillMaxHeight(),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                // Auto / Lock Segmented Selector Row
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    val isAutoSelected = curveFocusMode == "auto"
                                    OutlinedButton(
                                        onClick = { curveFocusMode = "auto" },
                                        modifier = Modifier
                                            .weight(1.1f)
                                            .focusRequester(initialFocusRequester),
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            containerColor = if (isAutoSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent
                                        ),
                                        border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(
                                            brush = androidx.compose.ui.graphics.SolidColor(if (isAutoSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                                        ),
                                        contentPadding = PaddingValues(horizontal = 3.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = if (isAutoSelected) "🔄 Auto ($lastActiveComponent)" else "🔄 Auto",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = if (isAutoSelected) FontWeight.Bold else FontWeight.Normal
                                        )
                                    }

                                    val tabs = listOf(
                                        Triple("ls", "🕹️ LS", MaterialTheme.colorScheme.primary),
                                        Triple("rs", "🕹️ RS", MaterialTheme.colorScheme.tertiary),
                                        Triple("lt", "⚡ LT", Color(0xFF10B981)),
                                        Triple("rt", "⚡ RT", Color(0xFF06B6D4))
                                    )
                                    tabs.forEach { (key, label, tabColor) ->
                                        val isSelected = curveFocusMode == key || (isAutoSelected && activeComponentKey == key)
                                        OutlinedButton(
                                            onClick = { curveFocusMode = key },
                                            modifier = Modifier.weight(1f),
                                            colors = ButtonDefaults.outlinedButtonColors(
                                                containerColor = if (isSelected) tabColor.copy(alpha = 0.18f) else Color.Transparent
                                            ),
                                            border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(
                                                brush = androidx.compose.ui.graphics.SolidColor(if (isSelected) tabColor else MaterialTheme.colorScheme.outline)
                                            ),
                                            contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = label,
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                color = if (isSelected) tabColor else MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    }
                                }

                                // Configuration Summary in Full Words
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    shape = MaterialTheme.shapes.extraSmall,
                                    border = BorderStroke(1.dp, activeComponentColor.copy(alpha = 0.4f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = if (isTrigger && isHair) {
                                            "Active: ${activeComponentKey.uppercase()} • Feather / Hair Trigger (Click at ${(activeThreshold * 100).toInt()}%)"
                                        } else if (isTrigger) {
                                            "Active: ${activeComponentKey.uppercase()} • Start DZ ${(activeDeadzone * 100).toInt()}% • Max Cutoff ${(activeCutoff * 100).toInt()}% • Response Curve ${String.format(java.util.Locale.US, "%.2f", activeCurve)}"
                                        } else {
                                            "Active: ${activeComponentKey.uppercase()} • Deadzone ${(activeDeadzone * 100).toInt()}% • Outer DZ ${(activeCutoff * 100).toInt()}% • Sens ${String.format(java.util.Locale.US, "%.2f", activeSensitivity)}x • Curve ${String.format(java.util.Locale.US, "%.2f", activeCurve)}"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Medium,
                                        color = activeComponentColor,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                    )
                                }

                                val onSurfaceColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                val deadzoneColor = MaterialTheme.colorScheme.error.copy(alpha = 0.25f)
                                val threshColor = Color(0xFFF59E0B)

                                // High-Resolution Curve Canvas
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                        .background(MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small)
                                        .padding(8.dp)
                                ) {
                                    Canvas(modifier = Modifier.fillMaxSize()) {
                                        val w = size.width
                                        val h = size.height

                                        // 1. Grid Lines (0%, 25%, 50%, 75%, 100%)
                                        for (gridI in 1..3) {
                                            val gridFrac = gridI * 0.25f
                                            drawLine(color = onSurfaceColor.copy(alpha = 0.15f), start = Offset(0f, h * gridFrac), end = Offset(w, h * gridFrac), strokeWidth = 1f)
                                            drawLine(color = onSurfaceColor.copy(alpha = 0.15f), start = Offset(w * gridFrac, 0f), end = Offset(w * gridFrac, h), strokeWidth = 1f)
                                        }
                                        drawLine(color = onSurfaceColor, start = Offset(0f, h), end = Offset(w, h), strokeWidth = 2f)
                                        drawLine(color = onSurfaceColor, start = Offset(0f, 0f), end = Offset(0f, h), strokeWidth = 2f)
                                        drawLine(color = onSurfaceColor.copy(alpha = 0.25f), start = Offset(0f, h), end = Offset(w, 0f), strokeWidth = 1.5f)

                                        // 2. Deadzone Region (Left)
                                        val dzX = activeDeadzone * w
                                        if (dzX > 0f) {
                                            drawRect(color = deadzoneColor, topLeft = Offset(0f, 0f), size = Size(dzX, h))
                                            drawLine(color = Color(0xFFEF4444).copy(alpha = 0.6f), start = Offset(dzX, 0f), end = Offset(dzX, h), strokeWidth = 1.5f)
                                        }

                                        // 3. Cutoff / Outer DZ Region (Right)
                                        val cutX = activeCutoff * w
                                        if (cutX < w) {
                                            drawRect(color = Color(0xFFEF4444).copy(alpha = 0.15f), topLeft = Offset(cutX, 0f), size = Size(w - cutX, h))
                                            drawLine(color = Color(0xFFEF4444).copy(alpha = 0.7f), start = Offset(cutX, 0f), end = Offset(cutX, h), strokeWidth = 1.5f)
                                        }

                                        // 4. Response Curve Path
                                        if (isTrigger && isHair) {
                                            val threshX = activeThreshold * w
                                            drawLine(color = threshColor, start = Offset(0f, h), end = Offset(threshX, h), strokeWidth = 3.5f)
                                            drawLine(color = threshColor, start = Offset(threshX, h), end = Offset(threshX, 3f), strokeWidth = 3.5f)
                                            drawLine(color = threshColor, start = Offset(threshX, 3f), end = Offset(w, 3f), strokeWidth = 3.5f)
                                        } else {
                                            val path = Path()
                                            val steps = 60
                                            val denom = (activeCutoff - activeDeadzone).coerceAtLeast(0.01f)
                                            for (i in 0..steps) {
                                                val inV = i / steps.toFloat()
                                                val outV = if (inV <= activeDeadzone) 0f
                                                else {
                                                    val norm = ((inV - activeDeadzone) / denom).coerceIn(0f, 1f)
                                                    val curved = Math.pow(norm.toDouble(), activeCurve.toDouble()).toFloat()
                                                    (curved * activeSensitivity).coerceIn(0f, 1f)
                                                }
                                                val px = inV * w
                                                val py = h - (outV * (h - 6f)) - 3f
                                                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                                            }
                                            drawPath(path = path, color = activeComponentColor, style = Stroke(width = 3.5f))
                                        }

                                        // 5. Live moving dot
                                        if (inspectInput > 0.01f) {
                                            val dotX = (inspectInput.coerceIn(0f, 1f) * w)
                                            val dotY = h - (inspectOutput.coerceIn(0f, 1f) * (h - 6f)) - 3f
                                            val dotColor = if (isTrigger && isHair) threshColor else activeComponentColor
                                            drawCircle(color = dotColor.copy(alpha = 0.35f), radius = 10f, center = Offset(dotX, dotY))
                                            drawCircle(color = dotColor, radius = 6f, center = Offset(dotX, dotY))
                                        }
                                    }
                                }

                                // Live Numeric Info Row
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Input: ${(inspectInput * 100).toInt()}%",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = "Output to Game: ${(inspectOutput * 100).toInt()}%",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold,
                                        color = activeComponentColor
                                    )
                                }
                            }
                        }
                    } else {
                        // Portrait Fallback Layout with Cockpit Radars & Auto Curve
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // Top: Radars & Triggers
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                StickRadarCrosshair(
                                    label = "LS",
                                    rawX = dialogStickLX,
                                    rawY = dialogStickLY,
                                    deadzone = settings.leftStickDeadzone,
                                    outerDeadzone = settings.leftStickOuterDeadzone,
                                    sensitivity = settings.leftStickSensitivity,
                                    curve = settings.leftStickCurve,
                                    badgeColor = MaterialTheme.colorScheme.primary,
                                    radarSize = 90.dp,
                                    showCoordinates = true,
                                    onDeadzoneChanged = {
                                        val updated = settings.copy(leftStickDeadzone = it)
                                        if (it > updated.leftStickOuterDeadzone - 0.05f) {
                                            updated.leftStickOuterDeadzone = (it + 0.05f).coerceIn(0.70f, 1.0f)
                                        }
                                        onSettingsChanged(updated)
                                    },
                                    onOuterDeadzoneChanged = {
                                        val updated = settings.copy(leftStickOuterDeadzone = it)
                                        if (it < updated.leftStickDeadzone + 0.05f) {
                                            updated.leftStickDeadzone = (it - 0.05f).coerceIn(0.0f, 0.50f)
                                        }
                                        onSettingsChanged(updated)
                                    }
                                )
                                StickRadarCrosshair(
                                    label = "RS",
                                    rawX = dialogStickRX,
                                    rawY = dialogStickRY,
                                    deadzone = settings.rightStickDeadzone,
                                    outerDeadzone = settings.rightStickOuterDeadzone,
                                    sensitivity = settings.rightStickSensitivity,
                                    curve = settings.rightStickCurve,
                                    badgeColor = MaterialTheme.colorScheme.tertiary,
                                    radarSize = 90.dp,
                                    showCoordinates = true,
                                    onDeadzoneChanged = {
                                        val updated = settings.copy(rightStickDeadzone = it)
                                        if (it > updated.rightStickOuterDeadzone - 0.05f) {
                                            updated.rightStickOuterDeadzone = (it + 0.05f).coerceIn(0.70f, 1.0f)
                                        }
                                        onSettingsChanged(updated)
                                    },
                                    onOuterDeadzoneChanged = {
                                        val updated = settings.copy(rightStickOuterDeadzone = it)
                                        if (it < updated.rightStickDeadzone + 0.05f) {
                                            updated.rightStickDeadzone = (it - 0.05f).coerceIn(0.0f, 0.50f)
                                        }
                                        onSettingsChanged(updated)
                                    }
                                )
                                val triggerLActive = if (settings.leftTriggerHairTrigger) dialogTriggerL >= settings.leftTriggerThreshold else dialogTriggerL > settings.leftTriggerDeadzone
                                val triggerRActive = if (settings.rightTriggerHairTrigger) dialogTriggerR >= settings.rightTriggerThreshold else dialogTriggerR > settings.rightTriggerDeadzone
                                VerticalTriggerBar(
                                    label = "LT",
                                    rawVal = dialogTriggerL,
                                    deadzone = settings.leftTriggerDeadzone,
                                    cutoff = settings.leftTriggerCutoff,
                                    threshold = settings.leftTriggerThreshold,
                                    isActive = triggerLActive,
                                    isHairTrigger = settings.leftTriggerHairTrigger,
                                    activeColor = Color(0xFF10B981),
                                    barWidth = 20.dp,
                                    barHeight = 75.dp
                                )
                                VerticalTriggerBar(
                                    label = "RT",
                                    rawVal = dialogTriggerR,
                                    deadzone = settings.rightTriggerDeadzone,
                                    cutoff = settings.rightTriggerCutoff,
                                    threshold = settings.rightTriggerThreshold,
                                    isActive = triggerRActive,
                                    isHairTrigger = settings.rightTriggerHairTrigger,
                                    activeColor = Color(0xFF06B6D4),
                                    barWidth = 20.dp,
                                    barHeight = 75.dp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Controller-navigable slider row with two-stage focus (subtle highlight) and edit mode (prominent neon glow).
 * In Edit Mode (toggled via A or B), D-pad Left/Right adjusts value with progressive hold acceleration.
 */
@Composable
private fun ControllerSliderRow(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    stepSize: Float = 0.01f,
    displayValue: String,
    dialogTitle: String,
    isPercentage: Boolean = false,
    minVal: Float = valueRange.start,
    maxVal: Float = valueRange.endInclusive,
    activeColor: Color = MaterialTheme.colorScheme.primary,
    onEditModeChanged: ((Boolean) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var isRowFocused by remember { mutableStateOf(false) }
    var isEditing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var holdJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var currentHoldDir by remember { mutableFloatStateOf(0f) }

    val currentValueState = rememberUpdatedState(value)
    val onValueChangeState = rememberUpdatedState(onValueChange)

    fun setEditingMode(editing: Boolean) {
        isEditing = editing
        onEditModeChanged?.invoke(editing)
        if (!editing) {
            holdJob?.cancel()
            holdJob = null
            currentHoldDir = 0f
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            holdJob?.cancel()
            holdJob = null
            currentHoldDir = 0f
        }
    }

    val rowBorder = if (isEditing) {
        BorderStroke(2.dp, activeColor)
    } else if (isRowFocused) {
        BorderStroke(1.dp, activeColor.copy(alpha = 0.6f))
    } else {
        BorderStroke(1.dp, Color.Transparent)
    }

    val rowBackground = if (isEditing) {
        activeColor.copy(alpha = 0.15f)
    } else if (isRowFocused) {
        activeColor.copy(alpha = 0.06f)
    } else {
        Color.Transparent
    }

    Surface(
        color = rowBackground,
        shape = MaterialTheme.shapes.extraSmall,
        border = rowBorder,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged {
                isRowFocused = it.isFocused
                if (!it.isFocused && isEditing) {
                    setEditingMode(false)
                }
            }
            .onKeyEvent { keyEvent ->
                val keyCode = keyEvent.nativeKeyEvent.keyCode
                val action = keyEvent.nativeKeyEvent.action

                if (keyCode == KeyEvent.KEYCODE_BUTTON_A || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                    if (action == KeyEvent.ACTION_UP) {
                        setEditingMode(!isEditing)
                    }
                    return@onKeyEvent true
                }

                if (keyCode == KeyEvent.KEYCODE_BUTTON_B || keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
                    if (isEditing) {
                        if (action == KeyEvent.ACTION_UP) {
                            setEditingMode(false)
                        }
                        return@onKeyEvent true
                    }
                    return@onKeyEvent false
                }

                if (isEditing) {
                    if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                        return@onKeyEvent true // Lock Up/Down while in Edit Mode
                    }

                    if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                        val dir = if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1f else 1f

                        if (action == KeyEvent.ACTION_DOWN) {
                            if (holdJob == null || currentHoldDir != dir) {
                                holdJob?.cancel()
                                currentHoldDir = dir

                                // 1. Immediate step on press
                                var runningVal = (currentValueState.value + dir * stepSize).coerceIn(valueRange.start, valueRange.endInclusive)
                                onValueChangeState.value(runningVal)

                                // 2. Continuous hold loop
                                holdJob = scope.launch {
                                    kotlinx.coroutines.delay(220)
                                    var elapsed = 0L
                                    while (isActive) {
                                        val tickDelay = if (elapsed < 600) 100L else 55L
                                        kotlinx.coroutines.delay(tickDelay)
                                        elapsed += tickDelay
                                        runningVal = (runningVal + dir * stepSize).coerceIn(valueRange.start, valueRange.endInclusive)
                                        onValueChangeState.value(runningVal)
                                    }
                                }
                            }
                        } else if (action == KeyEvent.ACTION_UP) {
                            if (currentHoldDir == dir) {
                                holdJob?.cancel()
                                holdJob = null
                                currentHoldDir = 0f
                            }
                        }
                        return@onKeyEvent true
                    }
                }
                false
            }
            .focusable()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.width(76.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                if (isEditing) {
                    Text("◀▶", style = MaterialTheme.typography.labelSmall, color = activeColor, fontWeight = FontWeight.Bold)
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (isEditing || isRowFocused) FontWeight.Bold else FontWeight.Medium,
                    color = if (isEditing) activeColor else MaterialTheme.colorScheme.onSurface
                )
            }

            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = valueRange,
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(
                    thumbColor = if (isEditing) activeColor else MaterialTheme.colorScheme.primary,
                    activeTrackColor = if (isEditing) activeColor else MaterialTheme.colorScheme.primary
                )
            )

            ClickableValueBadge(
                displayValue = displayValue,
                dialogTitle = dialogTitle,
                currentValue = value,
                isPercentage = isPercentage,
                minVal = minVal,
                maxVal = maxVal,
                onValueConfirmed = onValueChange,
                modifier = Modifier.width(44.dp)
            )
        }
    }
}

/**
 * Clickable value badge that displays the current value and opens a numeric entry dialog when tapped.
 */
@Composable
private fun ClickableValueBadge(
    displayValue: String,
    dialogTitle: String,
    currentValue: Float,
    isPercentage: Boolean,
    minVal: Float,
    maxVal: Float,
    onValueConfirmed: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var showDialog by remember { mutableStateOf(false) }
    var textInput by remember { mutableStateOf("") }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.extraSmall,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
        modifier = modifier.clickable {
            textInput = if (isPercentage) "${(currentValue * 100).toInt()}" else String.format(java.util.Locale.US, "%.2f", currentValue)
            showDialog = true
        }
    ) {
        Text(
            text = displayValue,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        )
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(dialogTitle, style = MaterialTheme.typography.titleMedium) },
            text = {
                OutlinedTextField(
                    value = textInput,
                    onValueChange = { textInput = it },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val parsed = textInput.toFloatOrNull()
                        if (parsed != null) {
                            val finalVal = if (isPercentage) (parsed / 100f).coerceIn(minVal, maxVal) else parsed.coerceIn(minVal, maxVal)
                            onValueConfirmed(finalVal)
                        }
                        showDialog = false
                    }
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

/**
 * Visual Stick Radar Crosshair showing deadzone, outer threshold, sensitivity, and live motion dot.
 * Supports bi-directional radial touch drag to adjust inner/outer deadzones.
 */
@Composable
private fun StickRadarCrosshair(
    label: String,
    rawX: Float,
    rawY: Float,
    deadzone: Float,
    outerDeadzone: Float = 1.0f,
    sensitivity: Float,
    curve: Float,
    badgeColor: Color,
    radarSize: androidx.compose.ui.unit.Dp = 62.dp,
    showCoordinates: Boolean = false,
    onDeadzoneChanged: ((Float) -> Unit)? = null,
    onOuterDeadzoneChanged: ((Float) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val primaryColor = badgeColor
    val errorColor = MaterialTheme.colorScheme.error.copy(alpha = 0.35f)
    val onSurfaceColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    val rawDotColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)

    var draggingTarget by remember { mutableStateOf<String?>(null) } // "inner", "outer", null

    // Calculate processed output with outer deadzone normalization
    val rawMagnitude = Math.sqrt((rawX * rawX + rawY * rawY).toDouble()).toFloat()
    val (outX, outY) = if (rawMagnitude <= deadzone || rawMagnitude == 0f) {
        Pair(0f, 0f)
    } else {
        val denom = (outerDeadzone - deadzone).coerceAtLeast(0.01f)
        val normalized = ((rawMagnitude - deadzone) / denom).coerceIn(0f, 1f)
        val curved = Math.pow(normalized.toDouble(), curve.toDouble()).toFloat()
        val scaledMagnitude = (curved * sensitivity).coerceIn(0f, 1f)
        val factor = scaledMagnitude / rawMagnitude
        Pair((rawX * factor).coerceIn(-1f, 1f), (rawY * factor).coerceIn(-1f, 1f))
    }

    val inDeadzone = rawMagnitude <= deadzone && rawMagnitude > 0.01f
    val errorBorderColor = MaterialTheme.colorScheme.error.copy(alpha = 0.6f)

    val dragModifier = if (onDeadzoneChanged != null || onOuterDeadzoneChanged != null) {
        Modifier.pointerInput(deadzone, outerDeadzone) {
            detectDragGestures(
                onDragStart = { offset ->
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val radius = size.width / 2f - 3f
                    val dx = offset.x - center.x
                    val dy = offset.y - center.y
                    val dist = (Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat() / radius).coerceIn(0f, 1f)
                    val innerDiff = Math.abs(dist - deadzone)
                    val outerDiff = Math.abs(dist - outerDeadzone)
                    draggingTarget = if (innerDiff < outerDiff && innerDiff < 0.25f && onDeadzoneChanged != null) {
                        "inner"
                    } else if (outerDiff < 0.25f && onOuterDeadzoneChanged != null) {
                        "outer"
                    } else if (dist < deadzone + 0.12f && onDeadzoneChanged != null) {
                        "inner"
                    } else if (onOuterDeadzoneChanged != null) {
                        "outer"
                    } else null
                },
                onDragEnd = { draggingTarget = null },
                onDragCancel = { draggingTarget = null },
                onDrag = { change, _ ->
                    change.consume()
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val radius = size.width / 2f - 3f
                    val dx = change.position.x - center.x
                    val dy = change.position.y - center.y
                    val dist = (Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat() / radius).coerceIn(0f, 1f)
                    if (draggingTarget == "inner" && onDeadzoneChanged != null) {
                        val maxAllowed = (outerDeadzone - 0.05f).coerceAtLeast(0.01f)
                        onDeadzoneChanged(dist.coerceIn(0f, maxAllowed))
                    } else if (draggingTarget == "outer" && onOuterDeadzoneChanged != null) {
                        val minAllowed = (deadzone + 0.05f).coerceIn(0.50f, 1.0f)
                        onOuterDeadzoneChanged(dist.coerceIn(minAllowed, 1.0f))
                    }
                }
            )
        }
    } else Modifier

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = badgeColor
        )

        Box(
            modifier = Modifier
                .size(radarSize)
                .then(dragModifier)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val radius = size.width / 2f - 3f

                // Outer boundary
                drawCircle(
                    color = if (draggingTarget != null) primaryColor.copy(alpha = 0.8f) else onSurfaceColor,
                    radius = radius,
                    center = center,
                    style = Stroke(width = if (draggingTarget != null) 2.5f else 1.5f)
                )

                // Crosshair
                drawLine(color = onSurfaceColor.copy(alpha = 0.25f), start = Offset(center.x, 3f), end = Offset(center.x, size.height - 3f), strokeWidth = 1f)
                drawLine(color = onSurfaceColor.copy(alpha = 0.25f), start = Offset(3f, center.y), end = Offset(size.width - 3f, center.y), strokeWidth = 1f)

                // Deadzone Ring
                val deadzoneRadius = radius * deadzone.coerceIn(0f, 0.9f)
                drawCircle(color = if (inDeadzone) errorColor.copy(alpha = 0.6f) else errorColor, radius = deadzoneRadius, center = center)
                drawCircle(
                    color = if (draggingTarget == "inner") Color(0xFFEF4444) else errorBorderColor,
                    radius = deadzoneRadius,
                    center = center,
                    style = Stroke(width = if (draggingTarget == "inner") 2.5f else 1f)
                )

                // Outer Threshold Ring (Thick High-Contrast Line)
                val outerRadius = radius * outerDeadzone.coerceIn(0.5f, 1.0f)
                drawCircle(
                    color = if (draggingTarget == "outer") primaryColor else primaryColor.copy(alpha = 0.85f),
                    radius = outerRadius,
                    center = center,
                    style = Stroke(width = if (draggingTarget == "outer") 4.0f else 3.0f)
                )

                // Raw input ghost dot
                val rawDotX = center.x + (rawX.coerceIn(-1f, 1f) * radius)
                val rawDotY = center.y + (rawY.coerceIn(-1f, 1f) * radius)
                if (rawMagnitude > 0.01f) {
                    drawCircle(color = rawDotColor, radius = 3.5f, center = Offset(rawDotX, rawDotY))
                }

                // Processed output dot
                val outDotX = center.x + (outX * radius)
                val outDotY = center.y + (outY * radius)
                drawCircle(
                    color = if (rawMagnitude > 0.01f && !inDeadzone) primaryColor else onSurfaceColor,
                    radius = 6f,
                    center = Offset(outDotX, outDotY)
                )
            }
        }

        if (showCoordinates) {
            Text(
                text = "X:${String.format(java.util.Locale.US, "%+.2f", rawX)} Y:${String.format(java.util.Locale.US, "%+.2f", rawY)}",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = androidx.compose.ui.unit.TextUnit(9f, androidx.compose.ui.unit.TextUnitType.Sp)),
                fontWeight = FontWeight.Medium,
                color = badgeColor
            )
        }

        // Compact parameter badge
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.extraSmall
        ) {
            Text(
                text = if (outerDeadzone < 0.99f) "${(deadzone * 100).toInt()}%-${(outerDeadzone * 100).toInt()}% • ${String.format(java.util.Locale.US, "%.1fx", sensitivity)} • C${String.format(java.util.Locale.US, "%.1f", curve)}"
                       else "${(deadzone * 100).toInt()}% • ${String.format(java.util.Locale.US, "%.1fx", sensitivity)} • C${String.format(java.util.Locale.US, "%.1f", curve)}",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = androidx.compose.ui.unit.TextUnit(9.5f, androidx.compose.ui.unit.TextUnitType.Sp)),
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                color = badgeColor
            )
        }
    }
}

/**
 * Vertical Trigger meter bar with deadzone, cutoff, hair-trigger threshold indicators, and live level fill.
 */
@Composable
private fun VerticalTriggerBar(
    label: String,
    rawVal: Float,
    deadzone: Float,
    cutoff: Float,
    threshold: Float,
    isActive: Boolean,
    isHairTrigger: Boolean,
    activeColor: Color,
    barWidth: androidx.compose.ui.unit.Dp = 18.dp,
    barHeight: androidx.compose.ui.unit.Dp = 62.dp,
    modifier: Modifier = Modifier
) {
    val outlineColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    val deadzoneColor = MaterialTheme.colorScheme.error.copy(alpha = 0.35f)
    val hairColor = Color(0xFFF59E0B)

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = activeColor
        )

        Box(
            modifier = Modifier
                .width(barWidth)
                .height(barHeight)
                .background(MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.extraSmall)
                .border(1.dp, if (isActive) activeColor else outlineColor, shape = MaterialTheme.shapes.extraSmall)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height

                // Draw deadzone fill at bottom
                if (!isHairTrigger && deadzone > 0f) {
                    val dzH = deadzone.coerceIn(0f, 0.4f) * h
                    drawRect(color = deadzoneColor, topLeft = Offset(0f, h - dzH), size = Size(w, dzH))
                }

                // Draw cutoff marker at top
                if (!isHairTrigger && cutoff < 1.0f) {
                    val cutY = (1.0f - cutoff.coerceIn(0.5f, 1.0f)) * h
                    drawRect(color = Color(0xFFEF4444).copy(alpha = 0.2f), topLeft = Offset(0f, 0f), size = Size(w, cutY))
                    drawLine(color = Color(0xFFEF4444).copy(alpha = 0.6f), start = Offset(0f, cutY), end = Offset(w, cutY), strokeWidth = 1.5f)
                }

                // Draw hair trigger threshold line
                if (isHairTrigger) {
                    val threshY = (1f - threshold.coerceIn(0f, 1f)) * h
                    drawLine(color = hairColor, start = Offset(0f, threshY), end = Offset(w, threshY), strokeWidth = 2f)
                }

                // Fill current value from bottom up
                val fillH = rawVal.coerceIn(0f, 1f) * h
                if (fillH > 0.5f) {
                    drawRect(
                        color = if (isHairTrigger && isActive) hairColor else activeColor,
                        topLeft = Offset(0f, h - fillH),
                        size = Size(w, fillH)
                    )
                }
            }
        }

        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.extraSmall
        ) {
            Text(
                text = if (isHairTrigger && isActive) "FIRE"
                       else if (!isHairTrigger && cutoff < 1.0f) "${(deadzone * 100).toInt()}%-${(cutoff * 100).toInt()}%"
                       else "${(rawVal * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = androidx.compose.ui.unit.TextUnit(9f, androidx.compose.ui.unit.TextUnitType.Sp)),
                fontWeight = FontWeight.Bold,
                color = if (isActive) activeColor else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 2.dp, vertical = 1.dp)
            )
        }
    }
}
