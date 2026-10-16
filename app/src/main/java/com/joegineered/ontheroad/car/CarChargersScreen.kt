package com.joegineered.ontheroad.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.lifecycleScope
import com.joegineered.ontheroad.AppGraph
import com.joegineered.ontheroad.R
import com.joegineered.ontheroad.cameras.LatLng
import com.joegineered.ontheroad.chargers.ChargingStation
import com.joegineered.ontheroad.nav.Destination
import com.joegineered.ontheroad.ui.formatTripDistance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import uniffi.ferrostar.GeographicCoordinate

/** The nearest EV chargers, fast (DC) only by default; picking one starts navigation. */
class CarChargersScreen(carContext: CarContext) : Screen(carContext) {
  private val viewModel = AppGraph.navigationViewModel
  private var fastOnly = true
  private var results: List<Pair<ChargingStation, Double>>? = null
  private var failed = false

  private val maxItems =
      carContext
          .getCarService(ConstraintManager::class.java)
          .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)

  init {
    load()
  }

  private fun load() {
    results = null
    failed = false
    val here = viewModel.location.value?.coordinates
    lifecycleScope.launch {
      if (here == null) {
        failed = true
      } else {
        val plugTypes = AppGraph.settings.chargerPlugTypes.first()
        runCatching { AppGraph.chargers.nearest(LatLng(here.lat, here.lng), fastOnly, plugTypes) }
            .onSuccess { results = it }
            .onFailure { failed = true }
      }
      invalidate()
    }
  }

  override fun onGetTemplate(): Template {
    val filter =
        Action.Builder()
            .setTitle(carContext.getString(if (fastOnly) R.string.fast_only else R.string.ev_chargers))
            .setOnClickListener {
              fastOnly = !fastOnly
              load()
              invalidate()
            }
            .build()
    val builder =
        ListTemplate.Builder()
            .setTitle(carContext.getString(R.string.chargers_nearby))
            .setHeaderAction(Action.BACK)
            .setActionStrip(ActionStrip.Builder().addAction(filter).build())

    val list = results
    if (list == null && !failed) return builder.setLoading(true).build()

    val icon =
        CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_bolt))
            .setTint(CarColor.GREEN)
            .build()
    val items =
        ItemList.Builder()
            .setNoItemsMessage(
                carContext.getString(if (failed) R.string.chargers_failed else R.string.no_chargers_found)
            )
            .apply {
          list.orEmpty().take(maxItems).forEach { (station, distance) ->
            addItem(
                Row.Builder()
                    .setTitle(station.title ?: carContext.getString(R.string.charger_default_title))
                    .addText(listOfNotNull(station.headline, formatTripDistance(distance)).joinToString(" · "))
                    .setImage(icon)
                    .setOnClickListener { navigateTo(station) }
                    .build()
            )
          }
        }
    return builder.setSingleList(items.build()).build()
  }

  private fun navigateTo(station: ChargingStation) {
    viewModel.startNavigation(
        Destination(
            GeographicCoordinate(station.position.lat, station.position.lng),
            label = station.title,
            subtitle = station.headline,
        )
    )
    screenManager.pop()
  }
}
