package zam

import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import java.nio.channels.FileChannel
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale

import Model.{Config, Db, Entry}

/** Persistence and mutation of the visit database.
  *
  * The database is a plain, audit-friendly JSON file (unlike zoxide's binary
  * bincode format) so you can inspect it, and even `git init` your data dir.
  * Writes are serialized with an OS file lock and done atomically via
  * write-temp-then-rename, so concurrent shells cannot corrupt the file.
  */
object Store:

  def isWindows: Boolean =
    System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")

  def dataDir: Path =
    sys.env.get("ZAM_DATA_DIR").map(Paths.get(_)).getOrElse {
      sys.env.get("LOCALAPPDATA")
        .map(d => Paths.get(d).resolve("zam"))
        .getOrElse(Paths.get(System.getProperty("user.home"), ".local", "share", "zam"))
    }

  /** Canonical absolute path (keeps original casing for display). A leading
    * `~` expands to the user's home, so history lines like `cd ~/proj`
    * import meaningfully. */
  def canonicalize(raw: String): String =
    val expanded = expandHome(raw)
    Paths.get(expanded).toAbsolutePath.normalize.toString

  private def expandHome(raw: String): String =
    val p = raw.replace('\\', '/')
    if p == "~" then System.getProperty("user.home")
    else if p.startsWith("~/") || p.startsWith("~\\") then
      Paths.get(System.getProperty("user.home"), raw.substring(1)).toString
    else raw

  /** Case-normalized comparison key (Windows is case-insensitive). */
  def key(p: String): String =
    if isWindows then p.toLowerCase(Locale.ROOT) else p

  def same(a: String, b: String): Boolean = key(a) == key(b)

  // ---------------------------------------------------------------- loading

  /** Result of a load: `warning` is set when the on-disk file existed but
    * could not be decoded (it has already been quarantined as
    * `db.json.corrupt-<epoch>.json`, and the previous good generation may
    * still be in `db.json.bak`). */
  def load(dir: Path): Db = loadReport(dir)._1

  def loadReport(dir: Path): (Db, Option[String]) =
    val f = dir.resolve("db.json")
    if !Files.isRegularFile(f) then (Db(Config.Default, Vector.empty), None)
    else
      val text = new String(Files.readAllBytes(f), StandardCharsets.UTF_8)
      DbCodec.decode(text) match
        case Some(db) => (db, None)
        case None =>
          // quarantine the corrupt bytes once — the prompt hook loads the db
          // on every shell prompt, so keep at most one stamped copy around
          val alreadyCopied =
            try Files.newDirectoryStream(dir, "db.json.corrupt-*.json").iterator().hasNext
            catch case _: Exception => false
          val note =
            if alreadyCopied then
              Some(s"zam: WARNING: $f is corrupt and will be ignored; a copy is in this directory " +
                "(the previous good generation may be in db.json.bak)")
            else
              val quarantined = dir.resolve(s"db.json.corrupt-${System.currentTimeMillis()}.json")
              try
                Files.copy(f, quarantined, StandardCopyOption.REPLACE_EXISTING)
                Some(s"zam: WARNING: $f is corrupt and will be ignored; a copy is saved as $quarantined" +
                  " (the previous good generation may be in db.json.bak)")
              catch case _: Exception =>
                Some(s"zam: WARNING: $f is corrupt and unreadable; starting from an empty database")
          (Db(Config.Default, Vector.empty), note)

  def save(dir: Path, db: Db): Unit =
    Files.createDirectories(dir)
    val lockPath = dir.resolve("db.lock")
    val lockOut = new FileOutputStream(lockPath.toFile, true)
    val channel = lockOut.getChannel
    var lock: java.nio.channels.FileLock = null
    try
      lock = acquire(channel)
      val tmp = dir.resolve("db.json.tmp")
      Files.writeString(tmp, DbCodec.encode(db), StandardCharsets.UTF_8)
      val target = dir.resolve("db.json")
      // keep one good generation behind the live file so a bug or crash can
      // always be rolled back by hand. A corrupt live file is *not* rotated
      // into the backup (it was already quarantined at load time) — the last
      // good generation stays in db.json.bak.
      val backup = dir.resolve("db.json.bak")
      if Files.isRegularFile(target) then
        val healthy =
          try DbCodec.decode(new String(Files.readAllBytes(target), StandardCharsets.UTF_8)).isDefined
          catch case _: Exception => false
        if healthy then
          try Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING)
          catch case _: Exception => () // never fail a save because the backup failed
      try Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
      catch case _: java.nio.file.AtomicMoveNotSupportedException =>
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
    finally
      if lock != null && lock.isValid then lock.release()
      lockOut.close()

  private def acquire(channel: FileChannel): java.nio.channels.FileLock =
    val deadline = System.nanoTime() + 5_000_000_000L
    while true do
      val l = channel.tryLock()
      if l != null then return l
      if System.nanoTime() > deadline then
        throw new RuntimeException("zam: could not acquire db.lock (another process has it)")
      Thread.sleep(20)
    throw new IllegalStateException("unreachable")

  // ---------------------------------------------------------------- mutation

  def add(db: Db, rawPaths: Seq[String], verify: Boolean, now: Long): (Db, Int) =
    var d = db
    var added = 0
    var updated = 0
    for raw <- rawPaths do
      val p = canonicalize(raw)
      if !verify || Files.isDirectory(Paths.get(p)) then
        val idx = d.entries.indexWhere(e => same(e.path, p))
        if idx < 0 then
          d = d.copy(entries = d.entries :+ Entry(p, Vector(now)))
          added += 1
        else
          val e = d.entries(idx)
          d = d.copy(entries = d.entries.updated(idx, e.copy(t = e.t :+ now)))
          updated += 1
    (cap(d), added + updated)

  /** Event-count-based cap: if total visits exceed maxVisits, drop the oldest
    * visits in the whole database, then forget entries with no visits left.
    * This bounds the database by *activity*, not by wall-clock score decay —
    * a week off does not silently erase your history. */
  def cap(db: Db): Db =
    val max = db.config.maxVisits
    if db.totalVisits <= max then db.copy(entries = db.entries.filter(_.t.nonEmpty))
    else
      val all: Vector[(Long, Int, Int)] = for
        (e, ei) <- db.entries.zipWithIndex
        (ts, ti) <- e.t.zipWithIndex
      yield (ts, ei, ti)
      val dropCount = db.totalVisits - max
      val toDrop = all.sortBy(_._1).take(dropCount.toInt).map(v => (v._2, v._3)).toSet
      val kept = db.entries.zipWithIndex.map { (e, ei) =>
        e.copy(t = e.t.zipWithIndex.collect { case (ts, ti) if !toDrop.contains((ei, ti)) => ts })
      }.filter(_.t.nonEmpty)
      db.copy(entries = kept)

  def remove(db: Db, rawPaths: Seq[String]): (Db, Int) =
    val gone = db.entries.count(e => rawPaths.exists(raw => same(e.path, canonicalize(raw))))
    val kept = db.entries.filterNot(e => rawPaths.exists(raw => same(e.path, canonicalize(raw))))
    (db.copy(entries = kept), gone)

  /** Re-point an entry at a new location (for directories that were moved or
    * renamed). If the destination is already remembered, the histories merge
    * (timestamps sorted so `lastVisit` stays correct). Returns
    * `Left(reason)` when `oldRaw` does not exactly identify one entry. */
  def move(db: Db, oldRaw: String, newRaw: String): Either[String, (Db, String)] =
    val from = canonicalize(oldRaw)
    val to = canonicalize(newRaw)
    if same(from, to) then Left(s"zam: move source and target are the same: $from")
    else
      val hits = db.entries.filter(e => same(e.path, from))
      hits match
        case Vector() =>
          Left(s"zam: not remembered (add it first or check the spelling): $from")
        case Vector(e) =>
          val merged = db.entries.find(ex => same(ex.path, to)) match
            case Some(dst) =>
              val mergedTs = (dst.t ++ e.t).sorted
              db.copy(entries = db.entries.filterNot(x => same(x.path, to) || same(x.path, from)) :+
                dst.copy(t = mergedTs))
            case None =>
              db.copy(entries = db.entries.map(x => if same(x.path, from) then x.copy(path = to) else x))
          Right((cap(merged), to))
        case _ =>
          Left(s"zam: '$oldRaw' is ambiguous (${hits.length} entries match); give the full exact path")

  /** Parsed import outcome. `skipped` counts lines that were recognized as
    * commands/directives but contain no usable directory (`cd` with no
    * argument, git commands, ...) and lines that are not absolute paths. */
  final case class Imported(paths: Seq[String], skipped: Int)

  /** Extract candidate directories from a shell-history line. Strict on
    * purpose: a line is used only when it is a bare absolute path, or a
    * `cd`-family / zoxide command whose (first) argument is an absolute path.
    * Everything else — `git commit`, `ls -la`, export statements — is
    * skipped, never invented into a directory. Handles PowerShell history
    * (`Set-Location`, `sl`, `chdir`, `pushd`) as well as bash/zsh `cd`. */
  def parseHistoryLine(rawLine: String): Option[String] =
    var line = rawLine.trim
    if line.isEmpty || line.startsWith("#") then return None
    // chained commands: only the first segment can carry the cwd change
    val bang = line.indexOf("&&")
    if bang >= 0 then line = line.take(bang).trim
    val semi = line.indexOf(';')
    if semi >= 0 then line = line.take(semi).trim
    if line.isEmpty then return None
    // strip one leading command word: cd-family and z/zoxide wrappers
    val cmdWords = Seq("cd", "z", "zi", "zd", "zoxide", "pushd", "popd", "chdir", "sl",
      "set-location", "set-location -literalpath", "go")
    val lower = line.toLowerCase(Locale.ROOT)
    val stripped = cmdWords.sortBy(-_.length).find(w =>
      lower == w || lower.startsWith(w + " ") || lower.startsWith(w + "\t"))
    val candidate = stripped match
      case Some(w) => line.drop(w.length).trim
      case None    => line
    // a quoted argument may contain spaces — take the quoted span as-is
    for q <- Seq('"', '\'') do
      if candidate.startsWith(q.toString) then
        val close = candidate.indexOf(q, 1)
        if close > 1 then
          val inner = candidate.substring(1, close)
          return if isAbsolute(inner) then Some(inner) else None
        return None // unterminated quote
    // drop flags like - or --physical; an argument of "-" means "previous dir"
    val toks = candidate.split("\\s+").toVector.dropWhile(_.startsWith("-"))
    if toks.isEmpty then return None
    val path = toks.head
    if isAbsolute(path) then Some(path) else None

  /** True for absolute paths: platform-absolute (drive-letter, UNC, POSIX
    * root) or `~`-rooted. Relative names are rejected so a shell history can
    * never invent directories under the import-time working directory. */
  def isAbsolute(raw: String): Boolean =
    val p = raw.replace('\\', '/')
    if p == "~" || p.startsWith("~/") || p.startsWith("//") then true
    else if p.length >= 3 && p.charAt(1) == ':' && (p.charAt(2) == '/' || p.charAt(2) == '\\') then true
    else
      try Paths.get(raw).isAbsolute
      catch case _: Exception => false

  /** zoxide-style pruning: entries whose directory no longer exists AND whose
    * last visit is older than `maxAgeDays` are forgotten. `maxAgeDays < 0`
    * forgets every non-existent entry regardless of age. */
  def prune(db: Db, maxAgeDays: Long): Db =
    val now = System.currentTimeMillis() / 1000L
    val keep = db.entries.filter { e =>
      val exists = Files.isDirectory(Paths.get(e.path))
      val tooOld = e.lastVisit match
        case Some(last) => now - last > maxAgeDays * 86400L
        case None       => true
      exists || !tooOld
    }
    cap(db.copy(entries = keep))

  // ---------------------------------------------------------------- codec

  /** Plain-JSON encoding of the database. */
  object DbCodec:
    import Json.Value

    def encode(db: Db): String =
      val cfg = Value.obj(
        "alpha" -> Value.num(db.config.alpha),
        "tau" -> Value.num(db.config.tau),
        "max_visits" -> Value.num(db.config.maxVisits.toDouble)
      )
      val entries = Value.arr(db.entries.map { e =>
        Value.obj(
          "p" -> Value.str(e.path),
          "t" -> Value.arr(e.t.map(ts => Value.num(ts.toDouble)).toVector)
        )
      }.toVector)
      val aliases = Value.obj(db.aliases.map { case (name, path) => name -> Value.str(path) }*)
      Json.render(Value.obj(
        "version" -> Value.num(1),
        "config" -> cfg,
        "aliases" -> aliases,
        "entries" -> entries
      ))

    def decode(text: String): Option[Db] =
      try
        val root = Json.parse(text).obj.toMap
        val cfg = root.get("config").map(_.obj.toMap).map { m =>
          def num(key: String, dflt: Double): Double =
            m.get(key).flatMap(_.asDoubleOpt).getOrElse(dflt)
          Config(
            alpha = num("alpha", Config.Default.alpha),
            tau = num("tau", Config.Default.tau),
            maxVisits = num("max_visits", Config.Default.maxVisits.toDouble).toLong
          )
        }.getOrElse(Config.Default)
        val entries = root.get("entries").map(_.arr.map { entry =>
          val m = entry.obj.toMap
          Entry(
            path = m.get("p").map(_.str).getOrElse(""),
            t = m.get("t").map(_.arr.flatMap(_.asLongOpt)).getOrElse(Vector.empty)
          )
        }).getOrElse(Vector.empty)
        val aliases = root.get("aliases").map(_.obj.collect {
          case (name, p: Value.Str) if name.trim.nonEmpty && p.str.nonEmpty => (name.trim, p.str)
        }).getOrElse(Vector.empty)
        Some(Db(cfg, entries.filter(e => e.path.nonEmpty && e.t.nonEmpty), aliases))
      catch case _: Exception => None