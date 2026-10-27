package com.adaptive.simplifier.benchmark

import org.locationtech.jts.geom._
import org.locationtech.jts.io.{WKTReader, WKTWriter}

private[benchmark] object BenchmarkGeometry {
  private val factory = new GeometryFactory()
  private val supported = Set("LineString", "MultiLineString", "Polygon", "MultiPolygon")

  def parse(wkt: String): Geometry = new WKTReader(factory).read(wkt)
  def write(g: Geometry): String = new WKTWriter().write(g)
  def isSupported(g: Geometry): Boolean = supported.contains(g.getGeometryType)
  def isPolygonal(g: Geometry): Boolean = g.isInstanceOf[Polygonal]

  /** Lines, multiline components, polygon shells, then holes, in component order. */
  def parts(g: Geometry): Vector[LineString] = g match {
    case l: LineString => Vector(l)
    case ml: MultiLineString =>
      (0 until ml.getNumGeometries).map(i => ml.getGeometryN(i).asInstanceOf[LineString]).toVector
    case p: Polygon => polygonParts(p)
    case mp: MultiPolygon =>
      (0 until mp.getNumGeometries).flatMap(i => polygonParts(mp.getGeometryN(i).asInstanceOf[Polygon])).toVector
    case _ => Vector.empty
  }

  /** Flattened vertices in part order; ring-closing occurrences are retained. */
  def points(g: Geometry): Seq[Point] =
    parts(g).flatMap(part => part.getCoordinates.map(c => g.getFactory.createPoint(c.copy())))

  def holeCounts(g: Geometry): Vector[Int] = g match {
    case p: Polygon => Vector(p.getNumInteriorRing)
    case mp: MultiPolygon =>
      (0 until mp.getNumGeometries).map(i => mp.getGeometryN(i).asInstanceOf[Polygon].getNumInteriorRing).toVector
    case _ => Vector.empty
  }

  def holeCount(g: Geometry): Int = holeCounts(g).sum

  def metricLinework(g: Geometry): Geometry = if (isPolygonal(g)) g.getBoundary else g

  private def polygonParts(p: Polygon): Vector[LineString] =
    if (p.isEmpty) Vector.empty
    else p.getExteriorRing +: (0 until p.getNumInteriorRing).map(p.getInteriorRingN).toVector
}
