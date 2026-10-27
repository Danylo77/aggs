package com.adaptive.simplifier.benchmark

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}

private[benchmark] object BenchmarkCsv {
  val MetricsHeader: Seq[String] = Seq(
    "input_id", "target_reduction_percent", "algorithm", "status", "error",
    "runtime_ms", "tolerance_search_runtime_ms", "selected_tolerance", "requested_reduction_difference_percent",
    "geometry_type", "original_vertex_count", "output_vertex_count", "removed_vertex_count",
    "actual_reduction_percent", "component_count", "polygon_hole_count", "valid",
    "original_length", "output_length", "absolute_length_difference", "relative_length_difference",
    "original_area", "output_area", "absolute_area_difference", "relative_area_difference",
    "symmetric_hausdorff_distance", "mean_geometric_deviation", "maximum_removed_vertex_deviation",
    "symmetric_difference_area", "geometry_type_preserved", "component_count_preserved",
    "hole_counts_preserved", "output_uses_only_input_vertices", "endpoints_preserved", "rings_closed",
    "fallback_to_original", "adaptive_attempts", "adaptive_rejected_candidates", "warnings"
  )

  val ComparisonsHeader: Seq[String] = Seq(
    "input_id", "target_reduction_percent", "matched", "achieved_reduction_difference_percent",
    "adaptive_actual_reduction_percent", "dp_actual_reduction_percent", "adaptive_target_difference_percent",
    "dp_target_difference_percent", "selected_dp_tolerance", "adaptive_runtime_ms", "dp_runtime_ms",
    "dp_tolerance_search_runtime_ms",
    "adaptive_error", "dp_error", "input_error", "adaptive_fallback_to_original", "adaptive_attempts",
    "adaptive_rejected_candidates", "dp_max_iterations", "dp_max_tolerance",
    "dp_tolerance_epsilon", "match_tolerance_percent", "hausdorff_densify_fraction", "mean_samples_per_segment"
  )

  val GeometriesHeader: Seq[String] = Seq(
    "input_id", "target_reduction_percent", "matched", "achieved_reduction_difference_percent",
    "adaptive_fallback_to_original", "original_wkt", "adaptive_wkt", "douglas_peucker_wkt",
    "adaptive_error", "dp_error"
  )

  def write(path: Path, header: Seq[String], rows: Seq[Seq[String]]): Unit = {
    Files.createDirectories(path.getParent)
    val content = (header +: rows).map(row => row.map(escape).mkString(",")).mkString("\n") + "\n"
    Files.write(path, content.getBytes(StandardCharsets.UTF_8),
      StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
  }

  def metricsRow(id: String, target: Double, result: AlgorithmBenchmarkResult): Seq[String] = {
    val m = result.metrics
    Seq(
      id, number(target), result.algorithm, if (result.error.isEmpty) "ok" else "error", result.error.getOrElse(""),
      optional(result.runtimeMillis), optional(result.toleranceSearchRuntimeMillis), optional(result.selectedTolerance), optional(result.requestedReductionDifferencePercent),
      m.map(_.geometryType).getOrElse("N/A"), m.map(_.originalVertexCount.toString).getOrElse("N/A"),
      m.map(_.outputVertexCount.toString).getOrElse("N/A"), m.map(_.removedVertexCount.toString).getOrElse("N/A"),
      m.map(x => number(x.actualReductionPercent)).getOrElse("N/A"), m.map(_.componentCount.toString).getOrElse("N/A"),
      m.map(_.polygonHoleCount.toString).getOrElse("N/A"), m.map(_.valid.toString).getOrElse("N/A"),
      m.map(x => number(x.originalLength)).getOrElse("N/A"), m.map(x => number(x.outputLength)).getOrElse("N/A"),
      m.map(x => number(x.absoluteLengthDifference)).getOrElse("N/A"), m.flatMap(_.relativeLengthDifference).map(number).getOrElse("N/A"),
      m.flatMap(_.originalArea).map(number).getOrElse("N/A"), m.flatMap(_.outputArea).map(number).getOrElse("N/A"),
      m.flatMap(_.absoluteAreaDifference).map(number).getOrElse("N/A"), m.flatMap(_.relativeAreaDifference).map(number).getOrElse("N/A"),
      m.flatMap(_.symmetricHausdorffDistance).map(number).getOrElse("N/A"),
      m.flatMap(_.meanGeometricDeviation).map(number).getOrElse("N/A"),
      m.flatMap(_.maximumRemovedVertexDeviation).map(number).getOrElse("N/A"),
      m.flatMap(_.symmetricDifferenceArea).map(number).getOrElse("N/A"),
      m.map(_.structure.geometryTypePreserved.toString).getOrElse("N/A"),
      m.map(_.structure.componentCountPreserved.toString).getOrElse("N/A"),
      m.map(_.structure.holeCountsPreserved.toString).getOrElse("N/A"),
      m.map(_.structure.outputUsesOnlyInputVertices.toString).getOrElse("N/A"),
      m.flatMap(_.structure.endpointsPreserved).map(_.toString).getOrElse("N/A"),
      m.flatMap(_.structure.ringsClosed).map(_.toString).getOrElse("N/A"),
      result.fallbackToOriginal.map(_.toString).getOrElse("N/A"),
      result.adaptiveAttempts.map(_.toString).getOrElse("N/A"),
      result.adaptiveRejectedCandidates.mkString(" | "),
      m.map(_.warnings.mkString(" | ")).getOrElse("")
    )
  }

  def originalResult(metrics: Option[GeometryMetrics], target: Double): AlgorithmBenchmarkResult =
    AlgorithmBenchmarkResult("original", None, metrics, None, None, Some(math.abs(target)),
      if (metrics.isDefined) None else Some("original metrics unavailable"))

  def comparisonRow(id: String, r: SimplificationComparisonReport): Seq[String] = Seq(
    id, number(r.targetReductionPercent), r.matched.toString, optional(r.achievedReductionDifferencePercent),
    r.adaptive.metrics.map(x => number(x.actualReductionPercent)).getOrElse("N/A"),
    r.douglasPeucker.metrics.map(x => number(x.actualReductionPercent)).getOrElse("N/A"),
    optional(r.adaptive.requestedReductionDifferencePercent), optional(r.douglasPeucker.requestedReductionDifferencePercent),
    optional(r.douglasPeucker.selectedTolerance), optional(r.adaptive.runtimeMillis), optional(r.douglasPeucker.runtimeMillis),
    optional(r.douglasPeucker.toleranceSearchRuntimeMillis),
    r.adaptive.error.getOrElse(""), r.douglasPeucker.error.getOrElse(""), r.inputError.getOrElse(""),
    r.adaptive.fallbackToOriginal.map(_.toString).getOrElse("N/A"),
    r.adaptive.adaptiveAttempts.map(_.toString).getOrElse("N/A"), r.adaptive.adaptiveRejectedCandidates.mkString(" | "),
    r.config.dpMaxIterations.toString, r.config.dpMaxTolerance.map(number).getOrElse("auto"),
    number(r.config.dpToleranceEpsilon), number(r.config.reductionMatchTolerancePercent),
    number(r.config.hausdorffDensifyFraction), r.config.meanSamplesPerSegment.toString
  )

  def geometryRow(id: String, r: SimplificationComparisonReport): Seq[String] = Seq(
    id, number(r.targetReductionPercent), r.matched.toString, optional(r.achievedReductionDifferencePercent),
    r.adaptive.fallbackToOriginal.map(_.toString).getOrElse("N/A"), r.originalWkt, r.adaptive.wkt.getOrElse(""),
    r.douglasPeucker.wkt.getOrElse(""), r.adaptive.error.getOrElse(""), r.douglasPeucker.error.getOrElse("")
  )

  private def optional(value: Option[Double]): String = value.map(number).getOrElse("N/A")
  private def number(value: Double): String = java.lang.Double.toString(value)
  private def escape(value: String): String =
    if (value.exists(c => c == ',' || c == '"' || c == '\n' || c == '\r')) "\"" + value.replace("\"", "\"\"") + "\""
    else value
}
