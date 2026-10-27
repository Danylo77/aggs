package com.adaptive.simplifier

import org.locationtech.jts.geom._

/** One simplifiable coordinate sequence: a line, a polygon shell, or a hole. */
private[simplifier] final case class RawPart(coords: Array[Coordinate], isRing: Boolean)

/**
  * Decomposes supported geometries into parts and rebuilds them in exactly the same
  * order, so components, shells, and holes keep their structure.
  */
private[simplifier] object GeometryParts {

  def extract(geometry: Geometry): Vector[RawPart] = geometry match {
    case p: Polygon => polygonParts(p)
    case mp: MultiPolygon =>
      (0 until mp.getNumGeometries).flatMap(i => polygonParts(mp.getGeometryN(i).asInstanceOf[Polygon])).toVector
    case ml: MultiLineString =>
      (0 until ml.getNumGeometries).map(i => RawPart(ml.getGeometryN(i).getCoordinates, isRing = false)).toVector
    case l: LineString => Vector(RawPart(l.getCoordinates, isRing = false))
    case other => throw new IllegalStateException(s"Unsupported geometry reached decomposition: ${other.getGeometryType}")
  }

  /** Rebuilds a geometry with the structure of `template` from parts in `extract` order. */
  def rebuild(template: Geometry, parts: Iterator[Array[Coordinate]]): Geometry = {
    val f = template.getFactory
    template match {
      case p: Polygon => rebuildPolygon(p, parts)
      case mp: MultiPolygon =>
        f.createMultiPolygon(Array.tabulate(mp.getNumGeometries)(i =>
          rebuildPolygon(mp.getGeometryN(i).asInstanceOf[Polygon], parts)))
      case ml: MultiLineString =>
        f.createMultiLineString(Array.tabulate(ml.getNumGeometries)(_ => f.createLineString(parts.next())))
      case _: LineString => f.createLineString(parts.next())
      case other => throw new IllegalStateException(s"Unsupported geometry reached rebuild: ${other.getGeometryType}")
    }
  }

  private def polygonParts(p: Polygon): Vector[RawPart] =
    if (p.isEmpty) Vector.empty
    else
      RawPart(p.getExteriorRing.getCoordinates, isRing = true) +:
        (0 until p.getNumInteriorRing).map(i => RawPart(p.getInteriorRingN(i).getCoordinates, isRing = true)).toVector

  private def rebuildPolygon(template: Polygon, parts: Iterator[Array[Coordinate]]): Polygon = {
    val f = template.getFactory
    if (template.isEmpty) f.createPolygon()
    else {
      val shell = f.createLinearRing(parts.next())
      val holes = Array.tabulate(template.getNumInteriorRing)(_ => f.createLinearRing(parts.next()))
      f.createPolygon(shell, holes)
    }
  }
}
