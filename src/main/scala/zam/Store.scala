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

  /** Canonical absolute path (keeps original casing for display). */
  def canonicalize(raw: String): String =
    Paths.get(raw).toAbsolutePath.normalize.toString

  /** Case-normalized comparison key (Windows is case-insensitive). */
  def key(p: String): String =
    if isWindows then p.toLowerCase(Locale.ROOT) else p

  def same(a: String, b: String): Boolean = key(a) == key(b)

  // ---------------------------------------------------------------- loading

  def load(dir: Path): Db =
    val f = dir.resolve("db.json")
    if !Files.isRegularFile(f) then Db(Config.Default, Vector.empty)
    else
      val text = new String(Files.readAllBytes(f), StandardCharsets.UTF_8)
      DbCodec.decode(text).getOrElse(Db(Config.Default, Vector.empty))

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
      Json.render(Value.obj("version" -> Value.num(1), "config" -> cfg, "entries" -> entries))

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
        Some(Db(cfg, entries.filter(e => e.path.nonEmpty && e.t.nonEmpty)))
      catch case _: Exception => None