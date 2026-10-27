package com.adaptive.simplifier.benchmark

import org.locationtech.jts.algorithm.Distance
import org.locationtech.jts.algorithm.distance.DiscreteHausdorffDistance
import org.locationtech.jts.geom._
import org.locationtech.jts.operation.distance.DistanceOp

import scala.collection.mutable
import scala.util.control.NonFatal

/** Reusable, side-effect-free metric and structural diagnostics. */
object GeometryMetricsCalculator {

  /**
    * Measures `output` against `original`.
    *
    * - Hausdorff: symmetric discrete Hausdorff distance, densified by the configured fraction;
    *   polygonal geometries use boundaries so interior containment does not hide boundary displacement.
    * - Mean deviation: arithmetic mean of nearest-boundary distances in both directions. Each line/ring
    *   contributes its first vertex and `meanSamplesPerSegment` evenly spaced samples per segment.
    * - Maximum removed-vertex deviation: for outputs consisting of ordered input vertices with preserved
    *   part structure, maximum perpendicular distance from an omitted input vertex to its replacement
    *   output segment. Otherwise N/A.
    * - Relative differences are absoluteDifference / abs(originalValue), and are N/A at zero denominator.
    */
  def calculate(original: Geometry, output: Geometry, config: BenchmarkConfig): GeometryMetrics = {
    val warnings = mutable.ArrayBuffer.empty[String]
    val polygonal = BenchmarkGeometry.isPolygonal(original)
    val originalCount = original.getNumPoints
    val outputCount = output.getNumPoints
    val removed = originalCount - outputCount
    val reduction = if (originalCount == 0) 0.0 else 100.0 * removed / originalCount
    val originalLength = original.getLength
    val outputLength = output.getLength
    val lengthDiff = math.abs(outputLength - originalLength)

    val originalArea = if (polygonal) Some(original.getArea) else None
    val outputArea = if (polygonal && BenchmarkGeometry.isPolygonal(output)) Some(output.getArea) else None
    val areaDiff = for (a <- originalArea; b <- outputArea) yield math.abs(b - a)

    val originalMetric = BenchmarkGeometry.metricLinework(original)
    val outputMetric = BenchmarkGeometry.metricLinework(output)
    val hausdorff = safeMetric("Hausdorff", warnings) {
      if (originalMetric.isEmpty && outputMetric.isEmpty) 0.0
      else DiscreteHausdorffDistance.distance(originalMetric, outputMetric, config.hausdorffDensifyFraction)
    }
    val mean = safeMetric("mean deviation", warnings) {
      symmetricMeanDeviation(originalMetric, outputMetric, config.meanSamplesPerSegment)
    }
    val maxRemoved = maximumRemovedVertexDeviation(original, output)
    val symDiff =
      if (polygonal && BenchmarkGeometry.isPolygonal(output) && original.isValid && output.isValid)
        safeMetric("symmetric difference", warnings)(original.symDifference(output).getArea)
      else None

    GeometryMetrics(
      geometryType = output.getGeometryType,
      originalVertexCount = originalCount,
      outputVertexCount = outputCount,
      removedVertexCount = removed,
      actualReductionPercent = reduction,
      componentCount = output.getNumGeometries,
      polygonHoleCount = BenchmarkGeometry.holeCount(output),
      valid = output.isValid,
      originalLength = originalLength,
      outputLength = outputLength,
      absoluteLengthDifference = lengthDiff,
      relativeLengthDifference = relative(lengthDiff, originalLength),
      originalArea = originalArea,
      outputArea = outputArea,
      absoluteAreaDifference = areaDiff,
      relativeAreaDifference = areaDiff.flatMap(d => originalArea.flatMap(relative(d, _))),
      symmetricHausdorffDistance = hausdorff,
      meanGeometricDeviation = mean,
      maximumRemovedVertexDeviation = maxRemoved,
      symmetricDifferenceArea = symDiff,
      structure = structuralDiagnostics(original, output),
      warnings = warnings.toVector
    )
  }

  private def relative(diff: Double, original: Double): Option[Double] =
    if (original == 0.0 || !diff.isFinite || !original.isFinite) None else Some(diff / math.abs(original))

  private def safeMetric(name: String, warnings: mutable.ArrayBuffer[String])(body: => Double): Option[Double] =
    try {
      val value = body
      if (value.isFinite) Some(value)
      else { warnings += s"$name was non-finite"; None }
    } catch {
      case NonFatal(e) => warnings += s"$name unavailable: ${e.getClass.getSimpleName}: ${e.getMessage}"; None
    }

  private def symmetricMeanDeviation(a: Geometry, b: Geometry, samplesPerSegment: Int): Double = {
    if (a.isEmpty && b.isEmpty) return 0.0
    if (a.isEmpty || b.isEmpty) return Double.NaN
    val distances = sampleCoordinates(a, samplesPerSegment).iterator.map(distanceTo(_, b)) ++
      sampleCoordinates(b, samplesPerSegment).iterator.map(distanceTo(_, a))
    var sum = 0.0
    var n = 0
    distances.foreach { d => sum += d; n += 1 }
    if (n == 0) Double.NaN else sum / n
  }

