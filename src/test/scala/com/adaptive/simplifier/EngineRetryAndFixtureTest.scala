package com.adaptive.simplifier

import com.adaptive.simplifier.TestSupport._
import org.junit.Assert._
import org.junit.Test
import org.locationtech.jts.geom.{MultiPolygon, Polygon}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters._

class EngineRetryAndFixtureTest {
  private def adversarialPolygon: String = {
    val ring = (0 until 60).map { i =>
      val a = 2 * math.Pi * i / 60
      val r = 10 + (if (i % 3 == 0) 1.5 else 0)
      s"${r * math.cos(a)} ${r * math.sin(a)}"
    }
    s"POLYGON ((${(ring :+ ring.head).mkString(", ")}), (-2 -2, -2 2, 2 2, 2 -2, -2 -2))"
  }

  @Test def invalidAggressiveCandidateBacksOffAndReturnsValidGeometry(): Unit = {
    val report = AdaptiveSimplifier.simplifyWithReport(adversarialPolygon, 90)
    assertTrue("fixture should exercise retry", report.rejectedCandidates.nonEmpty)
    assertTrue(report.attempts > 1)
    assertTrue(report.attempts <= AdaptiveConfig.Default.maxAttempts)
    assertTrue(parse(report.wkt).isValid)
    assertEquals(1, parse(report.wkt).asInstanceOf[Polygon].getNumInteriorRing)

    val removals = report.rejectedCandidates.flatMap("removing ([0-9]+)".r.findFirstMatchIn(_).map(_.group(1).toInt))
    assertTrue(removals.sliding(2).forall(p => p.length < 2 || p(1) < p(0)))
  }

  @Test def engineFallbackReturnsOriginalWhenEvenCleanedCandidateCannotBeBuilt(): Unit = {
    // The public API correctly rejects this invalid line. Calling the package-private engine isolates
    // and deterministically exercises its final fallback after duplicate cleanup leaves one point.
    val input = parse("LINESTRING (0 0, 0 0)")
    val outcome = AdaptiveEngine.run(input, 50, AdaptiveConfig.Default.copy(maxAttempts = 2))
    assertTrue(outcome.fallbackToOriginal)
    assertTrue(outcome.geometry.equalsExact(input))
    assertTrue(outcome.rejected.exists(_.contains("construction failed")))
    assertTrue(outcome.attempts <= 2)
  }

  @Test def boundedRetryUsesAValidLessAggressiveCandidateWhenAvailable(): Unit = {
    val input = parse(adversarialPolygon)
    val outcome = AdaptiveEngine.run(input, 90, AdaptiveConfig.Default.copy(maxAttempts = 2))
    assertTrue(outcome.geometry.isValid)
    assertTrue(outcome.attempts <= 2)
    if (outcome.fallbackToOriginal) assertTrue(outcome.geometry.equalsExact(input))
  }

  @Test def suppliedWktExamplesRemainValidAndStructurallyStable(): Unit = {
    val path = Paths.get("wkt_examples.txt")
    assertTrue("supplied WKT fixture file is missing", Files.exists(path))
    val wkts = Files.readAllLines(path, StandardCharsets.UTF_8).asScala.map(_.trim).filter(_.nonEmpty)
    assertEquals(61, wkts.size)
    wkts.zipWithIndex.foreach { case (wkt, index) =>
      val input = parse(wkt)
      assertTrue(s"fixture $index is invalid", input.isValid)
      val output = parse(AdaptiveSimplifier.simplify(wkt, 40))
      assertValidAndSameStructure(input, output)
      assertLineEndpoints(input, output)
      assertCoordinatesComeFromInput(input, output)
    }
  }

  @Test def publicApiIsDeterministicUnderConcurrentCalls(): Unit = {
    import scala.concurrent.ExecutionContext.Implicits.global
    import scala.concurrent.duration._
    import scala.concurrent.{Await, Future}
    val inputs = Seq(
      "LINESTRING (0 0, 1 2, 2 -1, 3 3, 4 0)",
      "POLYGON ((0 0, 6 0, 7 3, 6 6, 0 6, -1 3, 0 0))"
    )
    val expected = inputs.map(AdaptiveSimplifier.simplify(_, 55))
    val calls = (0 until 100).map(i => Future(AdaptiveSimplifier.simplify(inputs(i % inputs.size), 55)))
    val results = Await.result(Future.sequence(calls), 30.seconds)
    results.zipWithIndex.foreach { case (result, i) => assertEquals(expected(i % inputs.size), result) }
  }
}
