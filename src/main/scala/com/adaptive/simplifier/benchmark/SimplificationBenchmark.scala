package com.adaptive.simplifier.benchmark

import com.adaptive.simplifier.{AdaptiveSimplifier, GeometryValidator}
import org.locationtech.jts.geom.Point
import org.locationtech.jts.simplify.DouglasPeuckerSimplifier

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import java.util
import scala.jdk.CollectionConverters.IterableHasAsJava
import scala.util.control.NonFatal

/** Separate comparison facade; it does not alter production simplification behavior. */
object SimplificationBenchmark {

  /**
   * Console comparison for the first Venice island/coastal-boundary record (record 31) in
   * `wkt_examples.txt` at 40% when no arguments are supplied. Arguments may be
   * `<target-percent> <WKT>`.
   */

  val targets = Seq(20, 40, 60, 80)

  def main(args: Array[String]): Unit = {
    val wkts: util.List[String] = Files.readAllLines(Paths.get("wkt_examples.txt"), StandardCharsets.UTF_8)
//    val wkts = Seq(Files.readAllLines(Paths.get("japan.txt"), StandardCharsets.UTF_8).get(0)).asJava
//    val wkts = Seq(Files.readAllLines(Paths.get("wkt_examples.txt"), StandardCharsets.UTF_8).get(0)).asJava

    wkts.forEach(
      wkt =>
        targets.map(target => compare(wkt, target))
          .foreach(report => println(ComparisonReportFormatter.format(report)))
    )
  }

  def compare(
               wkt: String,
               targetReductionPercent: Double,
               config: BenchmarkConfig = BenchmarkConfig()
             ): SimplificationComparisonReport = {
    if (!targetReductionPercent.isFinite || targetReductionPercent < 0.0 || targetReductionPercent > 100.0)
      return inputFailure(wkt, targetReductionPercent, config,
        s"Invalid target reduction percent: $targetReductionPercent; expected a finite value in [0, 100]")

    val original = try BenchmarkGeometry.parse(wkt) catch {
      case NonFatal(e) => return inputFailure(wkt, targetReductionPercent, config,
        s"Invalid input WKT: ${e.getClass.getSimpleName}: ${e.getMessage}")
    }
    if (!BenchmarkGeometry.isSupported(original))
      return inputFailure(wkt, targetReductionPercent, config,
        s"Unsupported input geometry type: ${original.getGeometryType}")
    if (!original.isValid)
      return inputFailure(wkt, targetReductionPercent, config, "Invalid input geometry according to JTS")
    GeometryValidator.numericValidationError(original).foreach { error =>
      return inputFailure(wkt, targetReductionPercent, config, s"Input geometry is outside the supported numeric domain: $error")
    }

    val originalMetrics = Some(GeometryMetricsCalculator.calculate(original, original, config))

    val adaptiveStart = System.nanoTime()
    val adaptiveAttempt = try {
      val report = AdaptiveSimplifier.simplifyWithReport(wkt, targetReductionPercent)
      val elapsed = elapsedMillis(adaptiveStart)
      val geometry = BenchmarkGeometry.parse(report.wkt)
      Right(AlgorithmBenchmarkResult(
        algorithm = "adaptive",
        wkt = Some(report.wkt),
        metrics = Some(GeometryMetricsCalculator.calculate(original, geometry, config)),
        runtimeMillis = Some(elapsed),
        selectedTolerance = Some(report.threshold),
        requestedReductionDifferencePercent = Some(math.abs(report.achievedReductionPercent - targetReductionPercent)),
        error = None,
        fallbackToOriginal = Some(report.fallbackToOriginal),
        adaptiveAttempts = Some(report.attempts),
        adaptiveRejectedCandidates = report.rejectedCandidates
      ))
    } catch {
      case NonFatal(e) => Left(errorResult("adaptive", elapsedMillis(adaptiveStart), e))
    }

    val adaptive = adaptiveAttempt.fold(identity, identity)
    val dpGoal = adaptive.metrics.map(_.actualReductionPercent).getOrElse(targetReductionPercent)
    val searchStart = System.nanoTime()
    val dp = try {
      val matched = DouglasPeuckerMatcher.search(original, dpGoal, config)
      val searchElapsed = elapsedMillis(searchStart)
      val executionStart = System.nanoTime()
      val selectedGeometry = DouglasPeuckerSimplifier.simplify(original, matched.tolerance)
      val executionElapsed = elapsedMillis(executionStart)
      val resultWkt = BenchmarkGeometry.write(selectedGeometry)
      val actualReduction = if (original.getNumPoints == 0) 0.0
      else 100.0 * (original.getNumPoints - selectedGeometry.getNumPoints) / original.getNumPoints
      AlgorithmBenchmarkResult(
        algorithm = "douglas_peucker",
        wkt = Some(resultWkt),
        metrics = Some(GeometryMetricsCalculator.calculate(original, selectedGeometry, config)),
        runtimeMillis = Some(executionElapsed),
        selectedTolerance = Some(matched.tolerance),
        requestedReductionDifferencePercent = Some(math.abs(actualReduction - targetReductionPercent)),
        error = None,
        toleranceSearchRuntimeMillis = Some(searchElapsed)
      )
    } catch {
      case NonFatal(e) => errorResult("douglas_peucker", elapsedMillis(searchStart), e, searchFailure = true)
    }

    val pairDifference = for (a <- adaptive.metrics; d <- dp.metrics)
      yield math.abs(a.actualReductionPercent - d.actualReductionPercent)
    val isMatched = matchEligible(adaptive, dp, pairDifference, config)
    SimplificationComparisonReport(
      originalWkt = wkt,
      targetReductionPercent = targetReductionPercent,
      originalMetrics = originalMetrics,
      adaptive = adaptive,
      douglasPeucker = dp,
      matched = isMatched,
      achievedReductionDifferencePercent = pairDifference,
      inputError = None,
      config = config
    )
  }

