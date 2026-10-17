package com.joegineered.ontheroad.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.lifecycleScope
import com.joegineered.ontheroad.AppGraph
import com.joegineered.ontheroad.R
import com.joegineered.ontheroad.stops.StopCategory
import com.joegineered.ontheroad.stops.StopSearch
import com.joegineered.ontheroad.ui.iconRes
import com.joegineered.ontheroad.ui.labelRes
import com.joegineered.ontheroad.ui.stopSummary
import com.joegineered.ontheroad.ui.stopTitle
import com.joegineered.ontheroad.ui.toDestination
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Stops on the way, for Android Auto: the kinds of place to look for, then (with [category]) the
 * places of that kind ahead on the route. Picking one drives there, then on to the destination.
 */
class CarStopsScreen(carContext: CarContext, private val category: StopCategory? = null) :
    Screen(carContext) {
  private val stops = AppGraph.roadsideStops

  private val maxItems =
      carContext
          .getCarService(ConstraintManager::class.java)
          .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)

  init {
    if (category != null) {
      stops.search(category)
      stops.state.onEach { invalidate() }.launchIn(lifecycleScope)
    }
  }

  override fun onGetTemplate(): Template {
    val builder = ListTemplate.Builder().setHeaderAction(Action.BACK)
    if (category == null) {
      val kinds =
          ItemList.Builder()
              .apply {
                StopCategory.entries.forEach { kind ->
                  addItem(
                      Row.Builder()
                          .setTitle(carContext.getString(kind.labelRes()))
                          .setImage(icon(kind))
                          .setBrowsable(true)
                          .setOnClickListener { screenManager.push(CarStopsScreen(carContext, kind)) }
                          .build()
                  )
                }
              }
              .build()
      return builder.setTitle(carContext.getString(R.string.stops_on_the_way)).setSingleList(kinds).build()
    }

    builder.setTitle(carContext.getString(category.labelRes()))
    val items = ItemList.Builder()
    when (val search = stops.state.value) {
      is StopSearch.Results -> {
        if (search.category != category) return builder.setLoading(true).build()
        items.setNoItemsMessage(carContext.getString(R.string.stops_none))
        search.stops.take(maxItems).forEach { stop ->
          items.addItem(
              Row.Builder()
                  .setTitle(stopTitle(carContext, stop))
                  .addText(stopSummary(carContext, stop))
                  .setImage(icon(category))
                  .setOnClickListener {
                    stops.clear()
                    AppGraph.navigationViewModel.stopOnTheWay(stop.toDestination(carContext))
                    screenManager.popToRoot()
                  }
                  .build()
          )
        }
      }
      is StopSearch.Failed ->
          items.setNoItemsMessage(
              carContext.getString(if (search.offline) R.string.stops_offline else R.string.stops_failed)
          )
      // Idle: the trip ended while searching.
      StopSearch.Idle -> items.setNoItemsMessage(carContext.getString(R.string.stops_none))
      is StopSearch.Loading -> return builder.setLoading(true).build()
    }
    return builder.setSingleList(items.build()).build()
  }

  private fun icon(kind: StopCategory) =
      CarIcon.Builder(IconCompat.createWithResource(carContext, kind.iconRes())).build()
}
