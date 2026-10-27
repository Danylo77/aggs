package com.adaptive.simplifier.benchmark

import java.nio.file.Paths

/** Command-line entry point for a single comparison or a WKT-file batch. */
object BenchmarkCli {
  def main(args: Array[String]): Unit = args.toList match {
    case "single" :: target :: wktParts if wktParts.nonEmpty =>
      val report = SimplificationBenchmark.compare(wktParts.mkString(" "), target.toDouble)
      println(ComparisonReportFormatter.format(report))
    case "batch" :: input :: output :: Nil =>
      val summary = BatchBenchmarkRunner.run(Paths.get(input), Paths.get(output))
      printSummary(summary)
    case "batch" :: input :: output :: targets :: Nil =>
      val summary = BatchBenchmarkRunner.run(Paths.get(input), Paths.get(output), parseTargets(targets))
      printSummary(summary)
    case _ =>
      System.err.println("Usage:\n  single <target-percent> <WKT>\n  batch <input-wkt-file> <output-directory> [comma-separated-targets]")
      throw new IllegalArgumentException("Invalid benchmark arguments")
  }

  private def parseTargets(value: String): Seq[Double] = {
    val fields = value.split(",", -1).toSeq
    require(fields.nonEmpty && fields.forall(_.trim.nonEmpty),
      "Target list must contain only comma-separated numbers")
    fields.map(_.trim.toDouble)
  }

  private def printSummary(summary: BatchBenchmarkSummary): Unit = {
    println(s"inputs=${summary.inputRecords}, targets=${summary.targetCount}, comparisons=${summary.comparisonCount}, failures=${summary.failureCount}")
    println(s"metrics=${summary.metricsCsv}")
    println(s"comparisons=${summary.comparisonsCsv}")
    println(s"geometries=${summary.geometriesCsv}")
  }

}
