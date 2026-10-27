package com.adaptive.simplifier

import org.locationtech.jts.geom.{Coordinate, Envelope}

import scala.collection.mutable

/**
  * Box-counting estimate of the fractal dimension of a polyline section coords(from..to).
  *
  * The section is covered by square grids with cell size eps = L / 2^j, where L is the longest
  * side of the section's bounding box (so horizontal/vertical sections are handled). For each grid,
  * N(eps) = number of cells touched by the segments. Segments are sampled every eps/2, so N is a
  * close approximation, not an exact cell traversal. D = least-squares slope of log N vs log(1/eps).
  *
  * Scales finer than half the mean segment length are excluded: below the digitization resolution
  * every polyline consists of straight pieces and the slope collapses towards 1.
  *
  * Returns None (unreliable) when the section has too few segments, too few usable scales,
  * zero extent, or a poor log-log fit. The result is clamped to [1, 2].
  */
private[simplifier] object BoxCounting {

  def dimension(coords: Array[Coordinate], from: Int, to: Int, cfg: AdaptiveConfig): Option[Double] = {
    val segments = to - from
    if (segments < cfg.fractalMinSegments) return None

    val env = new Envelope()
    var k = from
    while (k <= to) { env.expandToInclude(coords(k)); k += 1 }
    val side = math.max(env.getWidth, env.getHeight)
    if (!finite(side) || !(side > 0.0)) return None

    var length = 0.0
    k = from
    while (k < to) {
      val d = coords(k).distance(coords(k + 1))
      if (!finite(d)) return None
      length += d
      if (!finite(length)) return None
      k += 1
    }
    val minEps = (length / segments) / 2.0
    if (!finite(minEps)) return None

    val xs = mutable.ArrayBuffer.empty[Double]
    val ys = mutable.ArrayBuffer.empty[Double]
    var j = 1
    while (j <= cfg.fractalMaxScales && side / (1 << j) >= minEps) {
      val cells = 1 << j
      val eps = side / cells
      if (!finite(eps) || !(eps > 0.0)) return None
      val n = countBoxes(coords, from, to, env.getMinX, env.getMinY, eps, cells)
      if (n <= 0) return None
      xs += -math.log(eps)
      ys += math.log(n.toDouble)
      j += 1
    }
    if (xs.length < cfg.fractalMinScales) return None

    fit(xs, ys).collect {
      case (slope, r2) if finite(slope) && finite(r2) && r2 >= cfg.fractalMinR2 =>
        math.max(1.0, math.min(2.0, slope))
    }
  }

  private def countBoxes(
      coords: Array[Coordinate], from: Int, to: Int,
      minX: Double, minY: Double, eps: Double, cells: Int
  ): Int = {
    val seen = mutable.HashSet.empty[Long]
    val step = eps / 2.0
    var k = from
    while (k < to) {
      val a = coords(k)
      val b = coords(k + 1)
      val samples = math.max(1, math.ceil(a.distance(b) / step).toInt)
      var s = 0
      while (s <= samples) {
        val t = s.toDouble / samples
        val x = a.x + (b.x - a.x) * t
        val y = a.y + (b.y - a.y) * t
        val ix = math.max(0, math.min(cells - 1, ((x - minX) / eps).toInt))
        val iy = math.max(0, math.min(cells - 1, ((y - minY) / eps).toInt))
        seen += (ix.toLong << 32) | (iy.toLong & 0xffffffffL)
        s += 1
      }
      k += 1
    }
    seen.size
  }

  /** Ordinary least squares; returns (slope, R^2), or None for a degenerate fit. */
  private def fit(xs: collection.Seq[Double], ys: collection.Seq[Double]): Option[(Double, Double)] = {
    val n = xs.length
    val mx = xs.sum / n
    val my = ys.sum / n
    var sxx = 0.0
    var sxy = 0.0
    var syy = 0.0
    var i = 0
    while (i < n) {
      val dx = xs(i) - mx
      val dy = ys(i) - my
      sxx += dx * dx; sxy += dx * dy; syy += dy * dy
      i += 1
    }
    if (!finite(sxx) || !finite(sxy) || !finite(syy) || sxx <= 0.0 || syy <= 0.0) None
    else Some((sxy / sxx, (sxy * sxy) / (sxx * syy)))
  }

  private def finite(v: Double): Boolean = !v.isNaN && !v.isInfinite
}
