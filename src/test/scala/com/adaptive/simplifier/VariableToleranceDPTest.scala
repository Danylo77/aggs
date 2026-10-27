package com.adaptive.simplifier

import com.adaptive.simplifier.TestSupport._
import org.junit.Assert._
import org.junit.Test
import org.locationtech.jts.algorithm.Distance
import org.locationtech.jts.geom.Coordinate

class VariableToleranceDPTest {
  @Test def threePointSignificanceIsDistanceDividedByMultiplier(): Unit = {
    val coords = Array(new Coordinate(0,0), new Coordinate(1,2), new Coordinate(2,0))
    val ranking = VariableToleranceDP.rank(coords, Array(1.0, 2.0, 1.0), Array(0,2))
    assertTrue(ranking.significance(0).isPosInfinity)
    assertTrue(ranking.significance(2).isPosInfinity)
    assertEquals(1.0, ranking.significance(1), 1e-12)
    assertEquals(1, ranking.depth(1))
  }

  @Test def anchorsSplitIndependentSpansAndCollinearTiesAreZero(): Unit = {
    val coords = Array.tabulate(7)(i => new Coordinate(i, 0))
    val r = VariableToleranceDP.rank(coords, Array.fill(7)(1.0), Array(0,3,6))
    Seq(0,3,6).foreach(i => assertTrue(r.significance(i).isPosInfinity))
    Seq(1,2,4,5).foreach(i => assertEquals(0.0, r.significance(i), 0.0))
  }

  @Test def thresholdKeptSetsAreNestedAndRespectLocalBound(): Unit = {
    val coords = Array(
      new Coordinate(0,0), new Coordinate(1,3), new Coordinate(2,-1),
      new Coordinate(3,4), new Coordinate(4,-2), new Coordinate(5,0))
    val multipliers = Array(1.0, 0.5, 2.0, 0.75, 1.5, 1.0)
    val r = VariableToleranceDP.rank(coords, multipliers, Array(0,5))
    val thresholds = r.significance.filter(_.isFinite).distinct.sorted
    var previous = coords.indices.toSet
    thresholds.foreach { t =>
      val kept = coords.indices.filter(i => r.significance(i) > t).toSet
      assertTrue(kept.subsetOf(previous))
      val ordered = kept.toSeq.sorted
      ordered.sliding(2).foreach { pair =>
        for (i <- pair.head + 1 until pair(1))
          assertTrue(Distance.pointToSegment(coords(i), coords(pair.head), coords(pair(1))) <= t * multipliers(i) + 1e-12)
      }
      previous = kept
    }
  }

  @Test def rankingRejectsInvalidInputs(): Unit = {
    val coords = Array(new Coordinate(0,0), new Coordinate(1,1), new Coordinate(2,0))
    expectThrows(classOf[IllegalArgumentException]) { VariableToleranceDP.rank(coords, Array(1.0), Array(0,2)) }
    expectThrows(classOf[IllegalArgumentException]) { VariableToleranceDP.rank(coords, Array(1.0,0.0,1.0), Array(0,2)) }
    expectThrows(classOf[IllegalArgumentException]) { VariableToleranceDP.rank(coords, Array(1.0,1.0,1.0), Array(2,0)) }
    expectThrows(classOf[IllegalArgumentException]) { VariableToleranceDP.rank(coords, Array(1.0,1.0,1.0), Array(0,1)) }
  }

  @Test def iterativeImplementationHandlesLongInputWithoutStackOverflow(): Unit = {
    val coords = Array.tabulate(2000)(i => new Coordinate(i, math.sin(i * 0.1)))
    val r = VariableToleranceDP.rank(coords, Array.fill(coords.length)(1.0), Array(0, coords.length - 1))
    assertEquals(coords.length, r.significance.length)
    assertTrue(r.significance.forall(s => s.isFinite || s.isPosInfinity))
  }
}
