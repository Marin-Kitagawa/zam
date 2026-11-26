package zam

import java.nio.file.{Files, Path, Paths}
import java.util.Locale
import scala.jdk.CollectionConverters.*
import scala.util.Using

/** Candidates for tab completion.
  *
  * The shell integrations call `zam complete`; the ranking is deliberately
  * "what is in front of you first, memory second":
  *
  *   1. directories that physically exist in the current directory
  *   2. aliases (exact, then prefix)
  *   3. remembered directories whose basename matches (exact, prefix, then
  *      substring), ordered by frecency
  *
  * Only existing directories are offered unless `--all` is given, so a stale
  * history can never leak dead paths into the menu. Path-like words (`foo/`,
  * `C:\Us`, `~/pro`) complete on the filesystem, falling back to remembered
  * paths only when the filesystem has nothing to say.
  */
object Completion:

  /** One completion. `value` is inserted verbatim (the shell snippets quote it
    * when it contains whitespace); `detail` is shown as a tooltip. */
  final case class Candidate(
      value: String,
      display: String,
      detail: String,
      source: String,
      group: Int,
      weight: Double = 0.0
  )

  private val FlagHelp: Vector[(String, String)] = Vector(
    "-l, --list" -> "list remembered directories",
    "-s, --score" -> "list matches with scores",
    "-a, --all" -> "include directories that no longer exist",
    "-i, --interactive" -> "open the interactive picker",
    "-" -> "jump to the previous directory",
    "--help" -> "show help"
  )

  private def lower(s: String): String = s.toLowerCase(Locale.ROOT)

  private def lastSep(s: String): Int = math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'))

  private def isSep(c: Char): Boolean = c == '/' || c == '\\'

  private def baseName(s: String): String =
    val t = if s.length > 1 && isSep(s.last) then s.dropRight(1) else s
    t.substring(lastSep(t) + 1)

  def looksLikeFlag(w: String): Boolean =
    w == "-" || (w.length > 1 && w.startsWith("-") && w.drop(1).forall(c => c == '-' || c.isLetter))

  /** Direct child directories of `dir`, or empty when it cannot be listed. */
  private def childDirs(dir: Path): Vector[String] =
    try
      Using.resource(Files.newDirectoryStream(dir)) { stream =>
        stream.iterator().asScala
          .filter(p => try Files.isDirectory(p) catch case _: Exception => false)
          .map(_.getFileName.toString)
          .toVector
      }
    catch case _: Exception => Vector.empty

  /** Resolve a (possibly relative, possibly `~`-rooted) directory prefix. */
  private def resolveDir(cwd: Path, raw: String): Option[Path] =
    val trimmed = raw.trim
    if trimmed.isEmpty then Some(cwd)
    else
      try
        val expanded =
          if trimmed == "~" then System.getProperty("user.home")
          else if trimmed.startsWith("~/") || trimmed.startsWith("~\\") then
            Paths.get(System.getProperty("user.home"), trimmed.substring(1)).toString
          else trimmed
        val p = Paths.get(expanded)
        val abs = if p.isAbsolute then p else cwd.resolve(p)
        val n = abs.normalize
        if Files.isDirectory(n) then Some(n) else None
      catch case _: Exception => None

  private def formatScore(d: Double): String = String.format(Locale.ROOT, "%.2f", d)

  /** Flag candidates for a word starting with `-`. */
  private def flagCandidates(word: String): Vector[Candidate] =
    val tokens = FlagHelp.flatMap { case (names, help) =>
      names.split(',').map(_.trim).filter(_.nonEmpty).map(t => (t, help))
    }
    val exact = tokens.filter { case (t, _) => t.equalsIgnoreCase(word) }
    val pref = tokens.filter { case (t, _) =>
      !t.equalsIgnoreCase(word) && lower(t).startsWith(lower(word))
    }
    (exact ++ pref).map { case (t, help) => Candidate(t, t, help, "flag", 0) }

  /** Remembered paths that literally start with a path-like word. Only used
    * when the filesystem produced no candidates. */
  private def dbPathPrefix(
      word: String,
      db: Model.Db,
      now: Long,
      includeMissing: Boolean
  ): Vector[Candidate] =
    val w = lower(word.replace('\\', '/'))
    if w.isEmpty then Vector.empty
    else
      db.entries.iterator
        .filter(e => includeMissing || Files.isDirectory(Paths.get(e.path)))
        .filter(e => lower(e.path.replace('\\', '/')).startsWith(w))
        .map { e =>
          val s = Score.frecency(e, now, db.config)
          val age = e.lastVisit.map(l => (now - l).max(0L)).getOrElse(0L)
          Candidate(
            e.path,
            e.path,
            s"${e.count} visit(s) · ${Score.humanAge(age)} · score ${formatScore(s)}",
            "history",
            6,
            s
          )
        }
        .toVector

  /** All suggestions for `wordRaw`, best first. */
  def suggest(
      wordRaw: String,
      cwd: Path,
      db: Model.Db,
      now: Long,
      includeMissing: Boolean = false,
      limit: Int = 100
  ): Vector[Candidate] =
    val word = wordRaw
    if looksLikeFlag(word) then return flagCandidates(word)

    val sepIdx = lastSep(word)
    val tildeOnly = word == "~"
    val pathish =
      sepIdx >= 0 || tildeOnly || word.startsWith("~/") || word.startsWith("~\\") ||
        (word.nonEmpty && Store.isAbsolute(word))
    val sep = java.io.File.separatorChar

    val candidates =
      if pathish then
        val (dirPart, base) =
          if sepIdx >= 0 then (word.take(sepIdx + 1), word.drop(sepIdx + 1))
          else if tildeOnly then ("~" + sep, "")
          else ("", word)
        val local = resolveDir(cwd, dirPart).toVector.flatMap { d =>
          val baseKey = lower(base)
          childDirs(d)
            .filter(n => lower(n).startsWith(baseKey))
            .map { n =>
              val value = dirPart + n
              val detail = d.resolve(n).toString
              val group = if n.equalsIgnoreCase(base) then 0 else 2
              Candidate(value, value, detail, "here", group)
            }
        }
        val localSorted = local.sortBy(c => (c.group, lower(c.value)))
        if localSorted.nonEmpty then localSorted
        else dbPathPrefix(word, db, now, includeMissing)

      else
        val w = lower(word)
        val cwdKey = Store.key(cwd.toAbsolutePath.normalize.toString)
        def here(p: String): Boolean = Store.key(p) == cwdKey

        val localNames = childDirs(cwd)
        val prefixNames = localNames.filter(n => lower(n).startsWith(w))
        val localPrefix = prefixNames
          .sortBy(n => (if n.equalsIgnoreCase(word) then 0 else 1, lower(n)))
          .map { n =>
            val group = if n.equalsIgnoreCase(word) then 0 else 2
            Candidate(n, n, cwd.resolve(n).toString, "here", group)
          }
        // a looser "contains" pass so `z job<TAB>` can still find
        // `ai-job-search` sitting right here, but only below history's
        // exact/prefix matches
        val localSubstring =
          if w.isEmpty then Vector.empty
          else
            localNames
              .filter(n => !prefixNames.contains(n) && lower(n).contains(w))
              .sortBy(n => lower(n))
              .map(n => Candidate(n, n, cwd.resolve(n).toString, "here", 6))
        val localPaths =
          (localPrefix ++ localSubstring).map(c => Store.key(cwd.resolve(c.value).toString)).toSet

        val aliases = db.aliases.flatMap { (name, path) =>
          val k = lower(name)
          if k == w then
            Some(Candidate(name, name, s"alias → $path", "alias", 1))
          else if k.startsWith(w) then
            Some(Candidate(name, name, s"alias → $path", "alias", 3))
          else None
        }

        val historyAll = db.entries.iterator
          .filter(e => includeMissing || Files.isDirectory(Paths.get(e.path)))
          .filter(e => !here(e.path) && !localPaths.contains(Store.key(e.path)))
          .flatMap { e =>
            val b = lower(baseName(e.path))
            val group =
              if b == w then 4
              else if b.startsWith(w) then 5
              else if w.length >= 2 && b.contains(w) then 7
              else -1
            if group < 0 then None
            else
              val s = Score.frecency(e, now, db.config)
              val age = e.lastVisit.map(l => (now - l).max(0L)).getOrElse(0L)
              Some(
                Candidate(
                  e.path,
                  e.path,
                  s"${e.count} visit(s) · ${Score.humanAge(age)} · score ${formatScore(s)}",
                  "history",
                  group,
                  s
                )
              )
          }
          .toVector
        // an empty Tab means "show me what is around", not "dump my whole
        // history": keep only the ten most frecent memories then
        val history = if w.isEmpty then historyAll.sortBy(-_.weight).take(10) else historyAll

        localPrefix ++ localSubstring ++ aliases ++ history

    candidates
      .groupBy(c => lower(c.value))
      .values
      .map(_.minBy(c => (c.group, if c.source == "history" then -c.weight else 0.0)))
      .toVector
      .sortBy(c => (c.group, if c.source == "history" then -c.weight else 0.0, lower(c.display)))
      .take(math.max(0, limit))
