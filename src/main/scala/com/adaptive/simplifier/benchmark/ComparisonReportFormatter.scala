package com.adaptive.simplifier.benchmark

/** Readable terminal rendering of an existing comparison report; performs no metric calculations. */
object ComparisonReportFormatter {
  private val Width = 78
  private val MetricWidth = 34
  private val ValueWidth = 20
  private val Rule = "=" * Width
  private val Dash = "-" * Width

  def format(report: SimplificationComparisonReport): String = {
    val out = new StringBuilder
    out.append(Rule).append('\n')
    out.append(center("GEOMETRY COMPARISON", Width)).append('\n')
    out.append(Rule).append('\n')
    out.append(f"Target reduction: ${report.targetReductionPercent}%.2f%%\n")
    out.append(s"Reduction matched: ${report.matched}\n")
    out.append(s"Reduction difference: ${formatPercentagePoints(report.achievedReductionDifferencePercent)}\n")
    out.append("Distance/length unit: input coordinate units; area unit: square input coordinate units\n")
    out.append(s"Mean deviation sampling: ${report.config.meanSamplesPerSegment} uniform sample(s) per segment, both directions\n")

    report.inputError match {
      case Some(error) =>
        out.append(Dash).append('\n')
        out.append(s"INVALID INPUT: $error\n")
        out.append(Rule)
        return out.result()
      case None =>
    }

    out.append('\n')
    out.append(row("Metric", "Adaptive", "Douglas-Peucker")).append('\n')
    out.append(Dash).append('\n')

    val a = report.adaptive
    val d = report.douglasPeucker
    val am = a.metrics
    val dm = d.metrics
    def metric[A](m: Option[GeometryMetrics])(get: GeometryMetrics => A): Option[A] = m.map(get)

    out.append(row("Original vertices", int(metric(am)(_.originalVertexCount)), int(metric(dm)(_.originalVertexCount)))).append('\n')
    out.append(row("Output vertices", int(metric(am)(_.outputVertexCount)), int(metric(dm)(_.outputVertexCount)))).append('\n')
    out.append(row("Removed vertices", int(metric(am)(_.removedVertexCount)), int(metric(dm)(_.removedVertexCount)))).append('\n')
    out.append(row("Actual reduction (%)", percent(metric(am)(_.actualReductionPercent)), percent(metric(dm)(_.actualReductionPercent)))).append('\n')
    out.append(row("DP tolerance", "N/A", number(d.selectedTolerance))).append('\n')
    out.append(row("Hausdorff distance", number(metric(am)(_.symmetricHausdorffDistance).flatten), number(metric(dm)(_.symmetricHausdorffDistance).flatten))).append('\n')
    out.append(row("Mean geometric deviation", number(metric(am)(_.meanGeometricDeviation).flatten), number(metric(dm)(_.meanGeometricDeviation).flatten))).append('\n')
    out.append(row("Original length", number(metric(am)(_.originalLength)), number(metric(dm)(_.originalLength)))).append('\n')
    out.append(row("Simplified length", number(metric(am)(_.outputLength)), number(metric(dm)(_.outputLength)))).append('\n')
    out.append(row("Absolute length difference", number(metric(am)(_.absoluteLengthDifference)), number(metric(dm)(_.absoluteLengthDifference)))).append('\n')
    out.append(row("Length difference (%)", fractionPercent(metric(am)(_.relativeLengthDifference).flatten), fractionPercent(metric(dm)(_.relativeLengthDifference).flatten))).append('\n')
    out.append(row("Original area", number(metric(am)(_.originalArea).flatten), number(metric(dm)(_.originalArea).flatten))).append('\n')
    out.append(row("Simplified area", number(metric(am)(_.outputArea).flatten), number(metric(dm)(_.outputArea).flatten))).append('\n')
    out.append(row("Absolute area difference", number(metric(am)(_.absoluteAreaDifference).flatten), number(metric(dm)(_.absoluteAreaDifference).flatten))).append('\n')
    out.append(row("Area difference (%)", fractionPercent(metric(am)(_.relativeAreaDifference).flatten), fractionPercent(metric(dm)(_.relativeAreaDifference).flatten))).append('\n')
    out.append(row("Symmetric difference area", number(metric(am)(_.symmetricDifferenceArea).flatten), number(metric(dm)(_.symmetricDifferenceArea).flatten))).append('\n')
    out.append(row("Max perpendicular deviation", number(metric(am)(_.maximumRemovedVertexDeviation).flatten), number(metric(dm)(_.maximumRemovedVertexDeviation).flatten))).append('\n')
    out.append(row("Output valid", bool(metric(am)(_.valid)), bool(metric(dm)(_.valid)))).append('\n')
    out.append(row("Geometry type preserved", bool(metric(am)(_.structure.geometryTypePreserved)), bool(metric(dm)(_.structure.geometryTypePreserved)))).append('\n')
    out.append(row("Component count preserved", bool(metric(am)(_.structure.componentCountPreserved)), bool(metric(dm)(_.structure.componentCountPreserved)))).append('\n')
    out.append(row("Hole count preserved", applicableBool(am, _.structure.holeCountsPreserved, polygonOnly = true), applicableBool(dm, _.structure.holeCountsPreserved, polygonOnly = true))).append('\n')
    out.append(row("Endpoints preserved", optionalBool(metric(am)(_.structure.endpointsPreserved).flatten), optionalBool(metric(dm)(_.structure.endpointsPreserved).flatten))).append('\n')
    out.append(row("Ring closure preserved", optionalBool(metric(am)(_.structure.ringsClosed).flatten), optionalBool(metric(dm)(_.structure.ringsClosed).flatten))).append('\n')
    out.append(row("Original coordinates preserved", bool(metric(am)(_.structure.outputUsesOnlyInputVertices)), bool(metric(dm)(_.structure.outputUsesOnlyInputVertices)))).append('\n')
    out.append(row("Runtime (ms)", milliseconds(a.runtimeMillis), milliseconds(d.runtimeMillis))).append('\n')
    out.append(row("Tolerance search runtime (ms)", "N/A", milliseconds(d.toleranceSearchRuntimeMillis))).append('\n')
    out.append(row("Adaptive fallback", a.fallbackToOriginal.map(_.toString).getOrElse("N/A"), "N/A")).append('\n')
//    println("Adaptive WKT: " + a.wkt.getOrElse("N/A"))
//    println("Douglas-Peucker WKT: " + d.wkt.getOrElse("N/A"))

    out.append(Dash).append('\n')
    out.append("Interpretation (lower error/distortion is better):\n")
    out.append(interpret("Hausdorff distance", am.flatMap(_.symmetricHausdorffDistance), dm.flatMap(_.symmetricHausdorffDistance))).append('\n')
    out.append(interpret("Mean geometric deviation", am.flatMap(_.meanGeometricDeviation), dm.flatMap(_.meanGeometricDeviation))).append('\n')
    out.append(interpret("Relative length distortion", am.flatMap(_.relativeLengthDifference), dm.flatMap(_.relativeLengthDifference))).append('\n')
    if (am.flatMap(_.relativeAreaDifference).nonEmpty || dm.flatMap(_.relativeAreaDifference).nonEmpty)
      out.append(interpret("Relative area distortion", am.flatMap(_.relativeAreaDifference), dm.flatMap(_.relativeAreaDifference))).append('\n')
    out.append("No overall winner is declared; metrics measure different aspects of geometry quality.\n")

    if (!report.matched)
      out.append("WARNING: achieved reductions are not sufficiently matched; quality comparison requires caution.\n")
    if (a.fallbackToOriginal.contains(true))
      out.append("WARNING: adaptive simplification fell back to the original geometry.\n")
    appendDiagnostics(out, "Adaptive", a)
    appendDiagnostics(out, "Douglas-Peucker", d)
    out.append(Rule)
    out.result()
  }

