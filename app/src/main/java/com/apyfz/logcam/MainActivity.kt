package com.apyfz.logcam

import android.Manifest
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.viewModels
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.apyfz.logcam.ui.CameraScreen
import com.apyfz.logcam.ui.LogCamTheme
import com.apyfz.logcam.ui.CameraViewModel

class MainActivity : ComponentActivity() {
    private val cameraViewModel: CameraViewModel by viewModels()

    override fun onStart() {
        super.onStart()
        cameraViewModel.resumePreview()
    }

    override fun onStop() {
        cameraViewModel.pausePreview()
        super.onStop()
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // A recording that stops because the screen locked is a lost take.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Diagnostic sweep, triggered by a marker file. Runs instead of the camera UI because it
        // needs exclusive use of the camera.
        val sweepMarker = java.io.File(getExternalFilesDir(null), "sweep.run")
        if (sweepMarker.isFile) {
            sweepMarker.delete()
            Thread {
                com.apyfz.logcam.camera.DiagnosticSweep(this)
                    .run(java.io.File(getExternalFilesDir(null), "sweep.txt"))
            }.start()
            // The sweep needs the camera to itself, so the viewfinder must not also open it.
            setContent {
                LogCamTheme {
                    Text("Running diagnostic sweep...", color = Color.White)
                }
            }
            return
        }

        if (savedInstanceState == null &&
            applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 &&
            intent.getBooleanExtra("comparison", false)
        ) {
            cameraViewModel.prepareComparisonCapture(
                iso = intent.getIntExtra("comparison_iso", 100),
            )
        }

        setContent {
            LogCamTheme {
                var granted by remember { mutableStateOf(hasPermissions()) }

                val request = androidx.activity.compose.rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions(),
                ) { result -> granted = result.values.all { it } }

                if (granted) {
                    CameraScreen(viewModel = cameraViewModel)
                } else {
                    PermissionPrompt { request.launch(REQUIRED) }
                }
            }
        }
    }

    private fun hasPermissions() = REQUIRED.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private val REQUIRED = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    }
}

@androidx.compose.runtime.Composable
private fun PermissionPrompt(onRequest: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Color.Black).padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("LogCam needs the camera and microphone.", color = Color.White)
        Button(onClick = onRequest) { Text("Grant") }
    }
}
