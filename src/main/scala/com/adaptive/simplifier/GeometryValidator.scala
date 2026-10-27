package com.adaptive.simplifier

import org.locationtech.jts.geom.{Geometry, Polygon}
import org.locationtech.jts.operation.valid.IsValidOp

/** Validity and structure checks for input and candidate geometries. No automatic repair is performed. */
private[simplifier] object GeometryValidator {

  /**
    * Numeric-domain error, or None when the coordinate range is safe for the distance
    * calculations used by JTS and this implementation. Extreme but finite ranges are rejected
    * rather than allowed to turn distances, scores, or tolerances into NaN/Infinity.
    */
  def numericValidationError(geometry: Geometry): Option[String] = {
    val coords = geometry.getCoordinates
    if (coords.exists(c => !finite(c.x) || !finite(c.y)))
      Some("coordinates must have finite X and Y ordinates")
    else {
      val env = geometry.getEnvelopeInternal
      val width = env.getWidth
      val height = env.getHeight
      val span = math.max(width, height)
      val maxSafeSpan = math.sqrt(Double.MaxValue / 2.0)
      val minSafeSpan = math.sqrt(Double.MinPositiveValue)
      if (!finite(width) || !finite(height) || !finite(span))
        Some("coordinate extent is too large for finite distance calculations")
      else if (span > maxSafeSpan)
        Some(s"coordinate extent $span exceeds the numerically safe limit $maxSafeSpan")
      else if (span > 0.0 && span < minSafeSpan)
        Some(s"coordinate extent $span is too small for stable squared-distance calculations")
      else None
    }
  }

  /** JTS validity error description, or None if the geometry is valid. */
  def validationError(geometry: Geometry): Option[String] =
    Option(new IsValidOp(geometry).getValidationError).map(_.toString)

  /**
    * Reason to reject a candidate, or None if acceptable. A candidate must keep the geometry type,
    * component count, and hole count, must be JTS-valid, and, if the lineal input was simple,
    * must stay simple (no new self-intersections).
    */
  def rejectionReason(candidate: Geometry, original: Geometry, requireSimple: Boolean): Option[String] =
    if (candidate.getGeometryType != original.getGeometryType) Some("geometry type changed")
    else if (candidate.getNumGeometries != original.getNumGeometries) Some("component count changed")
    else if (holeCount(candidate) != holeCount(original)) Some("hole count changed")
    else
      validationError(candidate)
        .map(e => s"invalid result: $e")
        .orElse(if (requireSimple && !candidate.isSimple) Some("introduced a self-intersection in a simple lineal input") else None)

  private def holeCount(g: Geometry): Int =
    (0 until g.getNumGeometries).map(g.getGeometryN).collect { case p: Polygon => p.getNumInteriorRing }.sum

  private def finite(v: Double): Boolean = !v.isNaN && !v.isInfinite
}
