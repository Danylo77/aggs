# Adaptive Geospatial Simplifier

Scala/JTS library that simplifies WKT geometries with **locally varying tolerances**. Each line or
ring gets a per-vertex tolerance derived from box-counting fractal estimates and local geometric
features: simpler sections are simplified more aggressively, complex sections are preserved.

Whether this improves on standard Douglas–Peucker is a research hypothesis that has not been
evaluated yet.

## Requirements and build

JDK 11+, Maven 3.6+. Scala 2.13.12 and JTS 1.19.0 are pulled by Maven.

```bash
mvn clean package
```

## Tests

```bash
mvn test
```

The Stage 2 suite covers the public WKT contract, supported and invalid geometry types, percentages,
empty/degenerate/duplicate inputs, structure and validity, adaptive-DP invariants, box-counting and
feature fallbacks, retry/fallback behavior, concurrency, and all records in `wkt_extmples.txt`.

## Usage

```scala
import com.adaptive.simplifier.AdaptiveSimplifier

val simplifiedWkt: String = AdaptiveSimplifier.simplify(
  wkt = "LINESTRING (0 0, 1 0.2, 2 0, 3 1)",
  targetReductionPercent = 40.0
)
```

`AdaptiveSimplifier.simplifyWithReport(wkt, percent)` returns the same WKT plus optional diagnostics
(vertex counts, achieved reduction, threshold, attempts, fallback flag, box-counting coverage).

## Behavior contract

| Input | Behavior |
|---|---|
| `LINESTRING`, `MULTILINESTRING`, `POLYGON`, `MULTIPOLYGON` | simplified |
| `POINT`, `MULTIPOINT`, `GEOMETRYCOLLECTION`, `LINEARRING` | `UnsupportedGeometryTypeException` (nothing meaningful to simplify / mixed structure) |
| null, blank, malformed WKT, unclosed ring | `InvalidWktException` |
| JTS-invalid input (e.g. self-intersecting polygon), non-finite coordinates, or a coordinate extent outside the numerically safe distance range | `InvalidGeometryException`; no automatic repair |
| percent NaN, infinite, outside `[0, 100]` | `InvalidReductionPercentException` |
| empty geometry (`LINESTRING EMPTY`, ...) | returned as empty WKT |
| percent `0` | input returned unchanged |
| no valid candidate after retries | input returned unchanged (fallback) |

All exceptions extend `SimplificationInputException` (an `IllegalArgumentException`).

- **Units:** WKT carries no CRS. All distances are planar, in input coordinate units. No geodesic
  calculation or CRS transformation is performed.
- **Output:** 2D WKT. Z/M ordinates are not written.
- **Structure:** endpoints of lines, ring closure, every ring and hole, and every component are kept.
  Output vertices are a subset of the input vertices (no new coordinates are created).
- **Duplicates:** consecutive duplicate points are removed. They count toward the reduction.
- **Minimal parts:** 2-point lines and triangle rings are kept as-is. Rings never go below a triangle.
- **The target is approximate.** Mandatory vertices and validity retries can make the achieved
  reduction lower than requested. Score ties can make it slightly higher.

## Algorithm

1. **Decomposition** (`GeometryParts`): the geometry is split into lines, shells, and holes, then
   rebuilt in the same order.
2. **Local features** (`ComplexityAnalyzer`), all dimensionless in `[0, 1]`:
   - turning angle `(π − interior angle) / π`
   - local deviation `2·maxDist / pathLength` within a ±2-vertex window
   - relative vertex density `r/(1+r)`, where `r = mean segment / local mean segment`
3. **Box-counting** (`BoxCounting`): on overlapping 16-segment windows (cyclic on rings), grids of size `L/2^j` count the
   cells touched by the segments. `D` is the log-log regression slope. Scales finer than half the
   mean segment length are excluded. The estimate is discarded when there are fewer than 8
   segments, fewer than 3 scales, or R² < 0.8. **Fallback:** where no reliable estimate exists, the
   fractal term is dropped and the feature weights are renormalized.
