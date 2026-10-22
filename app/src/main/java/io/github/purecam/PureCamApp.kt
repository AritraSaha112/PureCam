package io.github.purecam

import android.app.Application
import io.github.purecam.camera.CameraEngine

class PureCamApp : Application() {
    val engine: CameraEngine by lazy { CameraEngine(this) }
}
