package com.adaptive.simplifier

/**
  * Internal tuning parameters. All defaults are heuristic starting values,
  * not empirically calibrated (calibration belongs to the experiment stage).
  *
  * @param featureHalfWindow  half-width (in vertices) of the window used for deviation and density
  * @param fractalHalfWindow  box-counting windows span 2*H segments with stride H (50% overlap)
  * @param fractalMinSegments minimum segments in a window for a box-counting estimate
  * @param fractalMinScales   minimum number of grid scales for the log-log fit
  * @param fractalMaxScales   grids use cell sizes L/2^1 .. L/2^max
  * @param fractalMinR2       minimum R^2 of the log-log fit; worse fits are treated as unreliable
  * @param weightAngle        weight of the turning-angle feature
  * @param weightDeviation    weight of the local-deviation feature
  * @param weightFractal      weight of the box-counting feature (dropped when unavailable)
  * @param weightDensity      weight of the relative vertex-density feature
  * @param toleranceSpread    multipliers range from 1/spread (complexity 1) to spread (complexity 0)
  * @param smoothingRadius    radius (in vertices) of the moving average applied to log multipliers
  * @param maxAttempts        maximum number of candidates built (initial + retries)
  * @param retryBackoff       each retry removes backoff^k of the originally requested removable vertices
  */
private[simplifier] final case class AdaptiveConfig(
    featureHalfWindow: Int = 2,
    fractalHalfWindow: Int = 8,
    fractalMinSegments: Int = 8,
    fractalMinScales: Int = 3,
    fractalMaxScales: Int = 6,
    fractalMinR2: Double = 0.8,
    weightAngle: Double = 0.35,
    weightDeviation: Double = 0.30,
    weightFractal: Double = 0.25,
    weightDensity: Double = 0.10,
    toleranceSpread: Double = 4.0,
    smoothingRadius: Int = 2,
    maxAttempts: Int = 8,
    retryBackoff: Double = 0.7
) {
  require(featureHalfWindow >= 1, "featureHalfWindow must be positive")
  require(fractalHalfWindow >= 1, "fractalHalfWindow must be positive")
  require(fractalMinSegments >= 2, "fractalMinSegments must be at least 2")
  require(fractalMinScales >= 2, "fractalMinScales must be at least 2")
  require(fractalMaxScales >= fractalMinScales && fractalMaxScales <= 30,
    "fractalMaxScales must be between fractalMinScales and 30")
  require(fractalMinR2.isFinite && fractalMinR2 >= 0.0 && fractalMinR2 <= 1.0,
    "fractalMinR2 must be finite and in [0, 1]")

  private val weights = Seq(weightAngle, weightDeviation, weightFractal, weightDensity)
  require(weights.forall(w => w.isFinite && w >= 0.0), "complexity weights must be finite and non-negative")
  require(weightAngle + weightDeviation + weightDensity > 0.0,
    "at least one geometric fallback weight must be positive")
  require(toleranceSpread.isFinite && toleranceSpread >= 1.0,
    "toleranceSpread must be finite and at least 1")
  require(smoothingRadius >= 0, "smoothingRadius must be non-negative")
  require(maxAttempts >= 2, "maxAttempts must be at least 2 so the final safe candidate can be attempted")
  require(retryBackoff.isFinite && retryBackoff > 0.0 && retryBackoff < 1.0,
    "retryBackoff must be finite and between 0 and 1")
}

private[simplifier] object AdaptiveConfig {
  val Default: AdaptiveConfig = AdaptiveConfig()
}
