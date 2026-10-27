package com.adaptive.simplifier

import com.adaptive.simplifier.model.PartComplexity
import org.locationtech.jts.algorithm.Distance
import org.locationtech.jts.geom.{Coordinate, CoordinateArrays, Geometry, Lineal}

import scala.collection.mutable

/**
  * Orchestrates per-part analysis, global threshold selection, bounded validity retries, and fallback.
  *
  * Reduction to threshold mapping: every part gets per-vertex significances (VariableToleranceDP).
  * All removable vertices of the whole geometry are ranked by significance. Legal removal counts
  * end at complete equal-significance groups; the count nearest R = round(N * p / 100), after
  * accounting for consecutive duplicates, is selected. Removing a complete group is exactly
  * variable-tolerance DP at t = that group's significance and preserves the local error bound.
  * Mandatory vertices (anchors) cannot be removed, so high targets may be capped.
  *
  * Retries: if a candidate fails validation, removal snaps to a strictly smaller complete group
  * near R * backoff^k. The final bounded attempt uses zero ranked removals. This lowers t and every
  * local tolerance t * m_i. If even that cleaned candidate fails, the original is returned unchanged.
  */
private[simplifier] object AdaptiveEngine {

  final case class PreparedPart(
      coords: Array[Coordinate],
      significance: Array[Double],
      depth: Array[Int],
      complexity: Option[PartComplexity]
  )

  final case class Outcome(
      geometry: Geometry,
      threshold: Double,
      attempts: Int,
      fallbackToOriginal: Boolean,
      fractalCoverage: Double,
      rejected: Vector[String]
  )

  /** Removable vertex reference, packed as (part index, vertex index). */
  private final case class Ref(part: Int, vertex: Int, sig: Double, depth: Int)

  def run(geometry: Geometry, targetPercent: Double, cfg: AdaptiveConfig): Outcome = {
    require(cfg.maxAttempts > 0, "maxAttempts must be positive")
    require(cfg.retryBackoff > 0.0 && cfg.retryBackoff < 1.0,
      "retryBackoff must be between 0 and 1")

    val originalCount = geometry.getNumPoints
    val parts = GeometryParts.extract(geometry).map(prepare(_, cfg))

    val order: Array[Ref] = {
      val b = mutable.ArrayBuffer.empty[Ref]
      parts.iterator.zipWithIndex.foreach { case (p, pi) =>
        var i = 0
        while (i < p.coords.length) {
          if (!p.significance(i).isPosInfinity) b += Ref(pi, i, p.significance(i), p.depth(i))
          i += 1
        }
      }
      // Final index keys make the result reproducible for otherwise equal entries.
      b.toArray.sortWith { (x, y) =>
        if (x.sig != y.sig) x.sig < y.sig
        else if (x.depth != y.depth) x.depth > y.depth
        else if (x.part != y.part) x.part < y.part
        else x.vertex < y.vertex
      }
    }
    val duplicates = originalCount - parts.map(_.coords.length).sum
    val requested = math.round(originalCount * targetPercent / 100.0).toInt
    val wanted = math.max(0, math.min(order.length, requested - duplicates))

    // Only complete equal-significance groups are legal removal counts. Cutting a group could retain
    // a DP split while dropping one of its descendants and would invalidate the documented t*m_i bound.
    val levels = mutable.ArrayBuffer(0)
    var i = 0
    while (i < order.length) {
      val sig = order(i).sig
      i += 1
      while (i < order.length && order(i).sig == sig) i += 1
      levels += i
    }
    val initialRemoval = levels.minBy(r => (math.abs(r - wanted), r))

    val analysed = parts.flatMap(_.complexity)
    val distinct = analysed.map(_.distinctVertices).sum
    val coverage = if (distinct > 0) analysed.map(_.fractalCoveredVertices).sum.toDouble / distinct else 0.0

    val requireSimple = geometry.isInstanceOf[Lineal] && geometry.isSimple
    val rejected = Vector.newBuilder[String]
    var attempts = 0
    var removal = initialRemoval
    while (attempts < cfg.maxAttempts) {
      attempts += 1
      val threshold = if (removal == 0) Double.NegativeInfinity else order(removal - 1).sig
      val verdict = build(geometry, parts, order, removal).flatMap { candidate =>
        GeometryValidator.rejectionReason(candidate, geometry, requireSimple).toLeft(candidate)
      }
      verdict match {
        case Right(candidate) =>
          return Outcome(candidate, threshold, attempts, fallbackToOriginal = false, coverage, rejected.result())
        case Left(reason) =>
          rejected += s"attempt $attempts (removing $removal vertices, threshold $threshold): $reason"
      }
      if (removal == 0) attempts = cfg.maxAttempts
      else {
        // The last bounded attempt is always the least aggressive cleaned candidate. Earlier retries
        // snap the geometric backoff target down to a strictly smaller complete threshold group.
        val desired =
          if (attempts == cfg.maxAttempts - 1) 0
          else math.floor(wanted * math.pow(cfg.retryBackoff, attempts)).toInt
        removal = levels.filter(r => r < removal && r <= desired).lastOption.getOrElse(0)
      }
    }
    Outcome(geometry, Double.NegativeInfinity, attempts, fallbackToOriginal = true, coverage, rejected.result())
  }

  private def prepare(raw: RawPart, cfg: AdaptiveConfig): PreparedPart = {
    val cleaned = CoordinateArrays.removeRepeatedPoints(raw.coords)
    val closed = cleaned.length >= 2 && cleaned(0).equals2D(cleaned(cleaned.length - 1))
    // Canonicalizing the seam makes equivalent cyclic rotations of a ring produce the same
    // feature windows, anchors, and simplification result.
    val coords = if (closed) canonicalizeClosed(cleaned) else cleaned
    val n = coords.length
    // Minimal parts are kept as-is: 2-point lines, triangles, and degenerate closed lines.
    val minimal = if (closed) n <= 4 else n <= 2
    if (minimal) PreparedPart(coords, Array.fill(n)(Double.PositiveInfinity), new Array[Int](n), None)
    else {
      val complexity = ComplexityAnalyzer.analyze(coords, closed, cfg)
      val multipliers = ToleranceModel.multipliers(complexity.scores, closed, cfg)
      val anchors = if (closed) closedAnchors(coords) else Array(0, n - 1)
      val ranking = VariableToleranceDP.rank(coords, multipliers, anchors)
      PreparedPart(coords, ranking.significance, ranking.depth, Some(complexity))
    }
  }

  private def canonicalizeClosed(coords: Array[Coordinate]): Array[Coordinate] = {
    val m = coords.length - 1
    if (m <= 1) return coords
    var start = 0
    var i = 1
    while (i < m) {
      val c = coords(i)
      val best = coords(start)
      if (c.x < best.x || (c.x == best.x && c.y < best.y)) start = i
      i += 1
    }
    if (start == 0) coords
    else Array.tabulate(coords.length)(i => coords((start + i) % m))
  }

  /**
    * Anchors for closed parts (rings, closed lines): the canonical start vertex, the vertex farthest from it,
    * and the vertex farthest from the segment between those two. This keeps at least a triangle
    * (4 coordinates with closure), so rings never collapse.
    */
  private def closedAnchors(coords: Array[Coordinate]): Array[Int] = {
    val m = coords.length - 1
    val p0 = coords(0)
    var a1 = 1
    var best = -1.0
    var i = 1
    while (i < m) { val d = coords(i).distance(p0); if (d > best) { best = d; a1 = i }; i += 1 }
    var a2 = -1
    best = -1.0
    i = 1
    while (i < m) {
      if (i != a1) {
        val d = Distance.pointToSegment(coords(i), p0, coords(a1))
        if (d > best) { best = d; a2 = i }
      }
      i += 1
    }
    Array(0, math.min(a1, a2), math.max(a1, a2), m)
  }

  private def build(
      template: Geometry,
      parts: Vector[PreparedPart],
      order: Array[Ref],
      removal: Int
  ): Either[String, Geometry] = {
    val removed = parts.map(p => new Array[Boolean](p.coords.length))
    var r = 0
    while (r < removal) { val ref = order(r); removed(ref.part)(ref.vertex) = true; r += 1 }

    val arrays = parts.iterator.zip(removed.iterator).map { case (p, drop) =>
      val b = mutable.ArrayBuffer.empty[Coordinate]
      var i = 0
      while (i < p.coords.length) {
        if (!drop(i)) b += p.coords(i).copy()
        i += 1
      }
      b.toArray
    }
    try Right(GeometryParts.rebuild(template, arrays))
    catch { case e: IllegalArgumentException => Left(s"construction failed: ${e.getMessage}") }
  }
}
