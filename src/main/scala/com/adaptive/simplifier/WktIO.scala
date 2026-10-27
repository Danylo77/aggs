package com.adaptive.simplifier

import com.adaptive.simplifier.model.InvalidWktException
import org.locationtech.jts.geom.{Geometry, GeometryFactory}
import org.locationtech.jts.io.{ParseException, WKTReader, WKTWriter}

/**
  * WKT parsing and serialization. Readers/writers are created per call because
  * JTS `WKTReader`/`WKTWriter` are not thread-safe; `GeometryFactory` is immutable.
  * Output is 2D: Z/M ordinates, if present in the input, are not written.
  */
private[simplifier] object WktIO {
  private val factory = new GeometryFactory()

  def parse(wkt: String): Geometry = {
    if (wkt == null) throw new InvalidWktException("WKT must not be null")
    if (wkt.trim.isEmpty) throw new InvalidWktException("WKT must not be blank")
    try new WKTReader(factory).read(wkt)
    catch {
      case e: ParseException =>
        throw new InvalidWktException(s"Malformed WKT: ${e.getMessage}", e)
      case e: IllegalArgumentException =>
        // JTS raises this for structurally impossible input, e.g. unclosed or too-short rings.
        throw new InvalidWktException(s"WKT describes a structurally invalid geometry: ${e.getMessage}", e)
    }
  }

  def write(geometry: Geometry): String = new WKTWriter().write(geometry)
}
