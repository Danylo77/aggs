package com.adaptive.simplifier

import com.adaptive.simplifier.TestSupport._
import org.junit.Assert._
import org.junit.Test
import org.locationtech.jts.geom.{LineString, MultiLineString, MultiPolygon, Polygon}

class GeometryStructureTest {
  @Test def extractAndRebuildPreservePartOrderAndStructure(): Unit = {
    val wkt = "MULTIPOLYGON (((0 0, 9 0, 9 9, 0 9, 0 0), (1 1, 1 2, 2 2, 2 1, 1 1), (4 4, 4 5, 5 5, 5 4, 4 4)), ((20 0, 25 0, 25 5, 20 5, 20 0)))"
    val input = parse(wkt).asInstanceOf[MultiPolygon]
    val parts = GeometryParts.extract(input)
    assertEquals(4, parts.size)
    assertTrue(parts.forall(_.isRing))
    assertTrue(parts(0).coords.head.equals2D(input.getGeometryN(0).asInstanceOf[Polygon].getExteriorRing.getCoordinateN(0)))
    val rebuilt = GeometryParts.rebuild(input, parts.iterator.map(_.coords))
    assertTrue(input.equalsExact(rebuilt))
    assertEquals(Seq(2, 0), holeCounts(rebuilt))
  }

  @Test def multilinePartOrderAndEndpointsSurvive(): Unit = {
    val wkt = "MULTILINESTRING ((0 0, 1 2, 2 0, 3 3, 4 0), (10 0, 11 4, 12 -1, 13 0))"
    val input = parse(wkt).asInstanceOf[MultiLineString]
    val output = parse(AdaptiveSimplifier.simplify(wkt, 70)).asInstanceOf[MultiLineString]
    assertEquals(2, output.getNumGeometries)
    assertLineEndpoints(input, output)
  }

  @Test def polygonHolesClosureAndMinimumRingSizeSurviveAggressiveReduction(): Unit = {
    val wkt = "POLYGON ((0 0, 4 -1, 8 0, 11 4, 10 8, 6 11, 2 10, -1 6, 0 0), (2 2, 2 5, 3 6, 5 5, 5 2, 2 2), (7 5, 8 4, 9 5, 9 7, 7 7, 7 5))"
    val input = parse(wkt).asInstanceOf[Polygon]
    val output = parse(AdaptiveSimplifier.simplify(wkt, 100)).asInstanceOf[Polygon]
    assertValidAndSameStructure(input, output)
    assertEquals(2, output.getNumInteriorRing)
  }

  @Test def minimalAndLongPartsCanCoexist(): Unit = {
    val wkt = "MULTILINESTRING ((0 0, 1 1), (10 0, 11 0.1, 12 -0.1, 13 3, 14 -2, 15 0))"
    val output = parse(AdaptiveSimplifier.simplify(wkt, 80)).asInstanceOf[MultiLineString]
    assertEquals(2, output.getGeometryN(0).getNumPoints)
    assertTrue(output.getGeometryN(1).getNumPoints >= 2)
  }

  @Test def cyclicRingSerializationHasIdenticalOutput(): Unit = {
    val points = Vector((0,0),(6,0),(7,1),(6,2),(8,4),(5,5),(4,8),(2,6),(0,7),(-1,4),(1,3),(-2,2))
    def wkt(k: Int): String = {
      val r = points.drop(k) ++ points.take(k)
      "POLYGON ((" + (r :+ r.head).map { case (x,y) => s"$x $y" }.mkString(", ") + "))"
    }
    val outputs = points.indices.map(k => AdaptiveSimplifier.simplify(wkt(k), 50)).distinct
    assertEquals(1, outputs.size)
  }

  @Test def validatorRejectsChangedTypeComponentsHolesInvalidityAndNewCrossing(): Unit = {
    val line = parse("LINESTRING (0 0, 1 1)")
    assertEquals(Some("geometry type changed"), GeometryValidator.rejectionReason(parse("POINT (0 0)"), line, requireSimple = true))

    val multi2 = parse("MULTILINESTRING ((0 0, 1 1), (2 2, 3 3))")
    val multi1 = parse("MULTILINESTRING ((0 0, 1 1))")
    assertEquals(Some("component count changed"), GeometryValidator.rejectionReason(multi1, multi2, requireSimple = true))

    val shell = parse("POLYGON ((0 0, 5 0, 5 5, 0 5, 0 0))")
    val hole = parse("POLYGON ((0 0, 5 0, 5 5, 0 5, 0 0), (1 1, 1 2, 2 2, 2 1, 1 1))")
    assertEquals(Some("hole count changed"), GeometryValidator.rejectionReason(shell, hole, requireSimple = true))

    val invalid = parse("POLYGON ((0 0, 2 2, 2 0, 0 2, 0 0))")
    assertTrue(GeometryValidator.rejectionReason(invalid, shell, requireSimple = true).exists(_.startsWith("invalid result")))

    val simple = parse("LINESTRING (0 0, 1 2, 2 0)")
    val crossing = parse("LINESTRING (0 0, 2 2, 0 2, 2 0)")
    assertTrue(GeometryValidator.rejectionReason(crossing, simple, requireSimple = true).exists(_.contains("self-intersection")))
    assertEquals(None, GeometryValidator.rejectionReason(crossing, simple, requireSimple = false))
  }
}
