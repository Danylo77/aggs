package com.adaptive.simplifier.benchmark

import org.junit.Assert._
import org.junit.Test

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.jdk.CollectionConverters._

class BatchBenchmarkRunnerTest {
  @Test def csvOutputIsCompleteEscapedAndContinuesAfterFailures(): Unit = {
    val root = Files.createTempDirectory("adaptive-benchmark-test")
    val input = root.resolve("input.wkt")
    val output = root.resolve("results")
    Files.write(input, Seq(
      "LINESTRING (0 0, 1 2, 2 0, 3 1)",
      "POINT (1 1)",
      "not valid WKT"
    ).asJava, StandardCharsets.UTF_8)

    val summary = BatchBenchmarkRunner.run(input, output, Seq(0.0, 50.0),
      BenchmarkConfig(dpMaxIterations = 8, meanSamplesPerSegment = 1))
    assertEquals(3, summary.inputRecords)
    assertEquals(2, summary.targetCount)
    assertEquals(6, summary.comparisonCount)
    assertEquals(4, summary.failureCount)
    assertTrue(Files.isRegularFile(summary.metricsCsv))
    assertTrue(Files.isRegularFile(summary.comparisonsCsv))
    assertTrue(Files.isRegularFile(summary.geometriesCsv))

    val metricLines = Files.readAllLines(summary.metricsCsv, StandardCharsets.UTF_8).asScala
    val comparisonLines = Files.readAllLines(summary.comparisonsCsv, StandardCharsets.UTF_8).asScala
    val geometryLines = Files.readAllLines(summary.geometriesCsv, StandardCharsets.UTF_8).asScala
    assertEquals(1 + 3 * 2 * 3, metricLines.size) // header + input/target/original+two algorithms
    assertEquals(1 + 3 * 2, comparisonLines.size)
    assertEquals(1 + 3 * 2, geometryLines.size)
    assertTrue(metricLines.head.contains("symmetric_hausdorff_distance"))
    assertTrue(metricLines.head.contains("maximum_removed_vertex_deviation"))
    assertTrue(metricLines.head.contains("adaptive_rejected_candidates"))
    assertTrue(comparisonLines.head.contains("matched"))
    assertTrue(comparisonLines.head.contains("adaptive_fallback_to_original"))
    assertTrue(comparisonLines.head.contains("adaptive_rejected_candidates"))
    assertTrue(geometryLines.head.contains("matched"))
    assertTrue(geometryLines.head.contains("adaptive_fallback_to_original"))
    assertTrue(comparisonLines.exists(_.contains("Unsupported input geometry type")))
    assertTrue(comparisonLines.exists(_.contains("Invalid input WKT")))
    assertTrue(geometryLines(1).contains("\"LINESTRING (0 0, 1 2, 2 0, 3 1)\""))
  }

  @Test def cliRejectsSurplusAndMalformedTargetArguments(): Unit = {
    com.adaptive.simplifier.TestSupport.expectThrows(classOf[IllegalArgumentException]) {
      BenchmarkCli.main(Array("batch", "input", "output", "40", "ignored"))
    }
    com.adaptive.simplifier.TestSupport.expectThrows(classOf[IllegalArgumentException]) {
      BenchmarkCli.main(Array("batch", "input", "output", "40,"))
    }
  }

  @Test def batchSettingsAndIdsAreReproducible(): Unit = {
    val root = Files.createTempDirectory("adaptive-benchmark-repro")
    val input = root.resolve("input.wkt")
    Files.write(input, Seq("LINESTRING (0 0, 1 2, 2 0, 3 1)").asJava, StandardCharsets.UTF_8)
    val a = BatchBenchmarkRunner.run(input, root.resolve("a"), Seq(25.0), BenchmarkConfig(dpMaxIterations = 5))
    val b = BatchBenchmarkRunner.run(input, root.resolve("b"), Seq(25.0), BenchmarkConfig(dpMaxIterations = 5))
    val rowA = Files.readAllLines(a.comparisonsCsv, StandardCharsets.UTF_8).get(1).split(",", -1)
    val rowB = Files.readAllLines(b.comparisonsCsv, StandardCharsets.UTF_8).get(1).split(",", -1)
    assertEquals(rowA(0), rowB(0))
    val header = Files.readAllLines(a.comparisonsCsv, StandardCharsets.UTF_8).get(0).split(",", -1)
    val runtimeColumns = header.indices.filter(i => header(i).contains("runtime_ms")).toSet
    rowA.indices.filterNot(runtimeColumns).foreach(i => assertEquals(s"column $i", rowA(i), rowB(i)))
  }
}
