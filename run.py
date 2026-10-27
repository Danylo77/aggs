"""Download diverse OpenStreetMap geometries and save them as WKT examples.

The output contains one WKT geometry per line and only geometry types supported by
this project's simplifier: LineString, MultiLineString, Polygon, and MultiPolygon.
Coordinates remain in OSM's EPSG:4326 longitude/latitude coordinate system.
"""

from __future__ import annotations

import argparse
import logging
import time
from dataclasses import dataclass
from itertools import combinations
from pathlib import Path

import osmnx as ox
from shapely import force_2d, get_num_coordinates, to_wkt
from shapely.geometry import MultiLineString, MultiPolygon
from shapely.geometry.base import BaseGeometry

SUPPORTED_TYPES = {"LineString", "MultiLineString", "Polygon", "MultiPolygon"}
LINE_TYPES = {"LineString", "MultiLineString"}
POLYGON_TYPES = {"Polygon", "MultiPolygon"}
DEFAULT_OUTPUT = Path(__file__).with_name("wkt_examples.txt")


@dataclass(frozen=True)
class OsmQuery:
    """A small, bounded OSM query used to build one example category."""

    name: str
    bbox: tuple[float, float, float, float]
    tags: dict[str, bool | str | list[str]]
    accepted_types: frozenset[str]


QUERIES = (
    OsmQuery(
        name="Lviv city areas",
        bbox=(24.020, 49.835, 24.042, 49.851),
        tags={
            "landuse": ["residential", "commercial", "retail"],
            "leisure": ["park", "garden", "pitch"],
        },
        accepted_types=frozenset(POLYGON_TYPES),
    ),
    OsmQuery(
        name="Lviv railway lines",
        bbox=(23.975, 49.825, 24.025, 49.855),
        tags={"railway": ["rail", "light_rail", "tram"]},
        accepted_types=frozenset(LINE_TYPES),
    ),
    OsmQuery(
        name="Venice island areas",
        bbox=(12.285, 45.410, 12.385, 45.475),
        tags={"place": ["island", "islet"], "natural": ["island", "islet"]},
        accepted_types=frozenset(POLYGON_TYPES),
    ),
    OsmQuery(
        name="Amsterdam waterways",
        bbox=(4.865, 52.345, 4.940, 52.405),
        tags={"waterway": ["river", "canal"]},
        accepted_types=frozenset(LINE_TYPES),
    ),
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--output",
        type=Path,
        default=DEFAULT_OUTPUT,
        help=f"output text file (default: {DEFAULT_OUTPUT.name})",
    )
    parser.add_argument(
        "--per-source",
        type=int,
        default=15,
        help="maximum number of geometries retained from each OSM query",
    )
    args = parser.parse_args()
    if args.per_source < 1:
        parser.error("--per-source must be at least 1")
    return args


def is_usable(geometry: BaseGeometry, accepted_types: frozenset[str]) -> bool:
    """Return whether a geometry is suitable input for the Scala simplifier."""
    coordinate_count = get_num_coordinates(geometry)
    return (
        geometry.geom_type in accepted_types
        and not geometry.is_empty
        and geometry.is_valid
        and 4 <= coordinate_count <= 2_000
    )


def download_features(query: OsmQuery, attempts: int = 3):
    """Download OSM features, retrying temporary Overpass failures."""
    for attempt in range(1, attempts + 1):
        try:
            return ox.features_from_bbox(query.bbox, query.tags)
        except Exception:
            if attempt == attempts:
                raise
            delay = 5 * (2 ** (attempt - 1))
            logging.warning(
                "Download attempt %d/%d failed for %s; retrying in %d seconds",
                attempt,
                attempts,
                query.name,
                delay,
            )
            time.sleep(delay)
    raise AssertionError("retry loop ended unexpectedly")


