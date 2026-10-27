package com.adaptive.simplifier

import com.adaptive.simplifier.TestSupport._
import org.junit.Assert._
import org.junit.Test
import org.locationtech.jts.geom.Coordinate

class ComplexityAnalysisTest {
  private val cfg = AdaptiveConfig.Default

  @Test def boxCountingHandlesShortDegenerateStraightTranslationAndScaling(): Unit = {
    val short = Array.tabulate(5)(i => new Coordinate(i, i % 2))
    assertEquals(None, BoxCounting.dimension(short, 0, 4, cfg))
    val same = Array.fill(17)(new Coordinate(3, 3))
    assertEquals(None, BoxCounting.dimension(same, 0, 16, cfg))
    val nonFinite = Array.tabulate(17)(i => new Coordinate(i, if (i == 8) Double.NaN else 0.0))
    assertEquals(None, BoxCounting.dimension(nonFinite, 0, 16, cfg))

    val horizontal = Array.tabulate(17)(i => new Coordinate(i, 5))
    val vertical = Array.tabulate(17)(i => new Coordinate(-2, i))
    val hd = BoxCounting.dimension(horizontal, 0, 16, cfg).get
    val vd = BoxCounting.dimension(vertical, 0, 16, cfg).get
    assertEquals(1.0, hd, 1e-12)
    assertEquals(hd, vd, 1e-12)

    val curve = Array.tabulate(17)(i => new Coordinate(i, math.sin(i)))
    val moved = curve.map(c => new Coordinate(c.x + 1000000, c.y - 1000000))
    val scaled = curve.map(c => new Coordinate(c.x * 10, c.y * 10))
    val d = BoxCounting.dimension(curve, 0, 16, cfg).get
    assertEquals(d, BoxCounting.dimension(moved, 0, 16, cfg).get, 1e-9)
    assertEquals(d, BoxCounting.dimension(scaled, 0, 16, cfg).get, 1e-9)
    assertTrue(d >= 1.0 && d <= 2.0)
  }

  @Test def shortComplexityFallsBackToFiniteGeometricFeatures(): Unit = {
    val coords = Array(new Coordinate(0,0), new Coordinate(1,0), new Coordinate(1,1), new Coordinate(2,1))
    val p = ComplexityAnalyzer.analyze(coords, closed = false, cfg)
    assertEquals(0, p.fractalCoveredVertices)
    assertEquals(coords.length, p.distinctVertices)
    assertTrue(p.scores.forall(s => s.isFinite && s >= 0 && s <= 1))
    assertTrue("right-angle vertex should be more complex than an endpoint", p.scores(1) > p.scores(0))
  }

  @Test def ringScoresAreCyclicClosedAndFinite(): Unit = {
    val coords = (0 until 24).map { i =>
      val a = 2 * math.Pi * i / 24
      new Coordinate(math.cos(a) * (if (i % 3 == 0) 2 else 1), math.sin(a) * (if (i % 3 == 0) 2 else 1))
    }.toArray
    val ring = coords :+ coords.head.copy()
    val p = ComplexityAnalyzer.analyze(ring, closed = true, cfg)
    assertEquals(24, p.distinctVertices)
    assertEquals(p.scores.head, p.scores.last, 0.0)
    assertTrue(p.fractalCoveredVertices > 0)
    assertTrue(p.fractalCoveredVertices <= p.distinctVertices)
    assertTrue(p.scores.forall(_.isFinite))
  }

  @Test def toleranceMappingClampsScoresAndUsesCyclicSmoothing(): Unit = {
    val exact = ToleranceModel.multipliers(Array(0.0, 0.5, 1.0, -1.0, 2.0, Double.NaN), closed = false,
      cfg.copy(smoothingRadius = 0))
    assertArrayEquals(Array(4.0, 1.0, 0.25, 4.0, 0.25, 1.0), exact, 1e-12)

    val ring = ToleranceModel.multipliers(Array(0.0, 0.5, 1.0, 0.0), closed = true,
      cfg.copy(smoothingRadius = 1))
    assertEquals(ring.head, ring.last, 0.0)
    assertTrue(ring.forall(m => m.isFinite && m >= 0.25 && m <= 4.0))
  }

  @Test def invalidInternalConfigurationIsRejected(): Unit = {
    expectThrows(classOf[IllegalArgumentException]) { AdaptiveConfig.Default.copy(fractalHalfWindow = 0) }
    expectThrows(classOf[IllegalArgumentException]) { AdaptiveConfig.Default.copy(featureHalfWindow = -1) }
    expectThrows(classOf[IllegalArgumentException]) { AdaptiveConfig.Default.copy(fractalMaxScales = 31) }
    expectThrows(classOf[IllegalArgumentException]) { AdaptiveConfig.Default.copy(weightAngle = 0, weightDeviation = 0, weightDensity = 0) }
    expectThrows(classOf[IllegalArgumentException]) { AdaptiveConfig.Default.copy(maxAttempts = 1) }
  }
}
