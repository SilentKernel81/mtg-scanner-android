package com.cardscanner

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.cardscanner.detection.CardTracker
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.cardscanner.ui.ScannerTheme
import com.cardscanner.ui.ScannerScreen
import com.cardscanner.ui.SettingsScreen
import com.cardscanner.ui.InventoryBrowser
import com.cardscanner.ui.ReviewScreen
import com.cardscanner.ui.PhotoImportScreen
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.opencv.android.OpenCVLoader
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.cardscanner.scryfall.Scryfall
import okhttp3.OkHttpClient

/**
 * The one activity: asks for the camera, then shows the scanner screen and, from it, Settings,
 * the inventory and the review queue (plain state, no navigation library). Also where the image
 * loader for Scryfall's card images gets the User-Agent Scryfall requires.
 */
class MainActivity : ComponentActivity() {
    private val viewModel: ScannerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(OpenCVLoader.initLocal()) { "OpenCV failed to load" }
        // The phone hangs above the box while scanning: keep the screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        keepWatchingForIdle()
        setContent {
            // Scryfall's image CDN rejects OkHttp's default User-Agent (HTTP 400)
            setSingletonImageLoaderFactory { context ->
                ImageLoader.Builder(context).components {
                    add(OkHttpNetworkFetcherFactory(callFactory = {
                        OkHttpClient.Builder().addInterceptor { chain ->
                            chain.proceed(chain.request().newBuilder().header("User-Agent", Scryfall.USER_AGENT).build())
                        }.build()
                    }))
                }.build()
            }
            ScannerTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { App(viewModel) }
            }
        }
    }

    // ------------------------------------------------------------------------
    // Dim the screen while nothing is going on (the phone hangs above the box; the display is the
    // biggest drain). A touch or a card being dropped brings the brightness back.
    // ------------------------------------------------------------------------

    private var lastActivity = SystemClock.elapsedRealtime()
    private var dimmed = false

    private fun noteActivity() {
        lastActivity = SystemClock.elapsedRealtime()
        if (dimmed) setDimmed(false)
    }

    private fun setDimmed(dim: Boolean) {
        dimmed = dim
        window.attributes = window.attributes.apply {
            screenBrightness = if (dim) DIM_BRIGHTNESS else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        noteActivity()
        return super.dispatchTouchEvent(ev)
    }

    private fun keepWatchingForIdle() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                noteActivity()
                launch {
                    viewModel.detection.collect {
                        if (it != null && it.status != CardTracker.Status.NO_CARD && it.status != CardTracker.Status.CAPTURED) noteActivity()
                    }
                }
                launch {
                    while (true) {
                        delay(1000)
                        if (!dimmed && SystemClock.elapsedRealtime() - lastActivity > DIM_AFTER_MS) setDimmed(true)
                    }
                }
            }
        }
    }

    private companion object {
        const val DIM_AFTER_MS = 60_000L
        const val DIM_BRIGHTNESS = 0.01f
    }
}

@Composable
private fun App(viewModel: ScannerViewModel) {
    val context = LocalContext.current
    var hasCamera by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasCamera = it }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showInventory by rememberSaveable { mutableStateOf(false) }
    var showReview by rememberSaveable { mutableStateOf(false) }
    // An export is ready: open the share sheet
    val share by viewModel.share.collectAsStateWithLifecycle()
    LaunchedEffect(share) {
        share?.let { context.startActivity(it); viewModel.shareHandled() }
    }

    // A photo of several cards (binder page, cards on a table): picked here, checked on its own screen
    val photoImport by viewModel.photoImport.collectAsStateWithLifecycle()
    val photoBusy by viewModel.photoBusy.collectAsStateWithLifecycle()
    var showPhoto by rememberSaveable { mutableStateOf(false) }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) { showPhoto = true; viewModel.startPhotoImport(uri) }
    }
    // (the import is over - cancelled, done or the photo could not be opened)
    LaunchedEffect(photoImport, photoBusy) { if (photoImport == null && photoBusy == null) showPhoto = false }

    when {
        showPhoto -> PhotoImportScreen(viewModel, onClose = { showPhoto = false })
        showSettings -> {
            BackHandler { showSettings = false }
            SettingsScreen(viewModel, onBack = { showSettings = false })
        }
        showReview -> {
            BackHandler { showReview = false }
            ReviewScreen(viewModel, onClose = { showReview = false })
        }
        hasCamera -> ScannerScreen(viewModel, onSettings = { showSettings = true }, onInventory = { showInventory = true },
            onReview = { showReview = true },
            onImportPhoto = { pickPhoto.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
        else -> Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("The scanner needs the camera to find cards.", style = MaterialTheme.typography.bodyLarge)
            Button(onClick = { askCamera.launch(Manifest.permission.CAMERA) }, Modifier.padding(top = 16.dp)) {
                Text("Allow camera")
            }
        }
    }
    // Keep ScannerScreen composed and its camera bound while browsing live scan progress.
    if (showInventory) InventoryBrowser(viewModel, onClose = { showInventory = false })
}
