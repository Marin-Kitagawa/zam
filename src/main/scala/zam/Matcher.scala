package zam

import scala.util.boundary

/** Path matching, faithful to zoxide's documented algorithm:
  *
  *  1. All matching is case-insensitive.
  *  2. All terms must be present (including slashes) within the path, in
  *     order. `z fo ba` matches `/foo/bar` but not `/bar/foo`; `z fo / ba`
  *     matches `/foo/bar` but not `/foobar`.
  *  3. The last component of the last keyword must match within the last
  *     component of the path. `z bar` matches `/foo/bar` but not `/bar/foo`;
  *     `z foo/bar` matches `/foo/bar` but not `/foo/bar/baz`.
  */
object Matcher:

  private val TermRegex = raw"\s+".r

  def splitTerms(query: String): Vector[String] =
    TermRegex.split(query.trim).toVector
      .map(t => t.replace('\\', '/').toLowerCase(java.util.Locale.ROOT))
      .filter(_.nonEmpty)

  def matches(pathRaw: String, query: String): Boolean =
    val terms = splitTerms(query)
    if terms.isEmpty then return false
    val path = pathRaw.replace('\\', '/').toLowerCase(java.util.Locale.ROOT)
    boundary:
      var pos = 0
      for term <- terms do
        val idx = path.indexOf(term, pos)
        if idx < 0 then boundary.break(false)
        pos = idx + term.length
      // rule 3: last component of last term must land in last path component
      val lastTermComponent = terms.last.split("/").last
      val lastPathComponent = path.substring(path.lastIndexOf('/') + 1)
      lastPathComponent.contains(lastTermComponent)

  /** Like [[matches]], but also returns the character offsets in the original
    * path where the fuzzy query hits, for TUI highlighting. */
  def matchPositions(pathRaw: String, query: String): Option[Vector[Int]] =
    val terms = splitTerms(query)
    if terms.isEmpty then return Some(Vector.empty)
    val path = pathRaw.replace('\\', '/').toLowerCase(java.util.Locale.ROOT)
    boundary:
      var pos = 0
      var acc = Vector.empty[Int]
      for term <- terms do
        val idx = path.indexOf(term, pos)
        if idx < 0 then boundary.break(None)
        acc ++= (idx until idx + term.length)
        pos = idx + term.length
      val lastTermComponent = terms.last.split("/").last
      val lastPathComponent = path.substring(path.lastIndexOf('/') + 1)
      if lastPathComponent.contains(lastTermComponent) then Some(acc) else None

  /** Offsets of every match of `pattern` (as a regex) within `path`. Returns
    * None when the pattern is not a valid regex or matches nothing. */
  def regexPositions(path: String, pattern: String): Option[Vector[Int]] =
    try
      val m = pattern.r.findAllMatchIn(path).toVector
      if m.isEmpty then None
      else Some(m.flatMap(x => (x.start until x.end).toVector))
    catch case _: Exception => None