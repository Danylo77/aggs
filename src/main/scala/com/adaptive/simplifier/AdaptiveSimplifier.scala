package com.adaptive.simplifier

import com.adaptive.simplifier.model._
import org.locationtech.jts.geom.Geometry

/**
  * Adaptive simplification of LINESTRING, MULTILINESTRING, POLYGON and MULTIPOLYGON geometries.
  *
  * Local tolerances vary along each line/ring according to a complexity score built from
  * box-counting fractal estimates and local geometric features (turning angle, local deviation,
  * relative vertex density). Distances are in input coordinate units; no CRS is assumed.
  *
  * {{{
  * val simplifiedWkt = AdaptiveSimplifier.simplify(
  *   wkt = "LINESTRING (0 0, 1 0.2, 2 0, 3 1)",
  *   targetReductionPercent = 40.0
  * )
  * }}}
  */
object AdaptiveSimplifier {

  private val SupportedTypes = Set("LineString", "MultiLineString", "Polygon", "MultiPolygon")

  /**
    * Simplifies a geometry.
    *
    * @param wkt                    input geometry as WKT
    * @param targetReductionPercent approximate percentage of vertices to remove, in [0, 100].
    *                               This is a target, not a guarantee: validity and structure take priority.
    * @return simplified geometry as 2D WKT. Empty input is returned as empty WKT. A target of 0 returns
    *         the input unchanged. If no valid candidate is found, the input geometry is returned unchanged.
    * @throws InvalidReductionPercentException  NaN, infinite, or out-of-range percentage
    * @throws InvalidWktException               null, blank, or malformed WKT
    * @throws UnsupportedGeometryTypeException  POINT, MULTIPOINT, GEOMETRYCOLLECTION, LINEARRING
    * @throws InvalidGeometryException          input that is not valid according to JTS (it is not repaired)
    */
  def simplify(wkt: String, targetReductionPercent: Double): String =
    simplifyWithReport(wkt, targetReductionPercent).wkt

  /** Same as [[simplify]], plus diagnostics. Optional; not needed for normal use. */
  def simplifyWithReport(wkt: String, targetReductionPercent: Double): SimplificationReport = {
    if (targetReductionPercent.isNaN || targetReductionPercent.isInfinite ||
        targetReductionPercent < 0.0 || targetReductionPercent > 100.0)
      throw new InvalidReductionPercentException(
        s"targetReductionPercent must be a finite number in [0, 100], got $targetReductionPercent")

    val geometry = WktIO.parse(wkt)
    if (!SupportedTypes.contains(geometry.getGeometryType))
      throw new UnsupportedGeometryTypeException(
        s"Unsupported geometry type ${geometry.getGeometryType}; supported: LINESTRING, MULTILINESTRING, POLYGON, MULTIPOLYGON")

    if (geometry.isEmpty) return unchanged(geometry, targetReductionPercent)

    GeometryValidator.numericValidationError(geometry).foreach { error =>
      throw new InvalidGeometryException(s"Input geometry is outside the supported numeric domain ($error)")
    }

    GeometryValidator.validationError(geometry).foreach { error =>
      throw new InvalidGeometryException(s"Input geometry is invalid ($error); it is not repaired automatically")
    }

    if (targetReductionPercent == 0.0) return unchanged(geometry, targetReductionPercent)

    val outcome = AdaptiveEngine.run(geometry, targetReductionPercent, AdaptiveConfig.Default)
    val original = geometry.getNumPoints
    val result = outcome.geometry.getNumPoints
    SimplificationReport(
      wkt = WktIO.write(outcome.geometry),
      originalVertexCount = original,
      resultVertexCount = result,
      targetReductionPercent = targetReductionPercent,
      achievedReductionPercent = if (original > 0) 100.0 * (original - result) / original else 0.0,
      threshold = outcome.threshold,
      attempts = outcome.attempts,
      fallbackToOriginal = outcome.fallbackToOriginal,
      fractalCoverage = outcome.fractalCoverage,
      rejectedCandidates = outcome.rejected
    )
  }

  private def unchanged(geometry: Geometry, target: Double): SimplificationReport = {
    val n = geometry.getNumPoints
    SimplificationReport(WktIO.write(geometry), n, n, target, 0.0, Double.NegativeInfinity, 0,
      fallbackToOriginal = false, fractalCoverage = 0.0, rejectedCandidates = Nil)
  }
}
