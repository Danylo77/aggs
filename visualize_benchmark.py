"""Plot actual original/adaptive/Douglas–Peucker WKT exported by the Scala benchmark.

Dependencies: Python 3.10+, matplotlib, shapely.
"""
from __future__ import annotations

import argparse
import csv
from pathlib import Path

import matplotlib.pyplot as plt
from shapely import wkt


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("csv", type=Path, help="benchmark geometries.csv")
    parser.add_argument("--input-id", required=True)
    parser.add_argument("--target", required=True, type=float)
    parser.add_argument("--output", type=Path, help="save PNG instead of opening a window")
    parser.add_argument(
        "--allow-unmatched",
        action="store_true",
        help="plot an unmatched or adaptive-fallback row for diagnostic inspection",
    )
    return parser.parse_args()


def coordinate_sequences(geometry):
    kind = geometry.geom_type
    if kind == "LineString":
        yield list(geometry.coords)
    elif kind == "MultiLineString":
        for part in geometry.geoms:
            yield list(part.coords)
    elif kind == "Polygon":
        yield list(geometry.exterior.coords)
        for ring in geometry.interiors:
            yield list(ring.coords)
    elif kind == "MultiPolygon":
        for polygon in geometry.geoms:
            yield from coordinate_sequences(polygon)


def normalized(point):
    return round(point[0], 12), round(point[1], 12)


def removed_vertices(original, result):
    """Occurrence-aware removals matched independently in corresponding geometry parts."""
    original_parts = list(coordinate_sequences(original))
    result_parts = list(coordinate_sequences(result))
    if len(original_parts) != len(result_parts):
        return [point for part in original_parts for point in part]
    removed = []
    for source, retained in zip(original_parts, result_parts):
        remaining = {}
        for point in retained:
            key = normalized(point)
            remaining[key] = remaining.get(key, 0) + 1
        for point in source:
            key = normalized(point)
            if remaining.get(key, 0) > 0:
                remaining[key] -= 1
            else:
                removed.append(point)
    return removed


def draw(ax, geometry, color: str, label: str, linewidth: float):
    first = True
    for seq in coordinate_sequences(geometry):
        xs = [p[0] for p in seq]
        ys = [p[1] for p in seq]
        ax.plot(xs, ys, color=color, linewidth=linewidth, label=label if first else None)
        first = False


def main() -> None:
    args = parse_args()
    with args.csv.open(newline="", encoding="utf-8") as handle:
        row = next((r for r in csv.DictReader(handle)
                    if r["input_id"] == args.input_id
                    and abs(float(r["target_reduction_percent"]) - args.target) < 1e-12), None)
    if row is None:
        raise SystemExit("No row matched --input-id and --target")
    fallback = row.get("adaptive_fallback_to_original", "false").lower() == "true"
    matched = row.get("matched", "false").lower() == "true"
    if (fallback or not matched) and not args.allow_unmatched:
        reason = "adaptive fallback" if fallback else "unmatched achieved reduction rates"
        raise SystemExit(f"Refusing direct comparison plot ({reason}); pass --allow-unmatched for diagnostic inspection")
    if not row["adaptive_wkt"] or not row["douglas_peucker_wkt"]:
        raise SystemExit("Selected row has an algorithm error and no complete WKT outputs")

    original = wkt.loads(row["original_wkt"])
    adaptive = wkt.loads(row["adaptive_wkt"])
    dp = wkt.loads(row["douglas_peucker_wkt"])

    fig, ax = plt.subplots(figsize=(10, 8))
    draw(ax, original, "#777777", "Original", 2.5)
    draw(ax, adaptive, "#0072B2", "Adaptive", 1.5)
    draw(ax, dp, "#D55E00", "Douglas–Peucker", 1.5)

    adaptive_removed = removed_vertices(original, adaptive)
    dp_removed = removed_vertices(original, dp)
    if adaptive_removed:
        x, y = zip(*[(p[0], p[1]) for p in adaptive_removed])
        ax.scatter(x, y, marker="x", s=28, color="#56B4E9", label="Removed by adaptive")
    if dp_removed:
        x, y = zip(*[(p[0], p[1]) for p in dp_removed])
        ax.scatter(x, y, marker="+", s=28, color="#E69F00", label="Removed by DP")

    ax.set_aspect("equal", adjustable="datalim")
    status = "matched" if matched else "UNMATCHED"
    if fallback:
        status += ", ADAPTIVE FALLBACK"
    ax.set_title(f"{args.input_id}, target {args.target:g}% ({status})")
    ax.legend()
    ax.grid(alpha=0.2)
    fig.tight_layout()
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        fig.savefig(args.output, dpi=160)
    else:
        plt.show()


if __name__ == "__main__":
    main()
