package com.example.vkapp

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

class MainActivity : ComponentActivity() {
    private lateinit var castContext: CastContext
    private lateinit var sessionManager: SessionManager
    private lateinit var mediaRouter: MediaRouter
    private lateinit var mediaRouteSelector: MediaRouteSelector
    private val videoUrl =
        "https://videolink-test.mycdn.me/?pct=1&sig=6QNOvp0y3BE&ct=0&clientType=45&mid=193241622673&type=5" // ваша ссылка не показывает видео ))) когда будете проверять замените ссылку на норм работающую
    private var castState by mutableStateOf(CastState.IDLE)
    private var availableRoutes by mutableStateOf(listOf<MediaRouter.RouteInfo>())

    private val sessionManagerListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) {
            castState = CastState.CONNECTING
            loadMedia()
        }

        override fun onSessionEnded(session: CastSession, error: Int) {
            castState = CastState.IDLE
            startCasting()
        }

        override fun onSessionStarting(p0: CastSession) {}

        override fun onSessionStartFailed(p0: CastSession, p1: Int) {
            castState = CastState.ERROR
            Thread {
                Thread.sleep(1000)
                runOnUiThread { startCasting() }
            }.start()
        }

        override fun onSessionEnding(p0: CastSession) {
        }

        override fun onSessionResumed(p0: CastSession, p1: Boolean) {
            loadMedia()
        }

        override fun onSessionResumeFailed(p0: CastSession, p1: Int) {
            castState = CastState.ERROR
        }

        override fun onSessionSuspended(p0: CastSession, p1: Int) {}

        override fun onSessionResuming(p0: CastSession, p1: String) {}
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

        if (castState == CastState.IDLE || castState == CastState.ERROR) {
            castState = CastState.CONNECTING
            val currentSession = sessionManager.currentCastSession
            if (currentSession != null && currentSession.isConnected) {
                loadMedia()
            } else {
                val routes = mediaRouter.routes

                val castRoutes = routes.filter { route ->
                    val supportsCast = route.supportsControlCategory(
                        CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
                    )

                    supportsCast
                }
                if (castRoutes.isNotEmpty()) {

                    availableRoutes = castRoutes
                    castState = CastState.IDLE
                } else {
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
        val currentSession = sessionManager.currentCastSession
        if (currentSession == null || !currentSession.isConnected) {
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

            remoteMediaClient.load(mediaInfo, true, 0).setResultCallback { result ->
                if (result.status.isSuccess) {

                    castState = CastState.CASTING
                } else {

                    castState = CastState.ERROR
                    startCasting()
                }
            }
        } else {

            castState = CastState.ERROR
            startCasting()
        }
    }

    private val mediaRouterCallback = object : MediaRouter.Callback() {

        override fun onRouteSelected(router: MediaRouter, route: MediaRouter.RouteInfo) {}


        override fun onRouteUnselected(router: MediaRouter, route: MediaRouter.RouteInfo) {
            castState = CastState.IDLE
            startCasting()
        }
    }
}

@Composable
fun CastAppScreen(
    castState: CastState,
    startCasting: () -> Unit,
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