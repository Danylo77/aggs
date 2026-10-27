package com.adaptive.simplifier.benchmark

import org.junit.Assert._
import org.junit.Test
import org.locationtech.jts.simplify.DouglasPeuckerSimplifier

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

class SimplificationBenchmarkTest {
  private val line = "LINESTRING (0 0, 1 0.1, 2 -0.1, 3 3, 4 -2, 5 0.2, 6 0)"
  private val polygon = "POLYGON ((0 0, 10 0, 11 3, 10 10, 6 11, 0 10, -1 5, 0 0), (2 2, 2 5, 5 5, 5 2, 2 2))"
  private val multi = "MULTIPOLYGON (((0 0, 5 0, 6 2, 5 5, 0 5, -1 2, 0 0)), ((10 0, 15 0, 16 2, 15 5, 10 5, 9 2, 10 0)))"

  @Test def bothAlgorithmsRunAndAreRateMatchedOnALine(): Unit = {
    val report = SimplificationBenchmark.compare(line, 50)
    assertEquals(None, report.inputError)
    assertEquals(None, report.adaptive.error)
    assertEquals(None, report.douglasPeucker.error)
    assertTrue(report.adaptive.wkt.nonEmpty)
    assertTrue(report.douglasPeucker.wkt.nonEmpty)
    assertTrue(report.douglasPeucker.selectedTolerance.exists(_ >= 0.0))
    assertTrue(report.douglasPeucker.toleranceSearchRuntimeMillis.exists(_ >= 0.0))
    assertTrue(report.achievedReductionDifferencePercent.nonEmpty)
    assertEquals(report.achievedReductionDifferencePercent.exists(_ <= report.config.reductionMatchTolerancePercent), report.matched)
    Seq(report.adaptive, report.douglasPeucker).foreach { result =>
      assertTrue(result.runtimeMillis.exists(_ >= 0.0))
      assertTrue(result.metrics.exists(_.valid))
      assertTrue(result.metrics.exists(_.structure.endpointsPreserved.contains(true)))
    }
  }

  @Test def zeroAndHighTargetsAreHandledWithoutAssumingExactReduction(): Unit = {
    val zero = SimplificationBenchmark.compare(line, 0)
    assertEquals(0.0, zero.adaptive.metrics.get.actualReductionPercent, 0.0)
    assertEquals(0.0, zero.douglasPeucker.metrics.get.actualReductionPercent, 0.0)
    assertTrue(zero.matched)

    val high = SimplificationBenchmark.compare(line, 100)
    assertTrue(high.adaptive.metrics.get.outputVertexCount >= 2)
    assertTrue(high.douglasPeucker.metrics.get.outputVertexCount >= 2)
    assertTrue(high.adaptive.metrics.get.actualReductionPercent <= 100.0)
  }

  @Test def constrainedToleranceIsExplicitlyUnmatched(): Unit = {
    val report = SimplificationBenchmark.compare(line, 80,
      BenchmarkConfig(dpMaxIterations = 4, dpMaxTolerance = Some(0.0), reductionMatchTolerancePercent = 0.5))
    assertFalse(report.matched)
    assertEquals(Some(0.0), report.douglasPeucker.selectedTolerance)
    assertTrue(report.achievedReductionDifferencePercent.exists(_ > 0.5))
    assertEquals(0.0, report.douglasPeucker.metrics.get.actualReductionPercent, 0.0)
  }

  @Test def polygonHoleAndMultipolygonDiagnosticsAndAreaMetricsApply(): Unit = {
    Seq(polygon -> 1, multi -> 0).foreach { case (wkt, holes) =>
      val report = SimplificationBenchmark.compare(wkt, 40)
      Seq(report.adaptive, report.douglasPeucker).foreach { result =>
        val m = result.metrics.get
        assertTrue(m.originalArea.nonEmpty)
        assertTrue(m.outputArea.nonEmpty)
        assertTrue(m.absoluteAreaDifference.nonEmpty)
        assertTrue(m.symmetricDifferenceArea.nonEmpty)
        assertEquals(holes, m.polygonHoleCount)
        assertTrue(m.structure.geometryTypePreserved)
        assertTrue(m.structure.componentCountPreserved)
        assertTrue(m.structure.holeCountsPreserved)
        assertEquals(Some(true), m.structure.ringsClosed)
      }
    }
  }

  @Test def lineAreaIsNotApplicableAndKnownDeviationIsMeasured(): Unit = {
    val original = BenchmarkGeometry.parse("LINESTRING (0 0, 1 1, 2 0)")
    val output = BenchmarkGeometry.parse("LINESTRING (0 0, 2 0)")
    val m = GeometryMetricsCalculator.calculate(original, output, BenchmarkConfig())
    assertEquals(None, m.originalArea)
    assertEquals(None, m.outputArea)
    assertEquals(None, m.symmetricDifferenceArea)
    assertEquals(Some(1.0), m.maximumRemovedVertexDeviation)
    assertEquals(Some(1.0), m.symmetricHausdorffDistance)
  }