4. **Tolerance field** (`ToleranceModel`): score `c_i` (weighted mean, with weights angle 0.35,
   deviation 0.30, fractal 0.25, density 0.10) maps to the multiplier `m_i = 4^(1 − 2c_i)`, a value
   in `[0.25, 4]`. The multiplier is smoothed in log space over ±2 vertices, so tolerances change
   gradually and there are no seams between sections.
5. **Variable-tolerance Douglas–Peucker** (`VariableToleranceDP`): spans split on the maximum of
   `dist_i / m_i`. At global threshold `t`, every removed vertex lies within its local tolerance
   `t·m_i` of the output segment that replaces it. Kept sets are nested in `t`, so one pass yields a
   per-vertex significance. Lines anchor both endpoints. Rings anchor three spread vertices.
6. **Percent → threshold** (`AdaptiveEngine`): vertices of the whole geometry are ranked by
   significance. The complete equal-significance group whose cumulative count is nearest
   `R ≈ N·p/100` is selected; groups are never split because that would break the local error bound.
   Consequently the achieved percentage can differ from the target, especially for large ties.
7. **Validation and retries** (`GeometryValidator`): a candidate must be JTS-valid and keep its type,
   component count, and hole count. Lineal input that was simple must stay simple. On failure the
   removal count snaps to a strictly smaller complete threshold group near `R·0.7^k`; the last of at
   most 8 attempts performs no ranked removals. If even that cleaned candidate fails, the original
   geometry is returned.

## Stage 3 comparison and benchmarking

The benchmark module is separate from `AdaptiveSimplifier`; it calls the existing public adaptive API
and JTS `DouglasPeuckerSimplifier` without changing either algorithm.

### Single comparison

```scala
import com.adaptive.simplifier.benchmark.{BenchmarkConfig, SimplificationBenchmark}

val report = SimplificationBenchmark.compare(
  "LINESTRING (0 0, 1 0.2, 2 0, 3 1, 4 0.5, 5 0)",
  targetReductionPercent = 40.0,
  config = BenchmarkConfig()
)
```

From PowerShell:

```powershell
mvn -q exec:java "-Dexec.mainClass=com.adaptive.simplifier.benchmark.BenchmarkCli" `
  "-Dexec.args=single 40 LINESTRING (0 0, 1 0.2, 2 0, 3 1, 4 0.5, 5 0)"
```

The `single` command prints the complete aligned comparison table automatically: vertex counts,
rate matching, JTS Hausdorff distance, deterministic bidirectional sampled mean deviation, length and
polygon area distortion, symmetric-difference area, reliable removed-vertex deviation, structural
checks, runtimes, warnings, and a per-metric interpretation. Unavailable values are printed as `N/A`.
Distances and lengths are labeled as input coordinate units and areas as square input coordinate units.
No overall winner is inferred from the table.

The report object contains original/adaptive/DP metrics and WKT, achieved reductions, runtimes, selected DP
tolerance, adaptive retry/fallback diagnostics, errors, and a `matched` flag. For point-level inspection
without comparing WKT text, project an existing report to ordered JTS points:

```scala
val points = SimplificationBenchmark.pointSequences(report)
points.foreach { p =>
  println(s"original=${p.original.size}, adaptive=${p.adaptive.size}, dp=${p.douglasPeucker.size}")
  println(SimplificationBenchmark.samePointSequence(p.adaptive, p.douglasPeucker))
}
```

The returned `ComparisonPointSequences` contains flattened `Seq[Point]` values in component,
shell, and hole order, including ring-closing occurrences. Equal point sequences are possible and
legitimate; `matched` means similar achieved reduction, not identical retained points. Use the
geometry metrics—not point equality alone—to evaluate quality.

### Fair reduction matching

The primary comparison runs the adaptive API first. Because its target is approximate, the DP search
uses the **adaptive achieved reduction** as its effective goal. It evaluates tolerance zero and a
bounded maximum (the envelope diagonal by default). The configured probe budget (30 by default) is
split between guided bisection, linear/logarithmic probes over the entire range, and local refinement
around the best candidate. All evaluated candidates remain eligible, so polygon vertex-count reversals
do not cause a whole tolerance region to be permanently discarded.
The candidate among all evaluated tolerances with the closest achieved reduction is selected; a tie
uses the smaller tolerance. `BenchmarkConfig` controls iteration count, an optional absolute maximum
tolerance, numerical stopping epsilon, and the accepted reduction-rate difference.

Vertex counts are discrete, and JTS polygon validity handling can introduce plateaus or jumps. Thus an
exact match is not promised. `matched` is true only when the absolute adaptive-vs-DP achieved reduction
difference is at most `reductionMatchTolerancePercent` (2 percentage points by default), neither
algorithm failed, and the adaptive method did not fall back to the original. Unmatched or fallback
rows retain all measurements but should not be interpreted as a direct quality comparison.

`runtime_ms` measures one execution at the selected tolerance for DP and one public API execution for
the adaptive algorithm. `tolerance_search_runtime_ms` separately records the entire DP search cost.
These simple wall-clock measurements are diagnostic, not controlled JVM microbenchmarks.

### Batch benchmark

The input format is one WKT geometry per nonblank line. Default targets are 10, 25, 40, 50, 70, and 90%.

```powershell
mvn -q exec:java "-Dexec.mainClass=com.adaptive.simplifier.benchmark.BenchmarkCli" `
  "-Dexec.args=batch wkt_examples.txt benchmark-results 10,25,40,50,70,90"
```