def select_examples(query: OsmQuery, limit: int) -> list[BaseGeometry]:
    """Fetch and deterministically select geometries with useful vertex counts."""
    logging.info("Downloading %s", query.name)
    features = download_features(query)

    candidates: list[BaseGeometry] = []
    seen: set[bytes] = set()
    for raw_geometry in features.geometry.dropna():
        geometry = force_2d(raw_geometry)
        if not is_usable(geometry, query.accepted_types):
            continue
        fingerprint = geometry.wkb
        if fingerprint in seen:
            continue
        seen.add(fingerprint)
        candidates.append(geometry)

    # Prefer geometries rich enough to exercise simplification, without choosing
    # only the very largest OSM relations.
    candidates.sort(key=lambda item: (-min(get_num_coordinates(item), 500), item.wkb_hex))
    selected = candidates[:limit]
    logging.info("Selected %d of %d usable geometries", len(selected), len(candidates))
    return selected


def make_multiline(lines: list[BaseGeometry]) -> MultiLineString | None:
    components = []
    for geometry in lines:
        if geometry.geom_type == "LineString":
            components.append(geometry)
        else:
            components.extend(geometry.geoms)
        if len(components) >= 3:
            result = MultiLineString(components[:3])
            return result if result.is_valid else None
    return None


def make_multipolygon(polygons: list[BaseGeometry]) -> MultiPolygon | None:
    components = []
    for geometry in polygons:
        if geometry.geom_type == "Polygon":
            components.append(geometry)
        else:
            components.extend(geometry.geoms)

    # Disjoint components guarantee that the resulting MultiPolygon is valid.
    for left, right in combinations(components, 2):
        if left.disjoint(right):
            result = MultiPolygon([left, right])
            if result.is_valid:
                return result
    return None


def ensure_geometry_type_coverage(geometries: list[BaseGeometry]) -> list[BaseGeometry]:
    """Ensure the file contains both singular and multipart line/area examples."""
    result = list(geometries)
    types = {geometry.geom_type for geometry in result}

    if "LineString" not in types:
        multipart = next((g for g in result if g.geom_type == "MultiLineString"), None)
        if multipart is not None:
            result.append(multipart.geoms[0])

    if "Polygon" not in types:
        multipart = next((g for g in result if g.geom_type == "MultiPolygon"), None)
        if multipart is not None:
            result.append(multipart.geoms[0])

    types = {geometry.geom_type for geometry in result}
    if "MultiLineString" not in types:
        multiline = make_multiline([g for g in result if g.geom_type in LINE_TYPES])
        if multiline is not None:
            result.append(multiline)

    if "MultiPolygon" not in types:
        multipolygon = make_multipolygon([g for g in result if g.geom_type in POLYGON_TYPES])
        if multipolygon is not None:
            result.append(multipolygon)

    missing = SUPPORTED_TYPES - {geometry.geom_type for geometry in result}
    if missing:
        raise RuntimeError(f"Could not obtain examples for: {', '.join(sorted(missing))}")
    return result


def write_wkt_file(path: Path, geometries: list[BaseGeometry]) -> None:
    """Write plain text with exactly one two-dimensional WKT value per line."""
    path.parent.mkdir(parents=True, exist_ok=True)
    values = [
        to_wkt(geometry, rounding_precision=7, trim=True, output_dimension=2)
        for geometry in geometries
    ]
    path.write_text("\n".join(values) + "\n", encoding="utf-8")


def main() -> None:
    args = parse_args()
    logging.basicConfig(level=logging.INFO, format="%(levelname)s: %(message)s")

    ox.settings.use_cache = True
    ox.settings.cache_folder = Path(__file__).with_name("cache")
    ox.settings.requests_timeout = 180

    geometries: list[BaseGeometry] = []
    failures: list[str] = []
    for query in QUERIES:
        try:
            geometries.extend(select_examples(query, args.per_source))
        except Exception as error:  # Continue when one external OSM query is unavailable.
            failures.append(query.name)
            logging.warning("Skipping %s: %s", query.name, error)

    geometries = ensure_geometry_type_coverage(geometries)
    write_wkt_file(args.output, geometries)

    counts = {kind: sum(g.geom_type == kind for g in geometries) for kind in sorted(SUPPORTED_TYPES)}
    logging.info("Wrote %d geometries to %s: %s", len(geometries), args.output, counts)
    if failures:
        logging.warning("Queries skipped because of download errors: %s", ", ".join(failures))


if __name__ == "__main__":
    main()
