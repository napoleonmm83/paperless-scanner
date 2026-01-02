package com.paperless.scanner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.navigation.compose.rememberNavController
import com.paperless.scanner.data.datastore.TokenManager
import com.paperless.scanner.ui.navigation.PaperlessNavGraph
import com.paperless.scanner.ui.navigation.Screen
import com.paperless.scanner.ui.theme.PaperlessScannerTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var tokenManager: TokenManager

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Permission result handled silently */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermission()

        val sharedUris = handleShareIntent(intent)

        setContent {
            PaperlessScannerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val token by tokenManager.token.collectAsState(initial = null)
                    val navController = rememberNavController()

                    val startDestination = if (token.isNullOrBlank()) {
                        Screen.Welcome.route
                    } else {
                        Screen.Home.route
                    }

                    PaperlessNavGraph(
                        navController = navController,
                        startDestination = startDestination,
                        sharedUris = sharedUris
                    )
                }
            }
        }
    }

    private fun handleShareIntent(intent: Intent?): List<Uri> {
        if (intent == null) return emptyList()

        return when (intent.action) {
            Intent.ACTION_SEND -> {
                // Single image or PDF
                if (intent.type?.startsWith("image/") == true ||
                    intent.type == "application/pdf"
                ) {
                    @Suppress("DEPRECATION")
                    val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    } else {
                        intent.getParcelableExtra(Intent.EXTRA_STREAM)
                    }
                    listOfNotNull(uri)
                } else {
                    emptyList()
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                // Multiple images
                if (intent.type?.startsWith("image/") == true) {
                    @Suppress("DEPRECATION")
                    val uris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    } else {
                        intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                    }
                    uris?.filterNotNull() ?: emptyList()
                } else {
                    emptyList()
                }
            }
            else -> emptyList()
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> {
                    // Permission already granted
                }
                else -> {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }
}
