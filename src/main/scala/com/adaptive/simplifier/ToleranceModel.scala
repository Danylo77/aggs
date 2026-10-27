package com.adaptive.simplifier

/**
  * Maps complexity scores c_i in [0, 1] to dimensionless tolerance multipliers:
  *
  *   m_i = spread^(1 - 2 c_i)   (c = 0 -> spread, c = 0.5 -> 1, c = 1 -> 1/spread)
  *
  * The log-multipliers are smoothed with a moving average (cyclic for rings), so the tolerance
  * field varies gradually along the part and there are no abrupt jumps between sections.
  * The local tolerance at vertex i is threshold * m_i, where the threshold is chosen globally
  * from the requested reduction (see AdaptiveEngine).
  */
private[simplifier] object ToleranceModel {

  def multipliers(scores: Array[Double], closed: Boolean, cfg: AdaptiveConfig): Array[Double] = {
    val n = scores.length
    val m = if (closed) n - 1 else n
    val out = Array.fill(n)(1.0)
    if (m <= 0) return out

    require(cfg.toleranceSpread.isFinite && cfg.toleranceSpread >= 1.0,
      "toleranceSpread must be finite and at least 1")
    require(cfg.smoothingRadius >= 0, "smoothingRadius must be non-negative")

    val logSpread = math.log(cfg.toleranceSpread)
    val raw = Array.tabulate(m) { i =>
      val score = if (scores(i).isFinite) math.max(0.0, math.min(1.0, scores(i))) else 0.5
      logSpread * (1.0 - 2.0 * score)
    }
    val r = cfg.smoothingRadius
    var i = 0
    while (i < m) {
      var s = 0.0
      var c = 0
      var d = -r
      while (d <= r) {
        val j = if (closed) Math.floorMod(i + d, m) else i + d
        if (j >= 0 && j < m) { s += raw(j); c += 1 }
        d += 1
      }
      out(i) = math.exp(s / c)
      i += 1
    }
    if (closed) out(m) = out(0)
    out
  }
}