  /**
   * Projects a successful report to ordered JTS points without rerunning either algorithm.
   * Parts are flattened in component/shell/hole order and ring-closing point occurrences are kept.
   * Use the report's geometric metrics for quality evaluation; this flattened view is primarily for
   * inspecting retained vertices and ordered sequence equality.
   */
  def pointSequences(report: SimplificationComparisonReport): Either[String, ComparisonPointSequences] = {
    val error = report.inputError.orElse(report.adaptive.error).orElse(report.douglasPeucker.error)
    error match {
      case Some(message) => Left(message)
      case None =>
        (report.adaptive.wkt, report.douglasPeucker.wkt) match {
          case (Some(adaptiveWkt), Some(dpWkt)) =>
            try Right(ComparisonPointSequences(
              original = BenchmarkGeometry.points(BenchmarkGeometry.parse(report.originalWkt)),
              adaptive = BenchmarkGeometry.points(BenchmarkGeometry.parse(adaptiveWkt)),
              douglasPeucker = BenchmarkGeometry.points(BenchmarkGeometry.parse(dpWkt))
            ))
            catch {
              case NonFatal(e) => Left(s"Could not parse comparison WKT: ${e.getClass.getSimpleName}: ${e.getMessage}")
            }
          case _ => Left("One or both algorithm WKT results are unavailable")
        }
    }
  }

  /** Ordered XY equality for point projections. */
  def samePointSequence(left: Seq[Point], right: Seq[Point], tolerance: Double = 1e-12): Boolean = {
    require(tolerance.isFinite && tolerance >= 0.0, "tolerance must be finite and non-negative")
    left.length == right.length && left.iterator.zip(right.iterator).forall {
      case (a, b) => a.equalsExact(b, tolerance)
    }
  }

  private[benchmark] def matchEligible(
                                        adaptive: AlgorithmBenchmarkResult,
                                        dp: AlgorithmBenchmarkResult,
                                        difference: Option[Double],
                                        config: BenchmarkConfig
                                      ): Boolean =
    adaptive.error.isEmpty && dp.error.isEmpty && !adaptive.fallbackToOriginal.contains(true) &&
      difference.exists(_ <= config.reductionMatchTolerancePercent)

  private def inputFailure(wkt: String, target: Double, config: BenchmarkConfig, message: String) =
    SimplificationComparisonReport(wkt, target, None,
      emptyError("adaptive", message), emptyError("douglas_peucker", message),
      matched = false, None, Some(message), config)

  private def emptyError(algorithm: String, message: String) =
    AlgorithmBenchmarkResult(algorithm, None, None, None, None, None, Some(message))

  private def errorResult(algorithm: String, elapsed: Double, e: Throwable, searchFailure: Boolean = false) =
    AlgorithmBenchmarkResult(algorithm, None, None, if (searchFailure) None else Some(elapsed), None, None,
      Some(s"${e.getClass.getSimpleName}: ${e.getMessage}"),
      toleranceSearchRuntimeMillis = if (searchFailure) Some(elapsed) else None)

  private def elapsedMillis(start: Long): Double = (System.nanoTime() - start) / 1000000.0
}
