"""Select transparent adaptive showcase cases from completed benchmark CSV files.

This is an outcome-conditioned showcase selector, not a general superiority test. The fixed rule is:
matched rates; no errors/fallback/warnings; valid, structure-preserving output; positive reduction;
adaptive at least 5% lower on both Hausdorff and sampled mean deviation; no worse maximum removed
vertex deviation when available; and no worse relative length (lines) or relative area plus symmetric
difference area (polygons). Cases rank by the smaller primary proportional improvement, with one target
per input geometry.
"""
from __future__ import annotations

import argparse
import csv
from pathlib import Path

NA = {"", "N/A"}


def number(row: dict[str, str], name: str) -> float | None:
    value = row.get(name, "")
    return None if value in NA else float(value)


def true(row: dict[str, str], name: str) -> bool:
    return row.get(name, "").lower() == "true"


def source_label(input_id: str) -> str:
    index = int(input_id.split("-")[1])
    if index <= 15:
        return "Lviv city areas"
    if index <= 30:
        return "Lviv railway lines"
    if index <= 45:
        return "Venice island areas"
    if index <= 60:
        return "Amsterdam waterways"
    return "Synthesized multipart from OSM selections"


def eligible(pair: dict[str, str], adaptive: dict[str, str], dp: dict[str, str]):
    if not true(pair, "matched") or true(pair, "adaptive_fallback_to_original"):
        return None
    if any(pair.get(k) for k in ("adaptive_error", "dp_error", "input_error")):
        return None
    for row in (adaptive, dp):
        if row.get("status") != "ok" or not true(row, "valid") or row.get("warnings"):
            return None
        for field in ("geometry_type_preserved", "component_count_preserved", "hole_counts_preserved",
                      "output_uses_only_input_vertices"):
            if not true(row, field):
                return None
        applicable = "rings_closed" if row["geometry_type"] in {"Polygon", "MultiPolygon"} else "endpoints_preserved"
        if row.get(applicable) != "N/A" and not true(row, applicable):
            return None
    if (number(adaptive, "actual_reduction_percent") or 0.0) <= 0.0:
        return None

    ah = number(adaptive, "symmetric_hausdorff_distance")
    dh = number(dp, "symmetric_hausdorff_distance")
    am = number(adaptive, "mean_geometric_deviation")
    dm = number(dp, "mean_geometric_deviation")
    if None in (ah, dh, am, dm) or dh <= 0.0 or dm <= 0.0:
        return None
    hausdorff_gain = 1.0 - ah / dh
    mean_gain = 1.0 - am / dm
    if hausdorff_gain < 0.05 or mean_gain < 0.05:
        return None

    ax = number(adaptive, "maximum_removed_vertex_deviation")
    dx = number(dp, "maximum_removed_vertex_deviation")
    if ax is not None and dx is not None and ax > dx:
        return None

    if adaptive["geometry_type"] in {"Polygon", "MultiPolygon"}:
        guards = ("relative_area_difference", "symmetric_difference_area")
    else:
        guards = ("relative_length_difference",)
    for field in guards:
        av, dv = number(adaptive, field), number(dp, field)
        if av is None or dv is None or av > dv:
            return None
    return min(hausdorff_gain, mean_gain), hausdorff_gain, mean_gain


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("results", type=Path, help="directory containing benchmark CSV outputs")
    parser.add_argument("--output", type=Path, default=Path("wkt_best_examples.txt"))
    parser.add_argument("--metadata", type=Path, default=Path("wkt_best_examples_selection.csv"))
    args = parser.parse_args()

    with (args.results / "paired_comparisons.csv").open(newline="", encoding="utf-8") as f:
        pairs = list(csv.DictReader(f))
    with (args.results / "algorithm_metrics.csv").open(newline="", encoding="utf-8") as f:
        metric_rows = list(csv.DictReader(f))
    with (args.results / "geometries.csv").open(newline="", encoding="utf-8") as f:
        geometry_rows = list(csv.DictReader(f))

    metrics = {(r["input_id"], r["target_reduction_percent"], r["algorithm"]): r for r in metric_rows}
    geometries = {(r["input_id"], r["target_reduction_percent"]): r for r in geometry_rows}
    candidates = []
    for pair in pairs:
        key = pair["input_id"], pair["target_reduction_percent"]
        adaptive = metrics.get((*key, "adaptive"))
        dp = metrics.get((*key, "douglas_peucker"))
        if adaptive is None or dp is None:
            continue
        score = eligible(pair, adaptive, dp)
        if score is not None:
            candidates.append((score, pair, adaptive, dp, geometries[key]))

    candidates.sort(key=lambda item: (-item[0][0], item[1]["input_id"], float(item[1]["target_reduction_percent"])))
    selected = []
    used = set()
    for item in candidates:
        input_id = item[1]["input_id"]
        if input_id not in used:
            selected.append(item)
            used.add(input_id)
        if len(selected) == 3:
            break
    if len(selected) < 3:
        raise SystemExit(f"Only {len(selected)} cases passed the frozen selection rule; criteria were not relaxed")

    args.output.write_text("\n".join(item[4]["original_wkt"] for item in selected) + "\n", encoding="utf-8")
    fields = [
        "input_id", "osm_source_group", "target_reduction_percent", "geometry_type",
        "adaptive_actual_reduction_percent", "dp_actual_reduction_percent", "rate_difference_percent",
        "adaptive_hausdorff", "dp_hausdorff", "hausdorff_improvement_percent",
        "adaptive_mean_deviation", "dp_mean_deviation", "mean_improvement_percent",
        "adaptive_max_removed_deviation", "dp_max_removed_deviation",
        "adaptive_relative_length_difference", "dp_relative_length_difference",
        "adaptive_relative_area_difference", "dp_relative_area_difference",
        "adaptive_symmetric_difference_area", "dp_symmetric_difference_area", "selection_score"
    ]
    with args.metadata.open("w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=fields)
        writer.writeheader()
        for (score, pair, adaptive, dp, _) in selected:
            writer.writerow({
                "input_id": pair["input_id"],
                "osm_source_group": source_label(pair["input_id"]),
                "target_reduction_percent": pair["target_reduction_percent"],
                "geometry_type": adaptive["geometry_type"],
                "adaptive_actual_reduction_percent": adaptive["actual_reduction_percent"],
                "dp_actual_reduction_percent": dp["actual_reduction_percent"],
                "rate_difference_percent": pair["achieved_reduction_difference_percent"],
                "adaptive_hausdorff": adaptive["symmetric_hausdorff_distance"],
                "dp_hausdorff": dp["symmetric_hausdorff_distance"],
                "hausdorff_improvement_percent": 100.0 * score[1],
                "adaptive_mean_deviation": adaptive["mean_geometric_deviation"],
                "dp_mean_deviation": dp["mean_geometric_deviation"],
                "mean_improvement_percent": 100.0 * score[2],
                "adaptive_max_removed_deviation": adaptive["maximum_removed_vertex_deviation"],
                "dp_max_removed_deviation": dp["maximum_removed_vertex_deviation"],
                "adaptive_relative_length_difference": adaptive["relative_length_difference"],
                "dp_relative_length_difference": dp["relative_length_difference"],
                "adaptive_relative_area_difference": adaptive["relative_area_difference"],
                "dp_relative_area_difference": dp["relative_area_difference"],
                "adaptive_symmetric_difference_area": adaptive["symmetric_difference_area"],
                "dp_symmetric_difference_area": dp["symmetric_difference_area"],
                "selection_score": score[0],
            })
    print(f"Selected {len(selected)} of {len(candidates)} qualifying input-target candidates")
    for _, pair, adaptive, dp, _ in selected:
        print(f"{pair['input_id']} target={pair['target_reduction_percent']} type={adaptive['geometry_type']} "
              f"adaptive={adaptive['actual_reduction_percent']}% dp={dp['actual_reduction_percent']}%")


if __name__ == "__main__":
    main()