A small deterministic input is included as `benchmark_examples.wkt`:

```powershell
mvn -q exec:java "-Dexec.mainClass=com.adaptive.simplifier.benchmark.BenchmarkCli" `
  "-Dexec.args=batch benchmark_examples.wkt benchmark-example-results 40"
```

The runner continues after malformed, unsupported, or invalid records and writes their errors. Stable
IDs combine the one-based input position with the first 12 hexadecimal SHA-256 digits of the WKT.
It creates:

- `algorithm_metrics.csv`: one row per input, target, and algorithm (`original`, `adaptive`,
  `douglas_peucker`); non-applicable values are `N/A`. Adaptive attempts, fallback, and rejected
  candidates are retained.
- `paired_comparisons.csv`: rate-match status, achieved reductions, selected DP tolerance, timings,
  adaptive fallback/rejection context, errors, and all relevant deterministic configuration.
- `geometries.csv`: original and actual output WKTs plus match/fallback context for safe visualization,
  kept separate to avoid repeating large WKT in every metric row.

Abridged output from the included 40% example (runtime columns omitted because they vary):

```csv
input_id,target_reduction_percent,matched,achieved_reduction_difference_percent,adaptive_actual_reduction_percent,dp_actual_reduction_percent,selected_dp_tolerance
record-00001-5e7a8ad36a2c,40.0,true,0.0,33.333333333333336,33.333333333333336,0.20003630324473648
record-00002-adaf7f2c7cfd,40.0,true,0.0,23.076923076923077,23.076923076923077,1.0001534478354357
```

### Metric definitions

All distances, lengths, tolerances, and areas use the planar input coordinate units. WKT contains no
CRS. In particular, `wkt_examples.txt` contains EPSG:4326 longitude/latitude coordinates, so distance
values are in degrees and areas in square degrees; they are not geodesic measurements.

| Metric | Definition / limitation |
|---|---|
| vertices | JTS `getNumPoints`; ring-closing coordinates are included |
| removed vertices / reduction | `originalCount - outputCount`; percentage divides by original count, or 0 for an empty original |
| length difference | absolute `|outputLength-originalLength|`; relative value divides by absolute original length and is `N/A` at zero |
| area difference | same definition using JTS planar area; only for polygonal geometries |
| symmetric Hausdorff | JTS densified discrete symmetric Hausdorff distance; polygon boundaries are compared, with densification fraction 0.25 by default |
| mean geometric deviation | symmetric arithmetic mean of nearest-linework distances; each segment is sampled uniformly (two samples per segment by default), in both original→output and output→original directions |
| maximum removed-vertex deviation | maximum perpendicular distance from an omitted input vertex to its corresponding replacement output segment; `N/A` if part structure/order or input-vertex correspondence cannot be established reliably |
| symmetric difference area | JTS polygon overlay area of `original △ output`; only when both values are valid polygonal geometries |
| structural diagnostics | validity, type/component/hole preservation, input-vertex-only output, open-line endpoints, and ring closure |

Hausdorff, mean deviation, removed-vertex deviation, length, and area describe different aspects of
quality; no single metric is treated as a complete shape-preservation measure. Discrete Hausdorff and
sampled mean deviation are approximations controlled by benchmark configuration. Overlay failures are
reported as metric warnings rather than changing algorithm output.

### Visual inspection

`visualize_benchmark.py` reads an actual row from `geometries.csv`, overlays all three geometries, and
marks original vertices removed by each method.

```powershell
python -m pip install matplotlib shapely
python visualize_benchmark.py benchmark-results/geometries.csv `
  --input-id record-00001-5e7a8ad36a2c --target 40 --output comparison.png
```

