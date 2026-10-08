package app.parkedvideo.car

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session

class ParkedVideoSession : Session() {

    override fun onCreateScreen(intent: Intent): Screen {
        val renderer = SurfaceRenderer(carContext, lifecycle)
        val guard = ParkingGuard(carContext, lifecycle)
        return BrowserScreen(carContext, renderer, guard)
    }
}