  private def sampleCoordinates(g: Geometry, samplesPerSegment: Int): Vector[Coordinate] = {
    val out = Vector.newBuilder[Coordinate]
    BenchmarkGeometry.parts(g).foreach { line =>
      val c = line.getCoordinates
      if (c.nonEmpty) out += c.head
      var i = 0
      while (i < c.length - 1) {
        var s = 1
        while (s <= samplesPerSegment) {
          val t = s.toDouble / samplesPerSegment
          out += new Coordinate(c(i).x + (c(i + 1).x - c(i).x) * t,
            c(i).y + (c(i + 1).y - c(i).y) * t)
          s += 1
        }
        i += 1
      }
    }
    out.result()
  }

  private def distanceTo(c: Coordinate, target: Geometry): Double =
    DistanceOp.distance(target.getFactory.createPoint(c), target)

  private def structuralDiagnostics(original: Geometry, output: Geometry): StructuralDiagnostics = {
    val originalParts = BenchmarkGeometry.parts(original)
    val outputParts = BenchmarkGeometry.parts(output)
    StructuralDiagnostics(
      geometryTypePreserved = original.getGeometryType == output.getGeometryType,
      componentCountPreserved = original.getNumGeometries == output.getNumGeometries,
      holeCountsPreserved = BenchmarkGeometry.holeCounts(original) == BenchmarkGeometry.holeCounts(output),
      outputUsesOnlyInputVertices = output.getCoordinates.forall(c => original.getCoordinates.exists(close(_, c))),
      endpointsPreserved = endpointCheck(originalParts, outputParts),
      ringsClosed = ringClosureCheck(output, outputParts)
    )
  }

  private def endpointCheck(original: Vector[LineString], output: Vector[LineString]): Option[Boolean] = {
    val openIndices = original.indices.filter(i => !original(i).isClosed)
    if (openIndices.isEmpty) None
    else if (original.length != output.length) Some(false)
    else Some(openIndices.forall { i =>
      val a = original(i); val b = output(i)
      !b.isEmpty && close(a.getCoordinateN(0), b.getCoordinateN(0)) &&
        close(a.getCoordinateN(a.getNumPoints - 1), b.getCoordinateN(b.getNumPoints - 1))
    })
  }

  private def ringClosureCheck(output: Geometry, parts: Vector[LineString]): Option[Boolean] = {
    if (BenchmarkGeometry.isPolygonal(output))
      Some(parts.nonEmpty && parts.forall(r => r.getNumPoints >= 4 && close(r.getCoordinateN(0), r.getCoordinateN(r.getNumPoints - 1))))
    else {
      val closedLines = parts.filter(_.isClosed)
      if (closedLines.isEmpty) None
      else Some(closedLines.forall(r => r.getNumPoints >= 2 && close(r.getCoordinateN(0), r.getCoordinateN(r.getNumPoints - 1))))
    }
  }

  private def maximumRemovedVertexDeviation(original: Geometry, output: Geometry): Option[Double] = {
    val sourceParts = BenchmarkGeometry.parts(original)
    val resultParts = BenchmarkGeometry.parts(output)
    if (sourceParts.length != resultParts.length) return None
    var maximum = 0.0
    var p = 0
    while (p < sourceParts.length) {
      alignedIndices(sourceParts(p).getCoordinates, resultParts(p).getCoordinates) match {
        case None => return None
        case Some((source, indices)) =>
          var k = 0
          while (k < indices.length - 1) {
            var i = indices(k) + 1
            while (i < indices(k + 1)) {
              maximum = math.max(maximum, Distance.pointToSegment(source(i), source(indices(k)), source(indices(k + 1))))
              i += 1
            }
            k += 1
          }
      }
      p += 1
    }
    Some(maximum)
  }

  /** Returns an aligned source sequence and monotonically increasing output indices. */
  private def alignedIndices(source0: Array[Coordinate], result0: Array[Coordinate]): Option[(Array[Coordinate], Array[Int])] = {
    if (result0.isEmpty) return if (source0.isEmpty) Some(source0 -> Array.emptyIntArray) else None
    val closed = source0.length > 1 && close(source0.head, source0.last)
    val source =
      if (!closed) source0
      else {
        val distinct = source0.dropRight(1)
        val start = distinct.indexWhere(close(_, result0.head))
        if (start < 0) return None
        val rotated = distinct.drop(start) ++ distinct.take(start)
        rotated :+ rotated.head
      }
    val indices = new Array[Int](result0.length)
    var cursor = 0
    var r = 0
    while (r < result0.length) {
      var found = -1
      var i = cursor
      while (i < source.length && found < 0) { if (close(source(i), result0(r))) found = i; i += 1 }
      if (found < 0) return None
      indices(r) = found
      cursor = found + 1
      r += 1
    }
    Some(source -> indices)
  }

  private def close(a: Coordinate, b: Coordinate): Boolean = {
    def ordinate(x: Double, y: Double): Boolean = {
      val scale = math.max(1.0, math.max(math.abs(x), math.abs(y)))
      math.abs(x - y) <= 1e-12 * scale
    }
    ordinate(a.x, b.x) && ordinate(a.y, b.y)
  }
}
