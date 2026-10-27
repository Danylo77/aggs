package com.adaptive.simplifier

import org.junit.Assert._
import org.locationtech.jts.geom.{Geometry, LineString, MultiLineString, MultiPolygon, Polygon}

object TestSupport {
  def parse(wkt: String): Geometry = WktIO.parse(wkt)

  def assertValidAndSameStructure(input: Geometry, output: Geometry): Unit = {
    assertEquals(input.getGeometryType, output.getGeometryType)
    assertEquals(input.getNumGeometries, output.getNumGeometries)
    assertTrue(s"Output is invalid: ${GeometryValidator.validationError(output)}", output.isValid)
    assertEquals(holeCounts(input), holeCounts(output))
    rings(output).foreach { r =>
      assertTrue("ring must have at least four coordinates", r.getNumPoints >= 4)
      assertTrue("ring must be closed", r.getCoordinateN(0).equals2D(r.getCoordinateN(r.getNumPoints - 1)))
    }
  }

  def holeCounts(g: Geometry): Seq[Int] = g match {
    case p: Polygon => Seq(p.getNumInteriorRing)
    case mp: MultiPolygon => (0 until mp.getNumGeometries).map(i => mp.getGeometryN(i).asInstanceOf[Polygon].getNumInteriorRing)
    case _ => Seq.empty
  }

  def rings(g: Geometry): Seq[LineString] = g match {
    case p: Polygon => polygonRings(p)
    case mp: MultiPolygon => (0 until mp.getNumGeometries).flatMap(i => polygonRings(mp.getGeometryN(i).asInstanceOf[Polygon]))
    case _ => Seq.empty
  }

  private def polygonRings(p: Polygon): Seq[LineString] =
    if (p.isEmpty) Seq.empty
    else p.getExteriorRing +: (0 until p.getNumInteriorRing).map(p.getInteriorRingN)

  def assertLineEndpoints(input: Geometry, output: Geometry): Unit = (input, output) match {
    case (a: LineString, b: LineString) if !a.isClosed => assertEndpoints(a, b)
    case (a: MultiLineString, b: MultiLineString) =>
      (0 until a.getNumGeometries).foreach { i =>
        val in = a.getGeometryN(i).asInstanceOf[LineString]
        val out = b.getGeometryN(i).asInstanceOf[LineString]
        if (!in.isClosed) assertEndpoints(in, out)
      }
    case _ => ()
  }

  private def assertEndpoints(a: LineString, b: LineString): Unit = {
    assertTrue(a.getCoordinateN(0).equals2D(b.getCoordinateN(0)))
    assertTrue(a.getCoordinateN(a.getNumPoints - 1).equals2D(b.getCoordinateN(b.getNumPoints - 1)))
  }

  def assertCoordinatesComeFromInput(input: Geometry, output: Geometry): Unit = {
    val source = input.getCoordinates
    output.getCoordinates.foreach { c =>
      assertTrue(s"output coordinate (${c.x}, ${c.y}) is not from the input",
        source.exists(s => close(s.x, c.x) && close(s.y, c.y)))
    }
  }

  private def close(a: Double, b: Double): Boolean = {
    val scale = math.max(1.0, math.max(math.abs(a), math.abs(b)))
    math.abs(a - b) <= 1e-12 * scale
  }

  def expectThrows[T <: Throwable](clazz: Class[T])(body: => Any): T = {
    try {
      body
      fail(s"Expected ${clazz.getSimpleName}")
      throw new AssertionError("unreachable")
    } catch {
      case e if clazz.isInstance(e) => clazz.cast(e)
    }
  }
}
