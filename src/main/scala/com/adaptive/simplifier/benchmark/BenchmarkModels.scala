package com.adaptive.simplifier.benchmark

/** Deterministic settings for one comparison. Distances remain in the input WKT coordinate units. */
final case class BenchmarkConfig(
    dpMaxIterations: Int = 30,
    dpMaxTolerance: Option[Double] = None,
    dpToleranceEpsilon: Double = 1e-12,
    reductionMatchTolerancePercent: Double = 2.0,
    hausdorffDensifyFraction: Double = 0.25,
    meanSamplesPerSegment: Int = 2
) {
  require(dpMaxIterations >= 1, "dpMaxIterations must be positive")
  require(dpMaxTolerance.forall(v => v.isFinite && v >= 0.0), "dpMaxTolerance must be finite and non-negative")
  require(dpToleranceEpsilon.isFinite && dpToleranceEpsilon > 0.0, "dpToleranceEpsilon must be finite and positive")
  require(reductionMatchTolerancePercent.isFinite && reductionMatchTolerancePercent >= 0.0,
    "reductionMatchTolerancePercent must be finite and non-negative")
  require(hausdorffDensifyFraction.isFinite && hausdorffDensifyFraction > 0.0 && hausdorffDensifyFraction <= 1.0,
    "hausdorffDensifyFraction must be in (0, 1]")
  require(meanSamplesPerSegment >= 1, "meanSamplesPerSegment must be positive")
}

/** Structural checks that diagnose changes without modifying either algorithm's output. */
final case class StructuralDiagnostics(
    geometryTypePreserved: Boolean,
    componentCountPreserved: Boolean,
    holeCountsPreserved: Boolean,
    outputUsesOnlyInputVertices: Boolean,
    endpointsPreserved: Option[Boolean],
    ringsClosed: Option[Boolean]
)

/**
  * Quality metrics for an output relative to the original geometry.
  * Option is used for non-applicable or unavailable values (rendered as N/A in CSV).
  */
final case class GeometryMetrics(
    geometryType: String,
    originalVertexCount: Int,
    outputVertexCount: Int,
    removedVertexCount: Int,
    actualReductionPercent: Double,
    componentCount: Int,
    polygonHoleCount: Int,
    valid: Boolean,
    originalLength: Double,
    outputLength: Double,
    absoluteLengthDifference: Double,
    relativeLengthDifference: Option[Double],
    originalArea: Option[Double],
    outputArea: Option[Double],
    absoluteAreaDifference: Option[Double],
    relativeAreaDifference: Option[Double],
    symmetricHausdorffDistance: Option[Double],
    meanGeometricDeviation: Option[Double],
    maximumRemovedVertexDeviation: Option[Double],
    symmetricDifferenceArea: Option[Double],
    structure: StructuralDiagnostics,
    warnings: Seq[String]
)

/** One algorithm result. Runtime covers simplification/search only, not parsing or metric calculation. */
final case class AlgorithmBenchmarkResult(
    algorithm: String,
    wkt: Option[String],
    metrics: Option[GeometryMetrics],
    runtimeMillis: Option[Double],
    selectedTolerance: Option[Double],
    requestedReductionDifferencePercent: Option[Double],
    error: Option[String],
    fallbackToOriginal: Option[Boolean] = None,
    adaptiveAttempts: Option[Int] = None,
    adaptiveRejectedCandidates: Seq[String] = Nil,
    toleranceSearchRuntimeMillis: Option[Double] = None
)

/** Paired primary comparison. `matched` is based on achieved adaptive-vs-DP reduction difference. */
final case class SimplificationComparisonReport(
    originalWkt: String,
    targetReductionPercent: Double,
    originalMetrics: Option[GeometryMetrics],
    adaptive: AlgorithmBenchmarkResult,
    douglasPeucker: AlgorithmBenchmarkResult,
    matched: Boolean,
    achievedReductionDifferencePercent: Option[Double],
    inputError: Option[String],
    config: BenchmarkConfig
)

final case class ComparisonPointSequences(
    original: Seq[org.locationtech.jts.geom.Point],
    adaptive: Seq[org.locationtech.jts.geom.Point],
    douglasPeucker: Seq[org.locationtech.jts.geom.Point]
)

final case class BatchBenchmarkSummary(
    inputRecords: Int,
    targetCount: Int,
    comparisonCount: Int,
    failureCount: Int,
    metricsCsv: java.nio.file.Path,
    comparisonsCsv: java.nio.file.Path,
    geometriesCsv: java.nio.file.Path
)
