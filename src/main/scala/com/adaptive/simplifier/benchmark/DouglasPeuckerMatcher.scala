package com.adaptive.simplifier.benchmark

import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.simplify.DouglasPeuckerSimplifier

import scala.collection.mutable

/**
  * Bounded, deterministic search for the JTS Douglas–Peucker tolerance closest to a target reduction.
  * JTS polygon output counts may reverse as tolerance grows because valid-area reconstruction is not
  * monotonic. The search therefore does not discard half-intervals based on monotonicity: it combines
  * linear and logarithmic probes over the complete configured range, then refines around the best
  * evaluated candidate. "Closest" means closest among the bounded set of evaluated tolerances.
  */
private[benchmark] object DouglasPeuckerMatcher {
  final case class Match(geometry: Geometry, tolerance: Double, actualReductionPercent: Double, evaluations: Int)

  def search(original: Geometry, targetReductionPercent: Double, config: BenchmarkConfig): Match = {
    val count = original.getNumPoints
    val maxTolerance = config.dpMaxTolerance.getOrElse {
      val e = original.getEnvelopeInternal
      val diagonal = math.hypot(e.getWidth, e.getHeight)
      if (diagonal.isFinite && diagonal > 0.0) diagonal else 1.0
    }
    val cache = mutable.LinkedHashMap.empty[Double, Match]

    def evaluate(tolerance: Double): Match = cache.getOrElseUpdate(tolerance, {
      val result = DouglasPeuckerSimplifier.simplify(original, tolerance)
      val reduction = if (count == 0) 0.0 else 100.0 * (count - result.getNumPoints) / count
      Match(result, tolerance, reduction, 0)
    })
    def difference(m: Match): Double = math.abs(m.actualReductionPercent - targetReductionPercent)
    def best: Match = cache.values.minBy(m => (difference(m), m.tolerance))

    evaluate(0.0)
    evaluate(maxTolerance)
    if (maxTolerance > 0.0) {
      val guidedBudget = config.dpMaxIterations / 3
      val globalBudget = config.dpMaxIterations / 3
      val refinementBudget = config.dpMaxIterations - guidedBudget - globalBudget

      // Guided bisection efficiently finds ordinary monotonic transitions, but every candidate is
      // retained globally; the other phases prevent a reversal from permanently discarding a region.
      var low = 0.0
      var high = maxTolerance
      var i = 0
      while (i < guidedBudget && high - low > config.dpToleranceEpsilon * math.max(1.0, maxTolerance)) {
        val mid = low + (high - low) / 2.0
        val candidate = evaluate(mid)
        if (candidate.actualReductionPercent < targetReductionPercent) low = mid else high = mid
        i += 1
      }

      // Probe the full range using alternating linear and logarithmic spacing.
      val linearCount = globalBudget / 2
      val logCount = globalBudget - linearCount
      i = 1
      while (i <= linearCount) {
        evaluate(maxTolerance * i / (linearCount + 1.0))
        i += 1
      }
      val minRatio = math.max(config.dpToleranceEpsilon, 1e-15)
      i = 1
      while (i <= logCount) {
        val fraction = i.toDouble / (logCount + 1.0)
        evaluate(maxTolerance * math.pow(minRatio, 1.0 - fraction))
        i += 1
      }

      // Refine both neighboring intervals of the current global best without assuming direction.
      var refinement = 0
      while (refinement < refinementBudget) {
        val sorted = cache.keys.toVector.sorted
        val bestIndex = sorted.indexOf(best.tolerance)
        val intervals = Seq(bestIndex - 1 -> bestIndex, bestIndex -> (bestIndex + 1)).collect {
          case (a, b) if a >= 0 && b < sorted.length => sorted(a) -> sorted(b)
        }.filter { case (a, b) => b - a > config.dpToleranceEpsilon * math.max(1.0, maxTolerance) }
        if (intervals.isEmpty) refinement = refinementBudget
        else {
          val (a, b) = intervals.maxBy { case (left, right) => right - left }
          evaluate(a + (b - a) / 2.0)
          refinement += 1
        }
      }
    }

    val selected = best
    selected.copy(evaluations = cache.size)
  }
}
