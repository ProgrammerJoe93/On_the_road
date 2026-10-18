package com.joegineered.ontheroad.car

import android.icu.util.MeasureUnit
import androidx.car.app.model.Distance
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.navigation.model.TravelEstimate
import com.joegineered.ontheroad.config.AppConfig
import com.stadiamaps.ferrostar.ui.formatters.LocalizedDistanceFormatter

/**
 * A distance for car templates, in the units setting. Ferrostar's car templates take their units
 * from the device locale instead, so an en-GB phone shows yards and miles even when the app is set
 * to (or, on Auto, chooses) metric.
 */
fun carDistance(metres: Double, metric: Boolean = AppConfig.usesMetricUnits): Distance {
  val formatter =
      LocalizedDistanceFormatter(distanceMeasurementSystemOverride = AppConfig.distanceMeasurementSystem(metric))
  val unit =
      when (formatter.recommendedUnit(metres)) {
        MeasureUnit.MILE -> Distance.UNIT_MILES
        MeasureUnit.YARD -> Distance.UNIT_YARDS
        MeasureUnit.FOOT -> Distance.UNIT_FEET
        MeasureUnit.KILOMETER -> Distance.UNIT_KILOMETERS
        else -> Distance.UNIT_METERS
      }
  return Distance.create(formatter.roundedDistanceForUnit(metres), unit)
}

/** This estimate with its distance replaced, e.g. by [carDistance]. */
fun TravelEstimate.withDistance(distance: Distance): TravelEstimate {
  val arrival = arrivalTimeAtDestination ?: return this
  return TravelEstimate.Builder(distance, arrival)
      .setRemainingTimeSeconds(remainingTimeSeconds)
      .apply {
        remainingTimeColor?.let(::setRemainingTimeColor)
        remainingDistanceColor?.let(::setRemainingDistanceColor)
      }
      .build()
}

/** This routing info with the distance to its current step replaced, e.g. by [carDistance]. */
fun RoutingInfo.withDistance(distance: Distance): RoutingInfo {
  val step = currentStep ?: return this
  return RoutingInfo.Builder()
      .setCurrentStep(step, distance)
      .apply {
        nextStep?.let(::setNextStep)
        junctionImage?.let(::setJunctionImage)
      }
      .build()
}
