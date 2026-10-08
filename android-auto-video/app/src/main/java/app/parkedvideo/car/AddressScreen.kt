package app.parkedvideo.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import app.parkedvideo.Sites

/** Keyboard entry: a web address loads directly, anything else searches YouTube. */
class AddressScreen(carContext: CarContext, private val renderer: SurfaceRenderer) : Screen(carContext) {

    override fun onGetTemplate(): Template =
        SearchTemplate.Builder(object : SearchTemplate.SearchCallback {
            override fun onSearchSubmitted(searchText: String) {
                if (searchText.isBlank()) return
                renderer.loadUrl(Sites.urlFor(searchText))
                screenManager.popToRoot()
            }
        })
            .setHeaderAction(Action.BACK)
            .setSearchHint("Web address or YouTube search")
            .setShowKeyboardByDefault(true)
            .setItemList(ItemList.Builder().setNoItemsMessage("Type, then press search").build())
            .build()
}
