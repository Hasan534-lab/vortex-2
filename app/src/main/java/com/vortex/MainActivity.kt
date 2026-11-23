package com.vortex

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vortex.net.Link
import com.vortex.ui.C
import com.vortex.ui.HomeScreen
import com.vortex.ui.ProjectScreen
import com.vortex.ui.ProjectsViewModel

class MainActivity : ComponentActivity() {
    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { startEngine() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Screen stays on while the app is open (live event use).
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Full screen: status and navigation bars hidden so the short landscape screen is fully used.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideBars()
        Link.init(applicationContext)

        val needed = ArrayList<String>()
        if (Link.isOperator && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isEmpty()) startEngine() else permLauncher.launch(needed.toTypedArray())

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.fillMaxSize().background(C.Bg).windowInsetsPadding(WindowInsets.displayCutout)) { AppRoot() }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideBars()
    }

    private fun hideBars() {
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.hide(WindowInsetsCompat.Type.systemBars())
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun startEngine() {
        if (Link.isOperator) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                ContextCompat.startForegroundService(this, Intent(this, EngineService::class.java))
            } else {
                Link.status.value = "Microphone permission is needed for the spectrum"
                Link.startOperator() // the Wi-Fi link still works without audio
            }
        } else {
            Link.startController()
        }
    }
}

@Composable
private fun AppRoot(vm: ProjectsViewModel = viewModel()) {
    var openId by rememberSaveable { mutableStateOf<Long?>(null) }
    var setupMode by rememberSaveable { mutableStateOf(false) }

    val id = openId
    if (id != null) {
        ProjectScreen(id, setupMode) { openId = null; setupMode = false }
    } else {
        HomeScreen(
            vm,
            onOpen = { vm.touch(it); setupMode = false; openId = it },
            onInitialSetup = { vm.touch(it); setupMode = true; openId = it },
        )
    }
}
