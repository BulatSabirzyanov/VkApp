package com.example.vkapp

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManager
import com.google.android.gms.cast.framework.SessionManagerListener
import java.io.BufferedReader
import java.io.InputStreamReader

class MainActivity : ComponentActivity() {
    private lateinit var castContext: CastContext
    private lateinit var sessionManager: SessionManager
    private lateinit var mediaRouter: MediaRouter
    private lateinit var mediaRouteSelector: MediaRouteSelector
    private val videoUrl = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"
    private var castState by mutableStateOf(CastState.IDLE)
    private var availableRoutes by mutableStateOf(listOf<MediaRouter.RouteInfo>())

    private val sessionManagerListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) {
            Log.d("CastApp", "Session started: $sessionId")
            castState = CastState.CONNECTING
            loadMedia()
        }

        override fun onSessionEnded(session: CastSession, error: Int) {
            Log.d("CastApp", "Session ended with error: $error")
            castState = CastState.IDLE
            startCasting()
        }

        override fun onSessionStarting(p0: CastSession) {
            Log.d("CastApp", "Session starting")
        }

        override fun onSessionStartFailed(p0: CastSession, p1: Int) {
            Log.e("CastApp", "Session start failed: $p1")
            castState = CastState.ERROR
            Thread {
                Thread.sleep(1000)
                runOnUiThread { startCasting() }
            }.start()
        }

        override fun onSessionEnding(p0: CastSession) {
            Log.d("CastApp", "Session ending")
        }

        override fun onSessionResumed(p0: CastSession, p1: Boolean) {
            Log.d("CastApp", "Session resumed")
            loadMedia()
        }

        override fun onSessionResumeFailed(p0: CastSession, p1: Int) {
            Log.e("CastApp", "Session resume failed: $p1")
            castState = CastState.ERROR
        }

        override fun onSessionSuspended(p0: CastSession, p1: Int) {
            Log.d("CastApp", "Session suspended: $p1")
        }

        override fun onSessionResuming(p0: CastSession, p1: String) {
            Log.d("CastApp", "Session resuming: $p1")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            castContext = CastContext.getSharedInstance(this)
            sessionManager = castContext.sessionManager
            mediaRouter = MediaRouter.getInstance(this)
            mediaRouteSelector = MediaRouteSelector.Builder()
                .addControlCategory(CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID))
                .build()
            Log.d("CastApp", "Initialization successful")
        } catch (e: Exception) {
            Log.e("CastApp", "Initialization failed: ${e.message}")
            castState = CastState.ERROR
            e.printStackTrace()
            return
        }

        setContent {
            CastAppScreen(
                castState = castState,
                startCasting = ::startCasting,
                sendLogs = ::sendLogsViaEmail,
                availableRoutes = availableRoutes,
                onRouteSelected = { route ->
                    Log.d("CastApp", "User selected route: ${route.name}")
                    mediaRouter.selectRoute(route)
                }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        sessionManager.addSessionManagerListener(sessionManagerListener, CastSession::class.java)
        mediaRouter.addCallback(mediaRouteSelector, mediaRouterCallback)
        Log.d("CastApp", "onResume: Added session listener and media router callback")
        startCasting()
    }

    override fun onPause() {
        super.onPause()
        sessionManager.removeSessionManagerListener(sessionManagerListener, CastSession::class.java)
        mediaRouter.removeCallback(mediaRouterCallback)
        Log.d("CastApp", "onPause: Removed session listener and media router callback")
    }

    private fun startCasting() {
        Log.d("CastApp", "startCasting called, current state: $castState")
        if (castState == CastState.IDLE || castState == CastState.ERROR) {
            castState = CastState.CONNECTING
            val currentSession = sessionManager.currentCastSession
            if (currentSession != null && currentSession.isConnected) {
                Log.d("CastApp", "Using existing session")
                loadMedia()
            } else {
                Log.d("CastApp", "Looking for Cast devices")
                val routes = mediaRouter.routes
                Log.d("CastApp", "Found ${routes.size} routes")
                val castRoutes = routes.filter { route ->
                    val supportsCast = route.supportsControlCategory(
                        CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
                    )
                    Log.d("CastApp", "Route: ${route.name}, supports Cast: $supportsCast")
                    supportsCast
                }
                if (castRoutes.isNotEmpty()) {
                    Log.d("CastApp", "Cast devices found: ${castRoutes.map { it.name }}")
                    availableRoutes = castRoutes
                    castState = CastState.IDLE // Ждем выбора пользователя
                } else {
                    Log.e("CastApp", "No Cast devices found")
                    castState = CastState.ERROR
                    Thread {
                        Thread.sleep(1000)
                        runOnUiThread { startCasting() }
                    }.start()
                }
            }
        }
    }

    private fun loadMedia() {
        Log.d("CastApp", "loadMedia called")
        val currentSession = sessionManager.currentCastSession
        if (currentSession == null || !currentSession.isConnected) {
            Log.e("CastApp", "No active session available")
            castState = CastState.ERROR
            startCasting()
            return
        }

        val remoteMediaClient = currentSession.remoteMediaClient
        if (remoteMediaClient != null) {
            val mediaInfo = MediaInfo.Builder(videoUrl)
                .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                .setContentType("video/mp4")
                .setMetadata(MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE))
                .build()

            Log.d("CastApp", "Loading media on remote client")
            remoteMediaClient.load(mediaInfo, true, 0).setResultCallback { result ->
                if (result.status.isSuccess) {
                    Log.d("CastApp", "Media loaded successfully")
                    castState = CastState.CASTING
                } else {
                    Log.e("CastApp", "Media load failed: ${result.status}")
                    castState = CastState.ERROR
                    startCasting()
                }
            }
        } else {
            Log.e("CastApp", "RemoteMediaClient is null")
            castState = CastState.ERROR
            startCasting()
        }
    }

    private val mediaRouterCallback = object : MediaRouter.Callback() {
        @Deprecated("Deprecated in Java", ReplaceWith(
            "Log.d(\"CastApp\", \"Route selected: \${route.name}\")",
            "android.util.Log"
        )
        )
        override fun onRouteSelected(router: MediaRouter, route: MediaRouter.RouteInfo) {
            Log.d("CastApp", "Route selected: ${route.name}")
            // Не вызываем loadMedia() здесь, ждем onSessionStarted
        }

        @Deprecated("Deprecated in Java")
        override fun onRouteUnselected(router: MediaRouter, route: MediaRouter.RouteInfo) {
            Log.d("CastApp", "Route unselected: ${route.name}")
            castState = CastState.IDLE
            startCasting()
        }
    }

    private fun sendLogsViaEmail() {
        try {
            val process = Runtime.getRuntime().exec("logcat -d -v time CastApp:D *:S")
            val bufferedReader = BufferedReader(InputStreamReader(process.inputStream))
            val logs = StringBuilder()
            var line: String?
            while (bufferedReader.readLine().also { line = it } != null) {
                logs.append(line).append("\n")
            }
            bufferedReader.close()

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_EMAIL, arrayOf("sabirzyanov427@gmail.com"))
                putExtra(Intent.EXTRA_SUBJECT, "CastApp Logs - ${System.currentTimeMillis()}")
                putExtra(Intent.EXTRA_TEXT, logs.toString())
            }
            startActivity(Intent.createChooser(intent, "Send logs via email"))
        } catch (e: Exception) {
            Log.e("CastApp", "Failed to send logs: ${e.message}")
            e.printStackTrace()
        }
    }
}

@Composable
fun CastAppScreen(
    castState: CastState,
    startCasting: () -> Unit,
    sendLogs: () -> Unit,
    availableRoutes: List<MediaRouter.RouteInfo>,
    onRouteSelected: (MediaRouter.RouteInfo) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }

    MaterialTheme {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Button(
                onClick = {
                    if (availableRoutes.isNotEmpty()) {
                        showDialog = true
                    } else {
                        startCasting()
                    }
                },
                enabled = castState == CastState.IDLE || castState == CastState.ERROR
            ) {
                Text(
                    when (castState) {
                        CastState.IDLE -> "Cast Video"
                        CastState.CONNECTING -> "Connecting..."
                        CastState.CASTING -> "Casting..."
                        CastState.ERROR -> "Error - Retry"
                    }
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = sendLogs) {
                Text("Send Logs")
            }
        }

        if (showDialog && availableRoutes.isNotEmpty()) {
            AlertDialog(
                onDismissRequest = { showDialog = false },
                title = { Text("Select Cast Device") },
                text = {
                    LazyColumn {
                        items(availableRoutes) { route ->
                            TextButton(
                                onClick = {
                                    onRouteSelected(route)
                                    showDialog = false
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(route.name)
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { showDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}