  private def appendDiagnostics(out: StringBuilder, name: String, result: AlgorithmBenchmarkResult): Unit = {
    result.error.foreach(e => out.append(s"$name error: $e\n"))
    result.metrics.toSeq.flatMap(_.warnings).foreach(w => out.append(s"$name metric warning: $w\n"))
  }

  private def interpret(label: String, adaptive: Option[Double], dp: Option[Double]): String =
    (adaptive, dp) match {
      case (Some(a), Some(d)) if nearlyEqual(a, d) => s"- $label: equal within display precision."
      case (Some(a), Some(d)) if a < d => s"- $label: Adaptive is lower (${formatDouble(a)} vs ${formatDouble(d)})."
      case (Some(a), Some(d)) => s"- $label: Douglas-Peucker is lower (${formatDouble(d)} vs ${formatDouble(a)})."
      case _ => s"- $label: N/A."
    }

  private def applicableBool(metrics: Option[GeometryMetrics], get: GeometryMetrics => Boolean, polygonOnly: Boolean): String =
    metrics match {
      case Some(m) if !polygonOnly || m.originalArea.nonEmpty => get(m).toString
      case _ => "N/A"
    }

  private def row(label: String, adaptive: String, dp: String): String =
    padRight(truncate(label, MetricWidth), MetricWidth) +
      padLeft(truncate(adaptive, ValueWidth), ValueWidth) +
      padLeft(truncate(dp, ValueWidth), ValueWidth)

  private def padRight(value: String, width: Int): String = value + " " * math.max(0, width - value.length)
  private def padLeft(value: String, width: Int): String = " " * math.max(0, width - value.length) + value

  private def truncate(value: String, width: Int): String =
    if (value.length <= width) value else value.take(width - 1) + "…"

  private def center(value: String, width: Int): String = {
    val left = math.max(0, (width - value.length) / 2)
    " " * left + value
  }

  private def int(value: Option[Int]): String = value.map(_.toString).getOrElse("N/A")
  private def bool(value: Option[Boolean]): String = value.map(_.toString).getOrElse("N/A")
  private def optionalBool(value: Option[Boolean]): String = value.map(_.toString).getOrElse("N/A")
  private def number(value: Option[Double]): String = value.filter(_.isFinite).map(formatDouble).getOrElse("N/A")
  private def percent(value: Option[Double]): String = value.filter(_.isFinite).map(v => f"$v%.4f%%").getOrElse("N/A")
  private def fractionPercent(value: Option[Double]): String = value.filter(_.isFinite).map(v => f"${v * 100.0}%.4f%%").getOrElse("N/A")
  private def milliseconds(value: Option[Double]): String = value.filter(_.isFinite).map(v => f"$v%.3f").getOrElse("N/A")
  private def formatPercentagePoints(value: Option[Double]): String = value.filter(_.isFinite).map(v => f"$v%.4f percentage points").getOrElse("N/A")

  private def formatDouble(value: Double): String = {
    val magnitude = math.abs(value)
    if (magnitude != 0.0 && (magnitude < 1e-4 || magnitude >= 1e7)) f"$value%.6e"
    else f"$value%.6f"
  }

  private def nearlyEqual(a: Double, b: Double): Boolean = {
    val scale = math.max(math.abs(a), math.abs(b))
    math.abs(a - b) <= math.max(1e-15, 1e-6 * scale)
  }
}
