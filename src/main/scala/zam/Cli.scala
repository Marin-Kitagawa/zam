package zam

import java.nio.file.{Files, Path, Paths}
import scala.collection.mutable.ListBuffer

object Cli:

  val Version = "0.1.0"

  final case class Parsed(flags: Map[String, String], positionals: Vector[String])

  private def flag(p: Parsed, name: String): Boolean = p.flags.contains(name)

  private def nowEpoch: Long = System.currentTimeMillis() / 1000L

  private def errln(s: String): Unit = System.err.println(s)

  private def fmt(d: Double): String = f"$d%.3f"

  def run(args: IndexedSeq[String]): Int =
    try runInner(args)
    catch
      case t: Throwable =>
        errln(s"zam: error: ${if t.getMessage != null then t.getMessage else t.getClass.getSimpleName}")
        1

  private def runInner(args: IndexedSeq[String]): Int =
    val (dirOpt, rest) = peelGlobal(args)
    val dir = dirOpt.getOrElse(Store.dataDir)
    if rest.isEmpty then
      printHelp()
      0
    else
      rest.head match
        case "-h" | "--help" | "help"     => printHelp(); 0
        case "-V" | "--version" | "version" => println(s"zam $Version"); 0
        case "add"                         => cmdAdd(dir, rest.tail.toVector)
        case "query" | "q"                 => cmdQuery(dir, rest.tail.toVector)
        case "list" | "ls"                 => cmdList(dir, rest.tail.toVector)
        case "remove" | "rm"               => cmdRemove(dir, rest.tail.toVector)
        case "prune"                       => cmdPrune(dir, rest.tail.toVector)
        case "explain" | "why"             => cmdExplain(dir, rest.tail.toVector)
        case "stats"                       => cmdStats(dir)
        case "config"                      => cmdConfig(dir, rest.tail.toVector)
        case "shell"                       => cmdShell(rest.tail.toVector)
        case "import"                      => cmdImport(dir, rest.tail.toVector)
        case "export"                      => cmdExport(dir, rest.tail.toVector)
        case "demo"                        => cmdDemo()
        case other                         => bare(dir, other, rest.tail.toVector)

  /** Peel `--data-dir` (either form) out of the raw argument stream. */
  private def peelGlobal(args: Seq[String]): (Option[Path], Vector[String]) =
    var dir: Option[Path] = None
    val buf = ListBuffer.empty[String]
    val it = args.iterator
    while it.hasNext do
      val a = it.next()
      if a == "--data-dir" then
        if it.hasNext then dir = Some(Paths.get(it.next()))
      else if a.startsWith("--data-dir=") then
        dir = Some(Paths.get(a.substring("--data-dir=".length)))
      else buf += a
    (dir, buf.toVector)

  /** Split arguments into flags (`-x` / `--name` / `--name=value`) and
    * positionals. `--` forces the remainder to be positional. */
  def divide(args: Seq[String]): Parsed =
    var flags = Map.empty[String, String]
    var pos = Vector.empty[String]
    val it = args.iterator
    var afterDash = false
    while it.hasNext do
      val a = it.next()
      if afterDash then pos :+= a
      else if a == "--" then afterDash = true
      else if a.startsWith("--") then
        val eq = a.indexOf('=')
        if eq > 0 then flags += ((a.substring(2, eq), a.substring(eq + 1)))
        else flags += ((a.substring(2), ""))
      else if a.startsWith("-") && a.length > 1 then flags += ((a.substring(1), ""))
      else pos :+= a
    Parsed(flags, pos)

  // ------------------------------------------------------------------ help

  private def printHelp(): Unit =
    println(s"""zam $Version - a smarter cd with full-history frecency
               |
               |Usage:
               |  zam <dir>          if <dir> exists: record it; else jump to best match
               |  zam <query>        jump to the best-matching directory
               |
               |Commands:
               |  add <path>...      record visits      (--no-verify: skip existence check)
|  query [<query>]    print best match   (-l/-s/-a, --max-results=N,
      |                                        --exclude=PATH[,PATH])
      |  query -i | --interactive   interactive picker: fuzzy search, regex (ctrl-r),
      |                    or select by id (type a number)
               |  list               list remembered dirs, best first
               |  remove <path>...   forget paths
               |  prune              forget gone dirs last seen --max-age=DAYS ago (default 90);
               |                     --dry-run shows what would be removed
               |  explain <query>    show exactly why each candidate ranks where it does
               |  stats              database statistics
               |  config [k=v ...]   view or set alpha, tau, max_visits (e.g. config alpha=1.5)
               |  shell              print PowerShell integration (adds a `z` function)
               |  import <file>      add dirs from a shell history / log file
               |  export [<file>]    write remembered paths, one per line
               |  demo               side-by-side: zam scoring vs zoxide scoring
               |  version            print version
               |
               |Global flags:
               |  --data-dir <dir>   database location (env: ZAM_DATA_DIR)
               |
               |Matching (same as zoxide): case-insensitive, all terms present in order,
               |and the last term must land in the path's last component.
               |
               |Scoring: full visit history aged by a power law -
               |  score = sum over visits of (1 + age/tau)^(-alpha)   (tau=1 day, alpha=1)
               |whereas zoxide stores only (count, last access) and buckets the count.
               |""".stripMargin)

  // ------------------------------------------------------------------ add

  private def cmdAdd(dir: Path, args: Vector[String]): Int =
    val p = divide(args)
    val noVerify = flag(p, "no-verify")
    val paths = p.positionals
    if paths.isEmpty then
      errln("zam: 'add' requires at least one directory path")
      2
    else
      val db = Store.load(dir)
      val (nd, _) = Store.add(db, paths, !noVerify, nowEpoch)
      Store.save(dir, nd)
      if !noVerify then
        paths.foreach { raw =>
          if !Files.isDirectory(Paths.get(raw)) then errln(s"zam: not a directory, skipped: $raw")
        }
      0

  // ---------------------------------------------------------------- query

  private def cmdQuery(dir: Path, args: Vector[String]): Int =
    val p = divide(args)
    val all = flag(p, "a") || flag(p, "all")
    val withScore = flag(p, "s") || flag(p, "score")
    val interactive = flag(p, "i") || flag(p, "interactive")
    val listOnly = flag(p, "l") || flag(p, "list")
    val maxResults = p.flags.get("max-results").flatMap(_.toIntOption).getOrElse(-1)
    val excludes = p.flags.get("exclude").toVector.flatMap(_.split(',')).map(_.trim).filter(_.nonEmpty)
    val terms = p.positionals.mkString(" ").trim

    if terms == "-" then
      sys.env.get("OLDPWD") match
        case Some(prev) if Files.isDirectory(Paths.get(prev)) =>
          println(prev)
          0
        case _ =>
          errln("zam: no previous directory")
          1
    else
      val db = Store.load(dir)
      val now = nowEpoch
      val candidates = db.entries.filter { e =>
        (all || Files.isDirectory(Paths.get(e.path))) &&
        !excludes.exists(x => Store.same(e.path, Store.canonicalize(x))) &&
        (terms.isEmpty || Matcher.matches(e.path, terms))
      }
      val ranked = candidates
        .map(e => (e, Score.frecency(e, now, db.config)))
        .sortWith(rankLess)
      val limited = if maxResults > 0 then ranked.take(maxResults) else ranked

      if interactive then
        Picker.run(limited.map { case (e, s) => Picker.Item(e.path, s) }) match
          case Picker.Result.Chosen(path) =>
            println(path)
            0
          case Picker.Result.Cancelled =>
            if limited.isEmpty then errln("zam: no match")
            else errln("zam: cancelled")
            1
          case Picker.Result.Unavailable =>
            limited.foreach { (e, s) => println(f"${s}%.3f\t${e.path}") }
            if limited.isEmpty then
              errln("zam: no match")
              1
            else 0
      else if listOnly then
        limited.foreach { (e, s) =>
          if withScore then println(f"${s}%.3f\t${e.path}") else println(e.path)
        }
        if limited.isEmpty then
          errln("zam: no match")
          1
        else 0
      else
        limited.headOption match
          case Some((e, _)) =>
            println(e.path)
            0
          case None =>
            errln(s"zam: no match${if terms.nonEmpty then s" for '$terms'" else ""}")
            1

  private def cmdList(dir: Path, args: Vector[String]): Int =
    cmdQuery(dir, args ++ Vector("--list", "--all"))

  private def rankLess(a: (Model.Entry, Double), b: (Model.Entry, Double)): Boolean =
    val (ea, sa) = a
    val (eb, sb) = b
    if sa != sb then sa > sb
    else
      val da = pathDepth(ea.path)
      val db2 = pathDepth(eb.path)
      if da != db2 then da < db2
      else if ea.path.length != eb.path.length then ea.path.length < eb.path.length
      else ea.path < eb.path

  private def pathDepth(p: String): Int = p.replace('\\', '/').count(_ == '/')

  // --------------------------------------------------------------- remove

  private def cmdRemove(dir: Path, args: Vector[String]): Int =
    val p = divide(args)
    if p.positionals.isEmpty then
      errln("zam: 'remove' requires at least one path")
      2
    else
      val db = Store.load(dir)
      val (nd, gone) = Store.remove(db, p.positionals)
      Store.save(dir, nd)
      if gone == 0 then
        errln("zam: nothing removed")
        1
      else 0

  // ---------------------------------------------------------------- prune

  private def cmdPrune(dir: Path, args: Vector[String]): Int =
    val p = divide(args)
    val dryRun = flag(p, "dry-run") || flag(p, "n")
    val maxAge = p.flags.get("max-age").flatMap(_.toLongOption).getOrElse(90L)
    val db = Store.load(dir)
    val now = nowEpoch
    val doomed = db.entries.filter { e =>
      !Files.isDirectory(Paths.get(e.path)) &&
      e.lastVisit.exists(last => now - last > maxAge * 86400L)
    }
    if dryRun then
      errln(s"zam: would remove ${doomed.length} entr${if doomed.length == 1 then "y" else "ies"}")
      doomed.foreach(e => println(e.path))
      0
    else
      val before = db.entryCount
      val nd = Store.prune(db, maxAge)
      Store.save(dir, nd)
      errln(s"zam: removed ${before - nd.entryCount} entr${if before - nd.entryCount == 1 then "y" else "ies"}, retained ${nd.entryCount} (${nd.totalVisits} visits)")
      0

  // --------------------------------------------------------------- explain

  private def cmdExplain(dir: Path, args: Vector[String]): Int =
    val p = divide(args)
    val all = flag(p, "a") || flag(p, "all")
    val terms = p.positionals.mkString(" ").trim
    if terms.isEmpty then
      errln("zam: 'explain' requires a query")
      2
    else
      val db = Store.load(dir)
      val now = nowEpoch
      val cfg = db.config
      val matches = db.entries
        .filter(e => (all || Files.isDirectory(Paths.get(e.path))) && Matcher.matches(e.path, terms))
        .map(e => (e, Score.frecency(e, now, cfg)))
        .sortWith(rankLess)
      if matches.isEmpty then
        errln(s"zam: no match for '$terms'")
        1
      else
        matches.foreach { case (e, s) =>
          val lastAge = e.lastVisit.map(now - _).getOrElse(0L)
          val zox = e.lastVisit.map(lv => Score.zoxideModel(e.count, (now - lv).max(0))).getOrElse(0.0)
          val lastW = Score.visitWeight(lastAge.toDouble, cfg)
          println(e.path)
          println(f"    score            = ${s}%.3f   (zoxide-model: ${zox}%.0f)")
          println(f"    visits           = ${e.count}   last ${Score.humanAge(lastAge)}")
          println(f"    last-visit weight= ${lastW}%.3f   history weight = ${s - lastW}%.3f")
          val recent = e.t.takeRight(5).map(ts => (now - ts).max(0L))
          if recent.nonEmpty then
            println("    recent visits    = " + recent.map(a => s"${Score.humanAge(a)} -> ${fmt(Score.visitWeight(a.toDouble, cfg))}").mkString(", "))
        }
        0

  // ---------------------------------------------------------------- stats

  private def cmdStats(dir: Path): Int =
    val db = Store.load(dir)
    val now = nowEpoch
    val cfg = db.config
    val allVisits = db.entries.flatMap(_.t)
    val oldest = allVisits.reduceOption(_ min _)
    val newest = allVisits.reduceOption(_ max _)
    println(s"data file   : ${dir.resolve("db.json")}")
    println(s"entries     : ${db.entryCount}")
    println(s"total visits: ${db.totalVisits}")
    println(s"history span: ${oldest.map(v => Score.humanAge((now - v).max(0))).getOrElse("(empty)")} .. " +
      s"${newest.map(v => Score.humanAge((now - v).max(0))).getOrElse("(empty)")}")
    println(f"config      : alpha=${cfg.alpha}%.2f   tau=${cfg.tau}%.0fs   max_visits=${cfg.maxVisits}")
    val top = db.entries.map(e => (e, Score.frecency(e, now, cfg))).sortWith(rankLess).take(5)
    if top.nonEmpty then
      println("top 5")
      top.foreach { case (e, s) => println(f"  $s%8.3f  ${e.path}") }
    0

  // ---------------------------------------------------------------- config

  private def cmdConfig(dir: Path, args: Vector[String]): Int =
    val p = divide(args)
    val pos = p.positionals
    val db = Store.load(dir)
    if pos.isEmpty then
      val c = db.config
      val shown = if c.tau == c.tau.floor then c.tau.toLong.toString else c.tau.toString
      println(f"alpha=${c.alpha}")
      println(s"tau=$shown")
      println(s"max_visits=${c.maxVisits}")
      println("set values with: zam config alpha=1.5 tau=43200 max_visits=50000   (or: config reset)")
      0
    else if pos.length == 1 && pos.head == "reset" then
      Store.save(dir, db.copy(config = Model.Config.Default))
      errln("zam: config restored to defaults")
      0
    else
      var cfg = db.config
      var ok = true
      for kv <- pos do
        val i = kv.indexOf('=')
        if i <= 0 then
          errln(s"zam: invalid setting '$kv' (expected key=value)")
          ok = false
        else
          val k = kv.substring(0, i).trim
          val v = kv.substring(i + 1).trim
          (k, v) match
            case ("alpha", x) =>
              x.toDoubleOption.filter(_ > 0) match
                case Some(d) => cfg = cfg.copy(alpha = d)
                case None    => errln(s"zam: invalid alpha '$x' (expected a positive number)"); ok = false
            case ("tau", x) =>
              x.toDoubleOption.filter(_ > 0) match
                case Some(d) => cfg = cfg.copy(tau = d)
                case None    => errln(s"zam: invalid tau '$x' (expected seconds, e.g. 86400)"); ok = false
            case ("max_visits", x) =>
              x.toLongOption.filter(_ > 0) match
                case Some(n) => cfg = cfg.copy(maxVisits = n)
                case None    => errln(s"zam: invalid max_visits '$x' (expected a positive integer)"); ok = false
            case _ =>
              errln(s"zam: unknown setting '$k' (known: alpha, tau, max_visits)")
              ok = false
      if ok then
        Store.save(dir, db.copy(config = cfg))
        errln("zam: config updated")
        0
      else 2

  // ---------------------------------------------------------------- shell

  private def cmdShell(args: Vector[String]): Int =
    val p = divide(args)
    val which = p.positionals.headOption.getOrElse("powershell")
    which match
      case "powershell" =>
        println(Shell.powershellSnippet)
        0
      case other =>
        errln(s"zam: unsupported shell '$other' (only 'powershell' is bundled)")
        2

  // ----------------------------------------------------------- import/export

  private def cmdImport(dir: Path, args: Vector[String]): Int =
    val p = divide(args)
    p.positionals.headOption match
      case None =>
        errln("zam: 'import' requires a file path")
        2
      case Some(file) =>
        val f = Paths.get(file)
        if !Files.isRegularFile(f) then
          errln(s"zam: no such file: $file")
          2
        else
          val lines = Files.readAllLines(f, java.nio.charset.StandardCharsets.UTF_8)
          val paths = ListBuffer.empty[String]
          lines.forEach { raw =>
            var line = raw.trim
            if line.nonEmpty && !line.startsWith("#") then
              if line.startsWith("cd ") || line.startsWith("cd\t") then line = line.drop(3).trim
              val bang = line.indexOf("&&")
              if bang >= 0 then line = line.take(bang).trim
              val semi = line.indexOf(';')
              if semi >= 0 then line = line.take(semi).trim
              if line.length >= 2 && line.startsWith("\"") && line.endsWith("\"") then line = line.drop(1).dropRight(1)
              if line.length >= 2 && line.startsWith("'") && line.endsWith("'") then line = line.drop(1).dropRight(1)
              if line.nonEmpty then paths += line
          }
          val db = Store.load(dir)
          val (nd, added) = Store.add(db, paths.toSeq, verify = false, nowEpoch)
          Store.save(dir, nd)
          errln(s"zam: imported $added path(s) from $file")
          0

  private def cmdExport(dir: Path, args: Vector[String]): Int =
    val p = divide(args)
    val name = p.positionals.headOption.getOrElse("zam-export.txt")
    Files.createDirectories(dir)
    val target = dir.resolve(name)
    val db = Store.load(dir)
    val now = nowEpoch
    val ranked = db.entries
      .map(e => (e, Score.frecency(e, now, db.config)))
      .sortWith(rankLess)
      .map(_._1.path)
    Files.writeString(target, ranked.mkString("", System.lineSeparator(), System.lineSeparator()))
    errln(s"zam: exported ${ranked.length} path(s) to $target")
    0

  // ------------------------------------------------------------------ demo

  /** A reproducible head-to-head: two directory histories that are
    * indistinguishable under zoxide's model but clearly different under zam's. */
  private def cmdDemo(): Int =
    val now = System.currentTimeMillis() / 1000L
    val day = 86400L
    val hour = 3600L
    val cfg = Model.Config.Default
    // A: 100 visits within the last week, plus one now
    val visitsA = (1 to 100).map(i => now - ((i % 7) + 1) * day).toVector :+ now
    // B: 100 visits spread over 90 days, plus one now
    val visitsB = (1 to 100).map(i => now - ((i % 90) + 1) * day).toVector :+ now
    // C: 6 visits today and nothing older
    val visitsC = (1 to 6).map(i => now - i * 2 * hour).toVector

    def scale(v: Vector[Long]): (Double, Double, Long, String) =
      val e = Model.Entry("scenario", v)
      val lastAge = e.lastVisit.map(now - _).getOrElse(0L)
      (Score.frecency(e, now, cfg), Score.zoxideModel(e.count, lastAge.max(0)), e.count, Score.humanAge(lastAge))

    val rows = Vector(
      ("A  (100 visits within the last week + now)", visitsA),
      ("B  (100 visits spread over 90 days + now)", visitsB),
      ("C  (6 visits today, nothing older)", visitsC)
    )
    println("zam vs zoxide: why full history beats (count, last-access)")
    println("")
    println(f"${"scenario"}%-52s${"n"}%5s${"last"}%10s${"zoxide"}%8s${"zam"}%9s")
    rows.foreach { case (name, v) =>
      val (zs, zx, n, last) = scale(v)
      println(f"$name%-52s$n%5d$last%10s$zx%8.0f$zs%9.3f")
    }
    println("")
    println("zoxide forces A and B to tie (same count, same last access -> both count*4)")
    println("but zam ages every visit individually: recent momentum (A) ranks above")
    println("long-forgotten history (B). C shows that recency still wins out.")
    0

  // ---------------------------------------------------------------- bare

  private def bare(dir: Path, first: String, rest: Vector[String]): Int =
    if Files.isDirectory(Paths.get(first)) then cmdAdd(dir, first +: rest)
    else cmdQuery(dir, first +: rest)