package com.adaptive.simplifier.benchmark

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

/** Reads one WKT per nonblank line and writes normalized metrics, paired comparisons, and WKT exports. */
object BatchBenchmarkRunner {
  val DefaultTargets: Seq[Double] = Seq(10.0, 25.0, 40.0, 50.0, 70.0, 90.0)

  def run(
      inputFile: Path,
      outputDirectory: Path,
      targets: Seq[Double] = DefaultTargets,
      config: BenchmarkConfig = BenchmarkConfig()
  ): BatchBenchmarkSummary = {
    require(Files.isRegularFile(inputFile), s"Input file does not exist: $inputFile")
    require(targets.nonEmpty, "At least one target reduction is required")
    require(targets.forall(t => t.isFinite && t >= 0.0 && t <= 100.0), "Targets must be finite and in [0, 100]")

    val inputs = Files.readAllLines(inputFile, StandardCharsets.UTF_8).asScala.map(_.trim).filter(_.nonEmpty).toVector
    val metricRows = Vector.newBuilder[Seq[String]]
    val comparisonRows = Vector.newBuilder[Seq[String]]
    val geometryRows = Vector.newBuilder[Seq[String]]
    var failures = 0

    inputs.zipWithIndex.foreach { case (wkt, index) =>
      val id = stableId(index, wkt)
      targets.foreach { target =>
        val report = try SimplificationBenchmark.compare(wkt, target, config) catch {
          case NonFatal(e) =>
            val message = s"Unexpected benchmark failure: ${e.getClass.getSimpleName}: ${e.getMessage}"
            SimplificationComparisonReport(wkt, target, None,
              failed("adaptive", message), failed("douglas_peucker", message), matched = false,
              None, Some(message), config)
        }
        if (report.inputError.nonEmpty || report.adaptive.error.nonEmpty || report.douglasPeucker.error.nonEmpty ||
            report.adaptive.fallbackToOriginal.contains(true)) failures += 1
        metricRows += BenchmarkCsv.metricsRow(id, target, BenchmarkCsv.originalResult(report.originalMetrics, target))
        metricRows += BenchmarkCsv.metricsRow(id, target, report.adaptive)
        metricRows += BenchmarkCsv.metricsRow(id, target, report.douglasPeucker)
        comparisonRows += BenchmarkCsv.comparisonRow(id, report)
        geometryRows += BenchmarkCsv.geometryRow(id, report)
      }
    }

    val metricsPath = outputDirectory.resolve("algorithm_metrics.csv")
    val comparisonsPath = outputDirectory.resolve("paired_comparisons.csv")
    val geometriesPath = outputDirectory.resolve("geometries.csv")
    BenchmarkCsv.write(metricsPath, BenchmarkCsv.MetricsHeader, metricRows.result())
    BenchmarkCsv.write(comparisonsPath, BenchmarkCsv.ComparisonsHeader, comparisonRows.result())
    BenchmarkCsv.write(geometriesPath, BenchmarkCsv.GeometriesHeader, geometryRows.result())

    BatchBenchmarkSummary(inputs.size, targets.size, inputs.size * targets.size, failures,
      metricsPath, comparisonsPath, geometriesPath)
  }

  private def failed(algorithm: String, message: String) =
    AlgorithmBenchmarkResult(algorithm, None, None, None, None, None, Some(message))

  private def stableId(index: Int, wkt: String): String = {
    val digest = MessageDigest.getInstance("SHA-256").digest(wkt.getBytes(StandardCharsets.UTF_8))
    val shortHash = digest.take(6).map(b => f"${b & 0xff}%02x").mkString
    f"record-${index + 1}%05d-$shortHash"
  }
}
