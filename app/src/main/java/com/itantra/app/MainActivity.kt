package com.itantra.app

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.itantra.app.ui.components.MissionBottomNav
import com.itantra.app.ui.components.MissionDestination
import com.itantra.app.ui.components.soft.SoftIcon
import com.itantra.app.ui.screens.RescueScreen
import com.itantra.app.ui.screens.SettingsScreen
import com.itantra.app.ui.screens.SosDistressScreen
import com.itantra.app.ui.screens.OnboardingScreen
import com.itantra.app.ui.screens.WalkieScreen
import com.itantra.app.ui.theme.MinimalColorsInstance
import com.itantra.app.ui.theme.MyApplicationTheme
import com.itantra.app.viewmodel.MissionControlViewModel

class MainActivity : ComponentActivity() {

    companion object {
        const val ACTION_EMERGENCY_LOCKSCREEN_SOS = "com.itantra.app.ACTION_EMERGENCY_LOCKSCREEN_SOS"
        const val EXTRA_LOCKSCREEN_SOS = "extra_lockscreen_sos"
    }

    private val isLockscreenSosTriggered = mutableStateOf(false)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        checkEmergencyIntent(intent)
        applyLockscreenVisibility()
    }

    private fun checkEmergencyIntent(intent: Intent?) {
        val isEmergency = intent?.getBooleanExtra(EXTRA_LOCKSCREEN_SOS, false) == true ||
            intent?.action == ACTION_EMERGENCY_LOCKSCREEN_SOS
        isLockscreenSosTriggered.value = isEmergency
        if (isEmergency) {
            android.util.Log.w("MainActivity", "🚨 Emergency lockscreen SOS intent received!")
        }
    }

    private fun applyLockscreenVisibility() {
        if (!isLockscreenSosTriggered.value) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            keyguardManager?.requestDismissKeyguard(this, null)
        }
    }

    private fun clearLockscreenVisibility() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(false)
            setTurnScreenOn(false)
        } else {
            @Suppress("DEPRECATION")
            window.clearFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkEmergencyIntent(intent)
        applyLockscreenVisibility()
        enableEdgeToEdge()
        setContent {
            val viewModel: MissionControlViewModel = viewModel()
            val uiState by viewModel.uiState.collectAsState()
            val isSystemDark = isSystemInDarkTheme()

            // Dynamic Keep Screen Awake handling based on tactical settings
            LaunchedEffect(uiState.keepScreenAwake) {
                if (uiState.keepScreenAwake) {
                    window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            // Theme evaluation (Light / Dark / System)
            val isDark = when (uiState.themeMode) {
                "light" -> false
                "dark" -> true
                "system" -> isSystemDark
                else -> false
            }

            MyApplicationTheme(darkTheme = isDark) {
                if (!uiState.isOnboardingCompleted && !isLockscreenSosTriggered.value) {
                    OnboardingScreen(
                        viewModel = viewModel,
                        onContinue = { /* DataStore auto-updates uiState */ }
                    )
                } else {
                    MainAppContent(
                        viewModel = viewModel,
                        isLockscreenSosTriggered = isLockscreenSosTriggered.value,
                        onEmergencyEnded = {
                            isLockscreenSosTriggered.value = false
                            clearLockscreenVisibility()
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun MainAppContent(
    viewModel: MissionControlViewModel,
    isLockscreenSosTriggered: Boolean = false,
    onEmergencyEnded: () -> Unit = {}
) {
    val context = LocalContext.current
    val colors = MinimalColorsInstance

    val requiredPermissions = remember {
        val list = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list.add(Manifest.permission.BLUETOOTH_CONNECT)
            list.add(Manifest.permission.BLUETOOTH_SCAN)
            list.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        list.toTypedArray()
    }

    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasMicPermission = results[Manifest.permission.RECORD_AUDIO] ?: false
        viewModel.onPermissionsGranted()
    }

    LaunchedEffect(Unit) {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    var isBluetoothEnabled by remember { mutableStateOf(viewModel.isBluetoothEnabled()) }
    var isLocationEnabled by remember { mutableStateOf(viewModel.isLocationEnabled()) }

    val enableBtLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        val btNow = viewModel.isBluetoothEnabled()
        isBluetoothEnabled = btNow
        if (btNow) {
            viewModel.onBluetoothStateRestored()
        }
    }

    val enableLocLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        isLocationEnabled = viewModel.isLocationEnabled()
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val btNow = viewModel.isBluetoothEnabled()
                val locNow = viewModel.isLocationEnabled()
                val btRestored = !isBluetoothEnabled && btNow
                isBluetoothEnabled = btNow
                isLocationEnabled = locNow
                if (btRestored) {
                    viewModel.onBluetoothStateRestored()
                }
                viewModel.onPermissionsGranted()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Default to the 1st primary mode: SOS
    var currentDestination by remember { mutableStateOf(MissionDestination.SOS) }
    val alertCount by viewModel.victimAlertCount.collectAsState()
    val isSosBroadcasting by viewModel.isSosBroadcasting.collectAsState()
    val isRescueActive by viewModel.isRescueActive.collectAsState()

    // If triggered from 5-click lockscreen shortcut, immediately ensure SOS destination and start distress
    LaunchedEffect(isLockscreenSosTriggered) {
        if (isLockscreenSosTriggered) {
            android.util.Log.w("MainActivity", "🚨 Applying Lockscreen SOS UI: destination=SOS, startSos()")
            currentDestination = MissionDestination.SOS
            (context as? android.app.Activity)?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (!viewModel.isSosBroadcasting.value) {
                viewModel.startSos()
            }
        }
    }

    // Auto-close directly back to lockscreen when SOS is turned OFF
    var wasSosActiveByLockscreen by remember { mutableStateOf(false) }
    LaunchedEffect(isSosBroadcasting, isLockscreenSosTriggered) {
        android.util.Log.d("MainActivity", "SOS Lockscreen state update: isLockscreenSosTriggered=$isLockscreenSosTriggered, isSosBroadcasting=$isSosBroadcasting, wasSosActiveByLockscreen=$wasSosActiveByLockscreen")
        if (isLockscreenSosTriggered && isSosBroadcasting) {
            wasSosActiveByLockscreen = true
        } else if (wasSosActiveByLockscreen && !isSosBroadcasting) {
            // SOS was active and was now cancelled / turned off by user.
            // Immediately dismiss trigger notification, release keep-screen-on, and close the activity to return straight to the locked lock screen!
            android.util.Log.w("MainActivity", "🔒 Lockscreen SOS turned off by user. Closing app back to lock screen.")
            val notifManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
            notifManager?.cancel(0x505)
            (context as? android.app.Activity)?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            // One-shot: disarm so this branch can never re-fire on a surviving activity.
            wasSosActiveByLockscreen = false
            // Reset the activity's emergency state and lockscreen visibility before closing.
            onEmergencyEnded()
            (context as? android.app.Activity)?.finish()
        }
    }

    // Proactively prompt user to turn on Bluetooth if entering an active mesh mode
    LaunchedEffect(currentDestination, isSosBroadcasting, isRescueActive) {
        val needsBle = currentDestination == MissionDestination.SOS ||
            currentDestination == MissionDestination.RESCUE ||
            isSosBroadcasting ||
            isRescueActive
        if (needsBle && !viewModel.isBluetoothEnabled()) {
            try {
                enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            } catch (_: Exception) {}
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.systemBars,
        bottomBar = {
            MissionBottomNav(
                currentDestination = currentDestination,
                onDestinationSelected = { currentDestination = it },
                alertCount = alertCount
            )
        },
        containerColor = colors.background
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Permission banner if microphone is ungranted
                if (!hasMicPermission) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 8.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(colors.surface)
                            .border(1.dp, colors.outline, RoundedCornerShape(16.dp))
                            .padding(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                SoftIcon(
                                    resId = com.itantra.app.R.drawable.ic_soft_mic,
                                    contentDescription = null,
                                    tint = colors.accent,
                                    modifier = Modifier.size(19.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = "Microphone access needed",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = colors.textPrimary
                                    )
                                    Text(
                                        text = "Enables hands-free emergency voice",
                                        fontSize = 12.sp,
                                        color = colors.textSecondary
                                    )
                                }
                            }

                            Button(
                                onClick = { permissionLauncher.launch(requiredPermissions) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = colors.accent,
                                    contentColor = colors.onAccent
                                ),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Grant", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }

                // Bluetooth Disabled Banner
                if (!isBluetoothEnabled) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 4.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(colors.surface)
                            .border(1.dp, colors.error.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
                            .padding(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                SoftIcon(
                                    resId = com.itantra.app.R.drawable.ic_soft_bluetooth,
                                    contentDescription = null,
                                    tint = colors.error,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = "Bluetooth is off",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = colors.textPrimary
                                    )
                                    Text(
                                        text = "Needed to broadcast SOS and find nearby devices",
                                        fontSize = 12.sp,
                                        color = colors.textSecondary
                                    )
                                }
                            }

                            Button(
                                onClick = {
                                    try {
                                        enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                                    } catch (_: Exception) {}
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = colors.error,
                                    contentColor = colors.onAccent
                                ),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Turn On", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }

                // Location Services Disabled Banner (Android BLE requirement)
                if (!isLocationEnabled) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 4.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(colors.surface)
                            .border(1.dp, colors.rescue.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
                            .padding(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                SoftIcon(
                                    resId = com.itantra.app.R.drawable.ic_soft_pin,
                                    contentDescription = null,
                                    tint = colors.rescue,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = "Location is off",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = colors.textPrimary
                                    )
                                    Text(
                                        text = "Android needs location on to discover nearby devices",
                                        fontSize = 12.sp,
                                        color = colors.textSecondary
                                    )
                                }
                            }

                            Button(
                                onClick = {
                                    try {
                                        enableLocLauncher.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                                    } catch (_: Exception) {}
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = colors.rescue,
                                    contentColor = colors.textPrimary
                                ),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Turn On", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }

                // Primary 4-Mode Router
                when (currentDestination) {
                    MissionDestination.SOS -> {
                        SosDistressScreen(viewModel = viewModel)
                    }

                    MissionDestination.WALKIE -> {
                        WalkieScreen(viewModel = viewModel)
                    }

                    MissionDestination.RESCUE -> {
                        RescueScreen(viewModel = viewModel)
                    }

                    MissionDestination.SETTINGS -> {
                        SettingsScreen(viewModel = viewModel)
                    }
                }
            }
        }
    }
}
