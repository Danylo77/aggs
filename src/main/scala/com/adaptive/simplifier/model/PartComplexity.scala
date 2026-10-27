package com.adaptive.simplifier.model

/**
  * Complexity analysis of one part (a line or a ring).
  *
  * @param scores                 per-vertex complexity in [0, 1], same length as the part's coordinates
  *                               (for rings the closing point repeats the score of the first vertex)
  * @param fractalCoveredVertices distinct vertices that received a reliable box-counting estimate
  * @param distinctVertices       distinct vertices analysed (ring closing point excluded)
  */
private[simplifier] final case class PartComplexity(
    scores: Array[Double],
    fractalCoveredVertices: Int,
    distinctVertices: Int
)
