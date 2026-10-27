package com.adaptive.simplifier

import com.adaptive.simplifier.model.PartComplexity
import org.locationtech.jts.algorithm.{Angle, Distance}
import org.locationtech.jts.geom.Coordinate

import scala.collection.mutable

/**
  * Per-vertex complexity of one part (line or ring), combining dimensionless features in [0, 1]:
  *
  *  - turning angle: (pi - interior angle) / pi; 0 for straight, 1 for a hairpin. 0 at open-line endpoints.
  *  - local deviation: 2 * maxDist / pathLength over the window [i-h, i+h], where maxDist is the largest
  *    distance of window vertices from the chord p(i-h)p(i+h). It is scale-invariant and at most 1.
  *  - relative density: r / (1 + r) with r = partMeanSegment / localMeanSegment (0.5 = average density).
  *  - fractal: D - 1 from box-counting over overlapping windows covering the vertex (averaged).
  *
  * score = weighted mean of the available features. Where box-counting is unreliable (short parts,
  * too few scales, poor fit), the fractal term is omitted and the remaining weights are renormalized.
  * This is the documented fallback to purely geometric features.
  *
  * Rings are treated cyclically (closing point excluded, then copied). Input must have no consecutive
  * duplicate points.
  */
private[simplifier] object ComplexityAnalyzer {

  def analyze(coords: Array[Coordinate], closed: Boolean, cfg: AdaptiveConfig): PartComplexity = {
    val n = coords.length
    val m = if (closed) n - 1 else n // distinct vertices
    val scores = Array.fill(n)(0.5)
    if (m < 3) return PartComplexity(scores, 0, math.max(m, 0))

    def at(i: Int): Coordinate =
      if (closed) coords(Math.floorMod(i, m)) else coords(math.max(0, math.min(m - 1, i)))

    val segCount = if (closed) m else m - 1
    var meanSeg = 0.0
    var j = 0
    while (j < segCount) {
      val d = coords(j).distance(coords(j + 1))
      meanSeg += (d - meanSeg) / (j + 1)
      j += 1
    }

    // On small rings the window must not wrap onto itself.
    val h = if (closed) math.max(1, math.min(cfg.featureHalfWindow, (m - 1) / 2)) else cfg.featureHalfWindow

    val (fractalSum, fractalCount) = fractalAccumulation(coords, closed, cfg)
    var covered = 0

    var i = 0
    while (i < m) {
      val angle =
        if (!closed && (i == 0 || i == m - 1)) 0.0
        else (math.Pi - Angle.angleBetween(at(i - 1), at(i), at(i + 1))) / math.Pi

      val lo = if (closed) i - h else math.max(0, i - h)
      val hi = if (closed) i + h else math.min(m - 1, i + h)
      val segs = hi - lo
      var path = 0.0
      var k = lo
      while (k < hi) { path += at(k).distance(at(k + 1)); k += 1 }

      var maxDist = 0.0
      k = lo + 1
      while (k < hi) {
        maxDist = math.max(maxDist, Distance.pointToSegment(at(k), at(lo), at(hi)))
        k += 1
      }
      val deviation = if (segs >= 2 && path > 0.0) clamp01(2.0 * maxDist / path) else 0.0

      val localMean = path / segs
      val density = if (localMean > 0.0) { val r = meanSeg / localMean; r / (1.0 + r) } else 0.5

      var num = cfg.weightAngle * clamp01(angle) + cfg.weightDeviation * deviation + cfg.weightDensity * density
      var den = cfg.weightAngle + cfg.weightDeviation + cfg.weightDensity
      if (fractalCount(i) > 0) {
        num += cfg.weightFractal * clamp01(fractalSum(i) / fractalCount(i))
        den += cfg.weightFractal
        covered += 1
      }
      scores(i) = num / den
      i += 1
    }
    if (closed) scores(m) = scores(0)
    PartComplexity(scores, covered, m)
  }

  /** Accumulates (D - 1) from overlapping box-counting windows onto the vertices they cover. */
  private def fractalAccumulation(
      coords: Array[Coordinate], closed: Boolean, cfg: AdaptiveConfig
  ): (Array[Double], Array[Int]) = {
    val n = coords.length
    val sum = new Array[Double](n)
    val cnt = new Array[Int](n)
    val segments = n - 1
    val w = 2 * cfg.fractalHalfWindow
    if (segments < cfg.fractalMinSegments) return (sum, cnt)

    if (closed && segments > w) {
      // Analyse cyclic windows so a ring's serialization seam is not a special low-coverage region.
      val extended = Array.tabulate(segments + w + 1)(i => coords(i % segments))
      var start = 0
      while (start < segments) {
        BoxCounting.dimension(extended, start, start + w, cfg).foreach { d =>
          var k = start
          while (k <= start + w) {
            val index = k % segments
            sum(index) += d - 1.0
            cnt(index) += 1
            k += 1
          }
        }
        start += cfg.fractalHalfWindow
      }
      sum(segments) = sum(0)
      cnt(segments) = cnt(0)
    } else {
      val windows = mutable.ArrayBuffer.empty[(Int, Int)]
      if (segments <= w) windows += ((0, segments))
      else {
        var s = 0
        while (s + w < segments) { windows += ((s, s + w)); s += cfg.fractalHalfWindow }
        windows += ((segments - w, segments))
      }
      windows.foreach { case (a, b) =>
        BoxCounting.dimension(coords, a, b, cfg).foreach { d =>
          var k = a
          while (k <= b) { sum(k) += d - 1.0; cnt(k) += 1; k += 1 }
        }
      }
    }
    (sum, cnt)
  }

  private def clamp01(v: Double): Double =
    if (!v.isFinite) 0.0 else math.max(0.0, math.min(1.0, v))
}
