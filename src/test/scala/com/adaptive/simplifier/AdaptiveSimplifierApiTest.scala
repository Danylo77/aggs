package com.adaptive.simplifier

import com.adaptive.simplifier.TestSupport._
import com.adaptive.simplifier.model._
import org.junit.Assert._
import org.junit.Test

class AdaptiveSimplifierApiTest {
  private val supported = Seq(
    "LINESTRING (0 0, 1 0.1, 2 -0.1, 3 2, 4 2.1, 5 2)",
    "MULTILINESTRING ((0 0, 1 1, 2 0, 3 1), (10 0, 11 0.1, 12 2, 13 0))",
    "POLYGON ((0 0, 12 0, 12 12, 10 13, 6 12, 2 13, 0 12, 0 0), (3 3, 3 6, 6 6, 6 3, 3 3))",
    "MULTIPOLYGON (((0 0, 5 0, 6 2, 5 5, 0 5, -1 2, 0 0)), ((10 0, 15 0, 16 2, 15 5, 10 5, 9 2, 10 0)))"
  )

  @Test def supportsAllRequiredTypesAcrossPercentages(): Unit = {
    for (wkt <- supported; percent <- Seq(0.0, 1.0, 40.0, 99.0, 100.0)) {
      val input = parse(wkt)
      val report = AdaptiveSimplifier.simplifyWithReport(wkt, percent)
      val output = parse(report.wkt)
      assertEquals(report.wkt, AdaptiveSimplifier.simplify(wkt, percent))
      assertValidAndSameStructure(input, output)
      assertLineEndpoints(input, output)
      assertCoordinatesComeFromInput(input, output)
      assertEquals(input.getNumPoints, report.originalVertexCount)
      assertEquals(output.getNumPoints, report.resultVertexCount)
      assertEquals(100.0 * (input.getNumPoints - output.getNumPoints) / input.getNumPoints,
        report.achievedReductionPercent, 1e-12)
      assertTrue(report.attempts >= 0 && report.attempts <= AdaptiveConfig.Default.maxAttempts)
    }
  }

  @Test def zeroPercentReturnsCanonicalizedInputWithoutAttempt(): Unit = {
    val report = AdaptiveSimplifier.simplifyWithReport("LINESTRING Z (0 0 7, 1 1 8, 2 0 9)", -0.0)
    assertEquals("LINESTRING (0 0, 1 1, 2 0)", report.wkt)
    assertEquals(0, report.attempts)
    assertEquals(0.0, report.achievedReductionPercent, 0.0)
    assertEquals(Double.NegativeInfinity, report.threshold, 0.0)
    assertFalse(report.fallbackToOriginal)
  }

  @Test def percentageValidationIsFiniteInclusiveAndPrecedesWktParsing(): Unit = {
    Seq(-0.0001, 100.0001, Double.NaN, Double.PositiveInfinity, Double.NegativeInfinity).foreach { p =>
      expectThrows(classOf[InvalidReductionPercentException]) {
        AdaptiveSimplifier.simplify("not WKT", p)
      }
    }
    AdaptiveSimplifier.simplify("LINESTRING (0 0, 1 1)", 0.0)
    AdaptiveSimplifier.simplify("LINESTRING (0 0, 1 1)", 100.0)
  }

  @Test def malformedAndStructurallyImpossibleWktAreRejected(): Unit = {
    Seq[String](null, "", "   ", "LINESTRING (0 0, nope)", "LINESTRING (0 0)",
      "POLYGON ((0 0, 1 0, 1 1, 0 1))", "NOTAGEOMETRY (0 0)").foreach { wkt =>
      expectThrows(classOf[InvalidWktException]) { AdaptiveSimplifier.simplify(wkt, 30) }
    }
  }

  @Test def unsupportedTypesAreRejectedClearly(): Unit = {
    Seq("POINT (1 2)", "MULTIPOINT ((1 2), (3 4))",
      "GEOMETRYCOLLECTION (LINESTRING (0 0, 1 1))", "POINT EMPTY").foreach { wkt =>
      val e = expectThrows(classOf[UnsupportedGeometryTypeException]) { AdaptiveSimplifier.simplify(wkt, 30) }
      assertTrue(e.getMessage.contains("Unsupported geometry type"))
    }
  }

  @Test def supportedEmptyGeometriesAreReturnedEmpty(): Unit = {
    Seq("LINESTRING EMPTY", "MULTILINESTRING EMPTY", "POLYGON EMPTY", "MULTIPOLYGON EMPTY").foreach { wkt =>
      val report = AdaptiveSimplifier.simplifyWithReport(wkt, 80)
      assertTrue(parse(report.wkt).isEmpty)
      assertEquals(parse(wkt).getGeometryType, parse(report.wkt).getGeometryType)
      assertEquals(0, report.originalVertexCount)
      assertEquals(0, report.resultVertexCount)
      assertEquals(0, report.attempts)
    }
  }

  @Test def invalidAndNumericallyUnsafeGeometryIsRejectedWithoutRepair(): Unit = {
    Seq(
      "POLYGON ((0 0, 2 2, 2 0, 0 2, 0 0))",
      "POLYGON ((0 0, 10 0, 10 10, 0 10, 0 0), (20 20, 21 20, 21 21, 20 21, 20 20))",
      "MULTIPOLYGON (((0 0, 4 0, 4 4, 0 4, 0 0)), ((2 2, 6 2, 6 6, 2 6, 2 2)))",
      "LINESTRING (-1E308 0, 0 1, 1E308 0)"
    ).foreach { wkt =>
      expectThrows(classOf[InvalidGeometryException]) { AdaptiveSimplifier.simplify(wkt, 50) }
    }
  }

  @Test def shortAndMinimalPartsRemainUsable(): Unit = {
    val line = parse(AdaptiveSimplifier.simplify("LINESTRING (0 0, 1 1)", 100))
    assertEquals(2, line.getNumPoints)
    val triangle = parse(AdaptiveSimplifier.simplify("POLYGON ((0 0, 4 0, 0 4, 0 0))", 100))
    assertEquals(4, triangle.getNumPoints)
    assertTrue(triangle.isValid)
  }

  @Test def consecutiveDuplicatesAreRemovedForPositiveTargetsAndCounted(): Unit = {
    val report = AdaptiveSimplifier.simplifyWithReport(
      "LINESTRING (0 0, 0 0, 1 1, 1 1, 2 0, 3 1, 3 1)", 1.0)
    assertTrue(report.resultVertexCount <= 4)
    assertEquals(7, report.originalVertexCount)
    assertTrue(report.achievedReductionPercent > 1.0)
    val out = parse(report.wkt).getCoordinates
    assertFalse(out.sliding(2).exists(p => p(0).equals2D(p(1))))
  }

  @Test def increasingTargetsNeverIncreaseTheVertexCount(): Unit = {
    val wkt = "LINESTRING (0 0, 1 3, 2 -2, 3 4, 4 -3, 5 2, 6 0, 7 4, 8 -1, 9 0)"
    val counts = (0 to 100).map(p => AdaptiveSimplifier.simplifyWithReport(wkt, p).resultVertexCount)
    assertTrue(counts.sliding(2).forall(pair => pair.length < 2 || pair(1) <= pair(0)))
    assertEquals(10, counts.head)
    assertEquals(2, counts.last)
  }

  @Test def duplicateOnlyDegenerateLineIsRejectedAsInvalidInput(): Unit = {
    expectThrows(classOf[InvalidGeometryException]) {
      AdaptiveSimplifier.simplifyWithReport("LINESTRING (0 0, 0 0)", 50)
    }
  }
}
