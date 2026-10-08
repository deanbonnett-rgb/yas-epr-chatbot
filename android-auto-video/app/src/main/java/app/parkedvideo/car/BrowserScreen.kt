package app.parkedvideo.car

import androidx.annotation.DrawableRes
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.graphics.drawable.IconCompat
import app.parkedvideo.R

/**
 * Main car screen. The web page fills the "map" surface behind this template;
 * the template only adds the buttons along the edges.
 */
class BrowserScreen(
    carContext: CarContext,
    private val renderer: SurfaceRenderer,
    guard: ParkingGuard,
) : Screen(carContext) {

    init {
        guard.onStateChanged = { state ->
            when (state) {
                ParkingGuard.State.PARKED -> renderer.setBlocked(false, "")
                ParkingGuard.State.MOVING -> renderer.setBlocked(true, MOVING_MESSAGE)
                ParkingGuard.State.UNKNOWN -> renderer.setBlocked(true, UNKNOWN_MESSAGE)
            }
        }
    }

    override fun onGetTemplate(): Template {
        val actionStrip = ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setTitle("Sites")
                    .setOnClickListener { screenManager.push(SitesScreen(carContext, renderer)) }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setTitle("Search")
                    .setOnClickListener { screenManager.push(AddressScreen(carContext, renderer)) }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setTitle("Back")
                    .setOnClickListener { renderer.goBack() }
                    .build()
            )
            .addAction(iconAction(R.drawable.ic_play_pause) { renderer.togglePlayPause() })
            .build()

        // PAN lets the driver scroll the page by dragging on touch screens and
        // gives rotary/touchpad cars a way to move around too.
        val mapActionStrip = ActionStrip.Builder()
            .addAction(Action.PAN)
            .addAction(iconAction(R.drawable.ic_scroll_up) { renderer.scrollPage(-1) })
            .addAction(iconAction(R.drawable.ic_scroll_down) { renderer.scrollPage(1) })
            .build()

        return NavigationTemplate.Builder()
            .setActionStrip(actionStrip)
            .setMapActionStrip(mapActionStrip)
            .build()
    }

    private fun iconAction(@DrawableRes icon: Int, onClick: () -> Unit): Action =
        Action.Builder()
            .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, icon)).build())
            .setOnClickListener(onClick)
            .build()

    private companion object {
        const val MOVING_MESSAGE = "Video is paused while the car is moving.\nPark to keep watching."
        const val UNKNOWN_MESSAGE = "Checking that the car is parked…\n\n" +
            "If this doesn't go away, open Parked Video on your phone\nand allow the speed and location permissions."
    }
}
