package com.adaptive.simplifier.model

/**
  * Optional diagnostics for one simplification run. The primary API returns only `wkt`.
  *
  * @param wkt                      resulting geometry as WKT (2D)
  * @param originalVertexCount      input vertex count (JTS `getNumPoints`, ring closing points included)
  * @param resultVertexCount        output vertex count, counted the same way
  * @param targetReductionPercent   requested reduction
  * @param achievedReductionPercent actual reduction
  * @param threshold                normalized threshold t; the local tolerance at vertex i is t * m_i.
  *                                 `-Infinity` means no vertex was removed by the threshold.
  * @param attempts                 number of candidates built (initial attempt + validity retries)
  * @param fallbackToOriginal       true if no candidate passed validation and the input was returned
  * @param fractalCoverage          fraction of analysed vertices that received a reliable box-counting estimate
  * @param rejectedCandidates       reasons why earlier candidates were rejected
  */
final case class SimplificationReport(
    wkt: String,
    originalVertexCount: Int,
    resultVertexCount: Int,
    targetReductionPercent: Double,
    achievedReductionPercent: Double,
    threshold: Double,
    attempts: Int,
    fallbackToOriginal: Boolean,
    fractalCoverage: Double,
    rejectedCandidates: Seq[String]
)
