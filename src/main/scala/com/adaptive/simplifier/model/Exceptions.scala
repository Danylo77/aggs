package com.adaptive.simplifier.model

/** Base type of every input error raised by the public API. */
class SimplificationInputException(message: String, cause: Throwable = null)
    extends IllegalArgumentException(message, cause)

/** The WKT text is null, blank, malformed, or describes a structurally impossible geometry (e.g. an unclosed ring). */
final class InvalidWktException(message: String, cause: Throwable = null)
    extends SimplificationInputException(message, cause)

/** The geometry type is not supported (POINT, MULTIPOINT, GEOMETRYCOLLECTION, LINEARRING, ...). */
final class UnsupportedGeometryTypeException(message: String) extends SimplificationInputException(message)

/** The geometry parses but is not topologically valid according to JTS (it is never repaired automatically). */
final class InvalidGeometryException(message: String) extends SimplificationInputException(message)

/** The reduction percentage is NaN, infinite, or outside [0, 100]. */
final class InvalidReductionPercentException(message: String) extends SimplificationInputException(message)