Omit `--output` for an interactive window. By default the script refuses unmatched or adaptive-fallback
rows; pass `--allow-unmatched` only for diagnostic inspection. Removed points are matched by part and
coordinate occurrence, so shared multipart coordinates do not hide removals. The script does not
generate or substitute geometries.

## Selected OSM showcase cases

`wkt_best_examples.txt` contains three real OSM-derived geometries selected from the fixed
`wkt_examples.txt` population. The selection was performed only after benchmarking all 61 records at
targets `10,25,40,50,70,90`. The frozen eligibility rule required matched achieved reductions, valid
and structurally preserved output, no adaptive fallback/errors/warnings, at least 5% lower adaptive
Hausdorff and mean deviation, and no worse geometry-specific length/area guards. At most one target was
selected per input. `select_best_examples.py` implements the rule, and
`wkt_best_examples_selection.csv` records the measurements and source groups.

Run all three at the two required target levels:

```powershell
mvn -q exec:java "-Dexec.mainClass=com.adaptive.simplifier.benchmark.BenchmarkCli" `
  "-Dexec.args=batch wkt_best_examples.txt best-example-results 10,40"
```

Inspect these exact rows:

| File record | OSM source group | Target | Result ID | Measured result |
|---|---|---:|---|---|
| 1 | Lviv railway lines | 40% | `record-00001-aae85b5057cf` | both reduced 40.23%; adaptive Hausdorff 87.75% lower and mean deviation 44.55% lower |
| 2 | Lviv city areas | 10% | `record-00002-3402aa2f3892` | both reduced 10.34%; adaptive Hausdorff 32.38% lower, mean deviation 31.78% lower, and polygon area guards were better |
| 3 | Amsterdam waterways | 40% | `record-00003-57bddba92499` | both reduced 38.46%; adaptive Hausdorff 44.38% lower and mean deviation 27.84% lower |

Visualize them with:

```powershell
python visualize_benchmark.py best-example-results/geometries.csv --input-id record-00001-aae85b5057cf --target 40 --output best-1.png
python visualize_benchmark.py best-example-results/geometries.csv --input-id record-00002-3402aa2f3892 --target 10 --output best-2.png
python visualize_benchmark.py best-example-results/geometries.csv --input-id record-00003-57bddba92499 --target 40 --output best-3.png
```

The supported conclusion is: **for these three selected input-target pairs, adaptive simplification
had lower measured error under the stated metrics at equal achieved vertex reduction.** They are
outcome-selected showcases, so they do not establish that adaptive simplification is generally better
than Douglas–Peucker. General superiority requires reporting the full benchmark population, including
wins, losses, unmatched rows, and effect-size distributions.

To reproduce selection after rerunning the full benchmark:

```powershell
python select_best_examples.py benchmark-results
```

## Known limitations

- Weights, window sizes, and the multiplier spread are heuristic defaults and have not been calibrated.
- Box counting samples segments, so cell counts are approximate.
- Polygons in a `MULTIPOLYGON` and adjacent features are simplified independently. Shared boundaries
  are not kept coherent. Overlaps inside one `MULTIPOLYGON` are caught by validation, which triggers
  a retry.
- The Douglas–Peucker pass is O(n²) in the worst case.