  @Test def emptyGeometryUsesNAForZeroDenominatorRelativeMetrics(): Unit = {
    val empty = BenchmarkGeometry.parse("LINESTRING EMPTY")
    val m = GeometryMetricsCalculator.calculate(empty, empty, BenchmarkConfig())
    assertEquals(0, m.outputVertexCount)
    assertEquals(None, m.relativeLengthDifference)
    assertEquals(Some(0.0), m.symmetricHausdorffDistance)
    assertEquals(Some(0.0), m.meanGeometricDeviation)
  }

  @Test def closedLineReportsClosureWithoutPolygonMinimumSizeRule(): Unit = {
    val closed = BenchmarkGeometry.parse("LINESTRING (0 0, 1 1, 0 0)")
    val m = GeometryMetricsCalculator.calculate(closed, closed, BenchmarkConfig())
    assertEquals(Some(true), m.structure.ringsClosed)
  }

  @Test def invalidUnsupportedAndInvalidPercentAreInputErrors(): Unit = {
    Seq(
      SimplificationBenchmark.compare("LINESTRING (0 0, broken)", 40),
      SimplificationBenchmark.compare("POINT (0 0)", 40),
      SimplificationBenchmark.compare("POLYGON ((0 0, 2 2, 2 0, 0 2, 0 0))", 40),
      SimplificationBenchmark.compare(line, Double.NaN)
    ).foreach { r =>
      assertTrue(r.inputError.nonEmpty)
      assertFalse(r.matched)
      assertTrue(r.adaptive.error.nonEmpty)
      assertTrue(r.douglasPeucker.error.nonEmpty)
    }
  }

  @Test def polygonSearchDoesNotDiscardACloserNonMonotonicCandidate(): Unit = {
    val wkt = Files.readAllLines(Paths.get("wkt_examples.txt"), StandardCharsets.UTF_8).get(1)
    val geometry = BenchmarkGeometry.parse(wkt)
    val target = 91.0
    val selected = DouglasPeuckerMatcher.search(geometry, target, BenchmarkConfig(dpMaxIterations = 30))
    val knownTolerance = 8.138327044161169e-4
    val known = DouglasPeuckerSimplifier.simplify(geometry, knownTolerance)
    val knownReduction = 100.0 * (geometry.getNumPoints - known.getNumPoints) / geometry.getNumPoints
    assertTrue(math.abs(selected.actualReductionPercent - target) <= math.abs(knownReduction - target) + 1e-12)
  }

  @Test def adaptiveFallbackCannotBeClassifiedAsMatched(): Unit = {
    val fallback = AlgorithmBenchmarkResult("adaptive", None, None, Some(1.0), None, Some(0.0), None,
      fallbackToOriginal = Some(true))
    val dp = AlgorithmBenchmarkResult("douglas_peucker", None, None, Some(1.0), Some(0.0), Some(0.0), None)
    assertFalse(SimplificationBenchmark.matchEligible(fallback, dp, Some(0.0), BenchmarkConfig()))
  }

  @Test def pointSequencesExposeParsedVerticesInsteadOfComparingWktText(): Unit = {
    val report = SimplificationBenchmark.compare(line, 50)
    val points = SimplificationBenchmark.pointSequences(report).toOption.get
    assertEquals(report.originalMetrics.get.outputVertexCount, points.original.size)
    assertEquals(report.adaptive.metrics.get.outputVertexCount, points.adaptive.size)
    assertEquals(report.douglasPeucker.metrics.get.outputVertexCount, points.douglasPeucker.size)
    assertFalse(SimplificationBenchmark.samePointSequence(points.original, points.adaptive))
    assertTrue(points.original.head.equalsExact(points.adaptive.head, 1e-12))
    assertTrue(points.original.last.equalsExact(points.adaptive.last, 1e-12))
  }

  @Test def pointSequencesIncludePolygonHoleClosuresAndHandleEmptyGeometry(): Unit = {
    val polygonReport = SimplificationBenchmark.compare(polygon, 40)
    val polygonPoints = SimplificationBenchmark.pointSequences(polygonReport).toOption.get
    assertEquals(polygonReport.adaptive.metrics.get.outputVertexCount, polygonPoints.adaptive.size)

    val empty = SimplificationBenchmark.pointSequences(
      SimplificationBenchmark.compare("LINESTRING EMPTY", 40)).toOption.get
    assertTrue(empty.original.isEmpty)
    assertTrue(empty.adaptive.isEmpty)
    assertTrue(empty.douglasPeucker.isEmpty)
  }

  @Test def pointSequencesReturnErrorInsteadOfThrowingWhenResultsAreUnavailable(): Unit = {
    val invalid = SimplificationBenchmark.compare("POINT (0 0)", 40)
    assertTrue(SimplificationBenchmark.pointSequences(invalid).isLeft)
  }

  @Test def metricsAreReproducibleApartFromRuntime(): Unit = {
    val first = SimplificationBenchmark.compare(polygon, 50)
    val second = SimplificationBenchmark.compare(polygon, 50)
    assertEquals(first.adaptive.wkt, second.adaptive.wkt)
    assertEquals(first.douglasPeucker.wkt, second.douglasPeucker.wkt)
    assertEquals(first.adaptive.metrics, second.adaptive.metrics)
    assertEquals(first.douglasPeucker.metrics, second.douglasPeucker.metrics)
    assertEquals(first.matched, second.matched)
    assertEquals(first.achievedReductionDifferencePercent, second.achievedReductionDifferencePercent)
  }
}
