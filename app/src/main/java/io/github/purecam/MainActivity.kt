package io.github.purecam

import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.OrientationEventListener
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.purecam.camera.CameraEngine
import io.github.purecam.ui.PureCamRoot
import io.github.purecam.ui.PureTheme

class MainActivity : ComponentActivity() {
    private val engine: CameraEngine get() = (application as PureCamApp).engine
    private lateinit var orientationListener: OrientationEventListener

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.statusBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        preferHighestRefreshRate()
        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(o: Int) {
                if (o != ORIENTATION_UNKNOWN) engine.setDeviceOrientation(o)
            }
        }
        setContent { PureTheme { PureCamRoot(engine) } }
    }

    /** The S21 FE screen runs at up to 120 Hz; ask for it so the viewfinder feels smooth. */
    private fun preferHighestRefreshRate() {
        if (Build.VERSION.SDK_INT < 30) return
        val d = display ?: return
        val cur = d.mode
        val best = d.supportedModes
            .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
            .maxByOrNull { it.refreshRate } ?: return
        window.attributes = window.attributes.apply { preferredDisplayModeId = best.modeId }
    }

    override fun onResume() {
        super.onResume()
        if (orientationListener.canDetectOrientation()) orientationListener.enable()
        engine.onResume()
    }

    override fun onPause() {
        orientationListener.disable()
        engine.onPause()
        super.onPause()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (engine.ui.volumeShutter && (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_CAMERA)) {
            if (event?.repeatCount == 0) engine.shutter()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
