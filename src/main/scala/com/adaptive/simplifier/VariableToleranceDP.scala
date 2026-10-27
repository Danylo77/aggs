package com.adaptive.simplifier

import org.locationtech.jts.algorithm.Distance
import org.locationtech.jts.geom.Coordinate

import scala.collection.mutable

/**
  * Douglas–Peucker with a per-vertex tolerance.
  *
  * For a span (a, b), the split vertex is the one maximizing the normalized distance
  * r_i = dist(p_i, segment p_a p_b) / m_i. At threshold t, the span is split iff max r_i > t,
  * which means every vertex dropped from the span lies within its own local tolerance t * m_i
  * of the output segment p_a p_b.
  *
  * The split choice does not depend on t, so the recursion tree is fixed and the kept sets are
  * nested in t. A single full pass therefore gives each vertex a significance
  *   sig_i = min(r over the vertex and all its ancestor splits),
  * and vertex i survives threshold t iff sig_i > t. Anchors (always kept) get +Infinity.
  * `depth` is the split depth in the tree; it breaks significance ties so that a child is never
  * kept while its parent is removed.
  *
  * The implementation is iterative (no recursion depth limit). Worst case O(n^2), typically O(n log n).
  */
private[simplifier] object VariableToleranceDP {

  final case class Ranking(significance: Array[Double], depth: Array[Int])

  /** @param anchors sorted indices that are always kept; must include 0 and coords.length - 1 */
  def rank(coords: Array[Coordinate], multipliers: Array[Double], anchors: Array[Int]): Ranking = {
    require(coords != null && coords.length >= 2, "coords must contain at least two coordinates")
    require(multipliers != null && multipliers.length == coords.length,
      "multipliers must have the same length as coords")
    require(coords.forall(c => c != null && c.x.isFinite && c.y.isFinite),
      "coordinates must have finite X and Y ordinates")
    require(multipliers.forall(m => m.isFinite && m > 0.0),
      "multipliers must be finite and positive")
    require(anchors != null && anchors.length >= 2 && anchors.head == 0 && anchors.last == coords.length - 1,
      "anchors must include the first and last coordinate")
    require(anchors.sliding(2).forall(a => a(0) < a(1)),
      "anchors must be strictly increasing")

    val sig = new Array[Double](coords.length)
    val depth = new Array[Int](coords.length)
    anchors.foreach(a => sig(a) = Double.PositiveInfinity)

    val stack = mutable.ArrayDeque.empty[(Int, Int, Double, Int)]
    var k = 0
    while (k < anchors.length - 1) { stack.append((anchors(k), anchors(k + 1), Double.PositiveInfinity, 0)); k += 1 }

    while (stack.nonEmpty) {
      val (a, b, parent, d) = stack.removeLast()
      if (b - a >= 2) {
        var best = a + 1
        var bestR = -1.0
        var i = a + 1
        while (i < b) {
          val r = Distance.pointToSegment(coords(i), coords(a), coords(b)) / multipliers(i)
          if (r > bestR) { bestR = r; best = i }
          i += 1
        }
        val s = math.min(parent, bestR)
        sig(best) = s
        depth(best) = d + 1
        stack.append((a, best, s, d + 1))
        stack.append((best, b, s, d + 1))
      }
    }
    Ranking(sig, depth)
  }
}
