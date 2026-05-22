package app.vimusic.android.service

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import app.vimusic.android.R
import app.vimusic.android.utils.intent
import app.vimusic.core.data.utils.CallValidator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel

@OptIn(UnstableApi::class)
class PlayerMediaLibraryService : MediaLibraryService(), ServiceConnection {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var bound = false
    private var binder: PlayerService.Binder? = null
    private var session: MediaLibraryService.MediaLibrarySession? = null

    private val callValidator by lazy {
        CallValidator(applicationContext, R.xml.allowed_media_browser_callers)
    }

    override fun onCreate() {
        super.onCreate()
        // Start this service explicitly so Android does not destroy it when Auto unbinds.
        // Without startService, Auto unbinding causes immediate destruction which Auto
        // detects and instantly rebinds -- a tight create/destroy loop at ~28 cycles/sec
        // that triggers the "this app has a bug" ANR notification.
        startService(intent<PlayerMediaLibraryService>())
        startService(intent<PlayerService>())
        bindService(intent<PlayerService>(), this, Context.BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        // Don't release the session -- it is owned by PlayerService, not us.
        session = null
        if (bound) {
            bound = false
            unbindService(this)
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    // Must return immediately -- any blocking here causes an ANR.
    // Returns null until PlayerService binds and sets the session.
    // uid == -1 is Media3's internal legacy controller probe; allow it through.
    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo
    ): MediaLibraryService.MediaLibrarySession? {
        if (controllerInfo.uid != -1 &&
            !callValidator.canCall(controllerInfo.packageName, controllerInfo.uid)) return null
        return session
    }

    override fun onServiceConnected(className: ComponentName, service: IBinder) {
        val binder = service as? PlayerService.Binder ?: return
        this.binder = binder
        bound = true
        // Session is owned and created by PlayerService -- just hold a reference to it.
        session = binder.mediaSession
    }

    override fun onServiceDisconnected(name: ComponentName) {
        bound = false
        binder = null
        // Don't touch the session -- it stays alive in PlayerService.
        // Auto stays connected; PlayerService will restart and rebind automatically.
    }
}