package com.touristsafety.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.touristsafety.app.navigation.AppNavigation
import com.touristsafety.app.ui.components.CrashReportDialog
import com.touristsafety.app.ui.theme.TouristSafetyTheme

/**
 * The single Activity.
 *
 * Deliberately thin: it hands the Compose tree the application's [AppContainer] and then gets out of
 * the way. All safety-critical state lives in the container and the ViewModels, so a configuration
 * change — or the process being recreated — cannot lose the tracking pipeline.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val container = (application as TouristSafetyApp).container

        setContent {
            TouristSafetyTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        AppNavigation(container = container)
                        // Sits above everything, and only appears when the previous run crashed.
                        CrashReportDialog()
                    }
                }
            }
        }
    }
}
