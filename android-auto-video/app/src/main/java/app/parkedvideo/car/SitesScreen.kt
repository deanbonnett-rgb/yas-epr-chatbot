package app.parkedvideo.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import app.parkedvideo.Sites

/** List of video sites, plus an entry for typing an address or search. */
class SitesScreen(carContext: CarContext, private val renderer: SurfaceRenderer) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val items = ItemList.Builder()
        items.addItem(
            Row.Builder()
                .setTitle("Type an address or search…")
                .setOnClickListener { screenManager.push(AddressScreen(carContext, renderer)) }
                .build()
        )
        for (site in Sites.ALL) {
            items.addItem(
                Row.Builder()
                    .setTitle(site.name)
                    .addText(site.url)
                    .setOnClickListener {
                        renderer.loadUrl(site.url)
                        screenManager.pop()
                    }
                    .build()
            )
        }
        return ListTemplate.Builder()
            .setTitle("Sites")
            .setHeaderAction(Action.BACK)
            .setSingleList(items.build())
            .build()
    }
}
