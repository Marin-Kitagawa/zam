package zam

import java.nio.file.{Files, Path, Paths}
import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters.*

import Json.Value

object Cli:

  /** Version reported by `zam version`. The packaged jar carries the sbt
    * version (the release workflow sets it from the git tag); `sbt run` and
    * the test runner fall back to the development version. */
  val Version: String =
    Option(getClass.getPackage)
      .flatMap(p => Option(p.getImplementationVersion))
      .map(_.trim)
      .filter(_.nonEmpty)
      .getOrElse("0.3.0")

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
        case "recent"                      => cmdRecent(dir, rest.tail.toVector)
        case "complete"                    => cmdComplete(dir, rest.tail.toVector)
        case "remove" | "rm"               => cmdRemove(dir, rest.tail.toVector)
        case "prune"                       => cmdPrune(dir, rest.tail.toVector)
        case "explain" | "why"             => cmdExplain(dir, rest.tail.toVector)
        case "stats"                       => cmdStats(dir)
        case "config"                      => cmdConfig(dir, rest.tail.toVector)
        case "alias"                       => cmdAlias(dir, rest.tail.toVector)
        case "move" | "mv"                 => cmdMove(dir, rest.tail.toVector)
        case "doctor"                      => cmdDoctor(dir)
        case "shell"                       => cmdShell(rest.tail.toVector)
        case "import"                      => cmdImport(dir, rest.tail.toVector)
        case "export"                      => cmdExport(dir, rest.tail.toVector)
        case "demo"                        => cmdDemo()
        case other                         => bare(dir, other, rest.tail.toVector)

  /** Load the database, reporting corruption loudly (once per run). */
  private def db(dir: Path): Model.Db =
    val (loaded, warning) = Store.loadReport(dir)
    warning.foreach(errln)
    loaded

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

  /** Flags that consume the following token as their value, so both
    * `--regex=foo` and `--regex foo` work. */
  private val ValueFlags =
    Set("max-results", "exclude", "regex", "cwd", "limit", "max-age")

  /** Split arguments into flags (`-x` / `--name` / `--name=value`) and
    * positionals. `--` forces the remainder to be positional. */
  def divide(args: Seq[String]): Parsed =
    val raw = args.toIndexedSeq
    var flags = Map.empty[String, String]
    var pos = Vector.empty[String]
    var i = 0
    var afterDash = false
    while i < raw.length do
      val a = raw(i)
      if afterDash then pos :+= a
      else if a == "--" then afterDash = true
      else if a.startsWith("--") then
        val eq = a.indexOf('=')
        if eq > 0 then flags += ((a.substring(2, eq), a.substring(eq + 1)))
        else
          val name = a.substring(2)
          if ValueFlags.contains(name) && i + 1 < raw.length && !isFlagLike(raw(i + 1)) then
            flags += ((name, raw(i + 1)))
            i += 1
          else flags += ((name, ""))
      else if a.startsWith("-") && a.length > 1 then flags += ((a.substring(1), ""))
      else pos :+= a
      i += 1
    Parsed(flags, pos)

  private def isFlagLike(s: String): Boolean =
    s.length > 1 && s.startsWith("-") &&
      !(s.charAt(1).isDigit || s.charAt(1) == '.') // -1 / -.5 are values

  // ------------------------------------------------------------------ help

  private def printHelp(): Unit =
    println(s"""zam $Version - a smarter cd with full-history frecency
               |
               |Usage:
               |  zam <dir>          if <dir> exists: record it; else jump to best match
               |  zam <query>        jump to the best-matching directory
               |                     (an exact alias name beats fuzzy matching)
               |
               |Commands:
               |  add <path>...      record visits      (--no-verify: skip existence check)
               |  query [<query>]    print best match   (-l list, -s list+scores, -a all,
               |                                        --json, --regex=PATTERN, --print0,
               |                                        --max-results=N, --exclude=P1,P2)
               |  query -i | --interactive   interactive picker: fuzzy search, regex (ctrl-r),
               |                    or select by id (type a number)
               |  list               list remembered dirs, best first
               |  recent [N]         most recently visited dirs (true recency; --all, --json)
               |  complete [<word>]  tab-completion candidates (--cwd=DIR, --limit=N,
               |                     --all, --json) - used by the shell integrations
               |  remove <path>...   forget paths
               |  move <old> <new>   re-point a remembered dir (histories merge)
               |  alias [<n> <tgt>]  named shortcuts: `zam alias work C:\\src\\job`,
               |                     `zam alias work <query>`; list: `zam alias`,
               |                     resolve: `zam alias work`, drop: `alias -d work`
               |  prune              forget gone dirs last seen --max-age=DAYS ago (default 90);
               |                     --dry-run shows what would be removed
               |  explain <query>    show exactly why each candidate ranks where it does
               |  stats              database statistics
               |  doctor             check the database for problems
               |  config [k=v ...]   view or set alpha, tau, max_visits (e.g. config alpha=1.5)
               |  shell [<name>]     print shell integration: powershell (default), bash,
               |                     zsh, fish
               |  import <file>      add dirs from a shell history / log file (--verify:
               |                     only record paths that still exist; '-' reads stdin)
               |  export [<file>]    write remembered paths, one per line (relative paths
               |                     land in the current directory)
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
      val database = db(dir)
      val (nd, _) = Store.add(database, paths, !noVerify, nowEpoch)
      Store.save(dir, nd)
      val skipped =
        if noVerify then Vector.empty
        else paths.filterNot(raw => isDirectory(raw))
      skipped.foreach(raw => errln(s"zam: not a directory, skipped: $raw"))
      // non-zero when anything was rejected, so scripts can tell
      if skipped.nonEmpty then 1 else 0

  /** Existence check that expands `~` and tolerates junk input, so the
    * verification path agrees with what `Store.add` actually records. */
  private def isDirectory(raw: String): Boolean =
    try Files.isDirectory(Paths.get(Store.canonicalize(raw)))
    catch case _: Exception => false

  // ---------------------------------------------------------------- query

  private def cmdQuery(dir: Path, args: Vector[String]): Int =
    val p = divide(args)
    val all = flag(p, "a") || flag(p, "all")
    val withScore = flag(p, "s") || flag(p, "score")
    val interactive = flag(p, "i") || flag(p, "interactive")
    val asJson = flag(p, "json")
    val print0 = flag(p, "0") || flag(p, "print0")
    val listOnly = flag(p, "l") || flag(p, "list") || (withScore && !interactive) // -s means "list with scores"
    val maxResults =
      p.flags.get("max-results") match
        case None => -1
        case Some(v) =>
          v.toIntOption.filter(_ > 0) match
            case Some(n) => n
            case None =>
              errln(s"zam: invalid --max-results '$v' (expected a positive integer)")
              return 2
    val excludes = p.flags.get("exclude").toVector.flatMap(_.split(',')).map(_.trim).filter(_.nonEmpty)
    val terms = p.positionals.mkString(" ").trim
    val regex = p.flags.get("regex").map(_.trim).filter(_.nonEmpty)

    if p.flags.contains("regex") && regex.isEmpty then
      errln("zam: --regex requires a pattern, e.g. --regex='^C:\\\\src'")
      return 2

    def emit(s: String): Unit =
      if print0 then
        Console.out.print(s)
        Console.out.print('\u0000')
      else Console.out.println(s)

    if terms == "-" then
      sys.env.get("OLDPWD") match
        case Some(prev) if Files.isDirectory(Paths.get(prev)) =>
          emit(prev)
          0
        case _ =>
          errln("zam: no previous directory")
          1
    else
      // compile the optional regex once; a bad pattern is a usage error
      val regexOk: String => Boolean =
        regex match
          case None => _ => true
          case Some(rx) =>
            try
              val compiled = java.util.regex.Pattern.compile(
                rx,
                java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE
              )
              path => compiled.matcher(path).find()
            catch
              case e: java.util.regex.PatternSyntaxException =>
                errln(s"zam: invalid regex '$rx': ${e.getDescription}")
                return 2

      val fuzzyOk: String => Boolean =
        if terms.isEmpty || regex.nonEmpty && p.positionals.isEmpty then _ => true
        else path => Matcher.matches(path, terms)

      val database = db(dir)
      val now = nowEpoch

      // an exact alias name beats fuzzy matching: `zam work` goes where
      // `zam alias work ...` pointed it. Listing/picker/json modes keep
      // their normal semantics.
      if terms.nonEmpty && regex.isEmpty && !listOnly && !interactive && !asJson then
        database.findAlias(terms) match
          case Some(path) =>
            if Files.isDirectory(Paths.get(path)) || all then
              emit(path)
              return 0
            else
              errln(s"zam: alias '$terms' points to a directory that no longer exists: $path")
              errln("zam: (re-point it: zam alias " + terms + " <path>, or drop it: zam alias -d " + terms + ")")
              return 1
          case None => ()

      val candidates = database.entries.filter { e =>
        (all || Files.isDirectory(Paths.get(e.path))) &&
        !isExcluded(e.path, excludes) &&
        fuzzyOk(e.path) &&
        regexOk(e.path)
      }
      val ranked = candidates
        .map(e => (e, Score.frecency(e, now, database.config)))
        .sortWith(rankLess)
      val limited = if maxResults > 0 then ranked.take(maxResults) else ranked

      if asJson then
        println(Json.render(Value.arr(limited.map { case (e, s) =>
          Value.obj(
            "path" -> Value.str(e.path),
            "score" -> Value.num(s),
            "visits" -> Value.num(e.count.toDouble),
            "last" -> Value.num(e.lastVisit.getOrElse(0L).toDouble)
          )
        }.toVector), pretty = false))
        if limited.isEmpty then 1 else 0
      else if interactive then
        Picker.run(limited.map { case (e, s) => Picker.Item(e.path, s) }) match
          case Picker.Result.Chosen(path) =>
            emit(path)
            0
          case Picker.Result.Cancelled =>
            if limited.isEmpty then errln("zam: no match")
            else errln("zam: cancelled")
            1
          case Picker.Result.Unavailable =>
            // not interactive (no tty, terminal too small): the scored list is
            // for the human on stderr, the best path stays on stdout so shell
            // wrappers can still cd to it
            if limited.isEmpty then
              errln("zam: no match")
              1
            else
              limited.foreach { (e, s) => errln(f"${s}%.3f\t${e.path}") }
              emit(limited.head._1.path)
              0
      else if listOnly then
        limited.foreach { (e, s) =>
          if withScore then emit(f"${s}%.3f\t${e.path}") else emit(e.path)
        }
        if limited.isEmpty then
          errln("zam: no match")
          1
        else 0
      else
        limited.headOption match
          case Some((e, _)) =>
            emit(e.path)
            0
          case None =>
            errln(s"zam: no match${if terms.nonEmpty then s" for '$terms'" else ""}")
            1

  /** `--exclude` accepts absolute paths (that path and everything under it)
    * and plain names (a path component with that exact name). */
  private def isExcluded(path: String, excludes: Vector[String]): Boolean =
    val pKey = Store.key(path)
    excludes.exists { raw =>
      val x = raw.trim
      if x.isEmpty then false
      else if Store.isAbsolute(x) then
        try
          val xKey = Store.key(Store.canonicalize(x))
          pKey == xKey || pKey.startsWith(xKey + "/") || pKey.startsWith(xKey + "\\")
        catch case _: Exception => false
      else
        val xk = Store.key(x)
        pKey.split(raw"[\\/]").exists(_ == xk)
    }

  private def cmdList(dir: Path, args: Vector[String]): Int =
    cmdQuery(dir, args ++ Vector("--list", "--all"))

  // --------------------------------------------------------------- recent

  /** Directories ordered by *when you were last there* (true recency), not by
    * frecency — handy when you want "what I was just working on". */
  private def cmdRecent(dir: Path, args: Vector[String]): Int =
    val p = divide(args)
    val all = flag(p, "a") || flag(p, "all")
    val asJson = flag(p, "json")
    val n = p.positionals.headOption.flatMap(_.toIntOption).filter(_ > 0).getOrElse(20)
    val database = db(dir)
    val now = nowEpoch
    val rows = database.entries.zipWithIndex
      .filter { case (e, _) => (all || Files.isDirectory(Paths.get(e.path))) && e.lastVisit.isDefined }
      // ties (same epoch second) fall back to insertion order, newest first
      .sortBy { case (e, i) => (-e.lastVisit.getOrElse(0L), -i) }
      .take(n)
      .map { case (e, _) => (e, Score.frecency(e, now, database.config)) }
    if asJson then
      println(Json.render(Value.arr(rows.map { case (e, s) =>
        val last = e.lastVisit.getOrElse(0L)
        Value.obj(
          "path" -> Value.str(e.path),
          "last" -> Value.num(last.toDouble),
          "age" -> Value.str(Score.humanAge((now - last).max(0L))),
          "visits" -> Value.num(e.count.toDouble),
          "score" -> Value.num(s)
        )
      }.toVector), pretty = false))
    else rows.foreach { case (e, _) => println(e.path) }
    if rows.isEmpty then
      errln("zam: no remembered directories")
      1
    else 0

  // ------------------------------------------------------------- complete

  /** Tab-completion engine used by every shell snippet. Prints one insertion
    * candidate per line, or rich JSON for shells that can show tooltips.
    * Always exits 0: completion must never interrupt the shell. */
  private def cmdComplete(dir: Path, args: Vector[String]): Int =
    val p = divide(args)
    val all = flag(p, "a") || flag(p, "all")
    val asJson = flag(p, "json")
    val limit = p.flags.get("limit").flatMap(_.toIntOption).filter(_ > 0).getOrElse(100)
    val cwd = p.flags
      .get("cwd")
      .map(_.trim)
      .filter(_.nonEmpty)
      .flatMap(s => try Some(Paths.get(s).toAbsolutePath.normalize) catch case _: Exception => None)
      .filter(Files.isDirectory(_))
      .getOrElse(Paths.get(System.getProperty("user.dir")))
    val word = p.positionals.mkString(" ")
    val database = db(dir)
    val candidates = Completion.suggest(word, cwd, database, nowEpoch, all, limit)
    if asJson then
      println(Json.render(Value.arr(candidates.map { c =>
        Value.obj(
          "value" -> Value.str(c.value),
          "display" -> Value.str(c.display),
          "detail" -> Value.str(c.detail),
          "source" -> Value.str(c.source)
        )
      }.toVector), pretty = false))
    else candidates.foreach(c => println(c.value))
    0

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
      val database = db(dir)
      val (nd, gone) = Store.remove(database, p.positionals)
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
    val database = db(dir)
    val now = nowEpoch
    val doomed = database.entries.filter { e =>
      !Files.isDirectory(Paths.get(e.path)) &&
      e.lastVisit.exists(last => now - last > maxAge * 86400L)
    }
    if dryRun then
      errln(s"zam: would remove ${doomed.length} entr${if doomed.length == 1 then "y" else "ies"}")
      doomed.foreach(e => println(e.path))
      0
    else
      val before = database.entryCount
      val nd = Store.prune(database, maxAge)
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
      val database = db(dir)
      val now = nowEpoch
      val cfg = database.config
      val matches = database.entries
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
    val database = db(dir)
    val now = nowEpoch
    val cfg = database.config
    val allVisits = database.entries.flatMap(_.t)
    val oldest = allVisits.reduceOption(_ min _)
    val newest = allVisits.reduceOption(_ max _)
    println(s"data file   : ${dir.resolve("db.json")}")
    println(s"entries     : ${database.entryCount}")
    println(s"total visits: ${database.totalVisits}")
    println(s"history span: ${oldest.map(v => Score.humanAge((now - v).max(0))).getOrElse("(empty)")} .. " +
      s"${newest.map(v => Score.humanAge((now - v).max(0))).getOrElse("(empty)")}")
    println(f"config      : alpha=${cfg.alpha}%.2f   tau=${cfg.tau}%.0fs   max_visits=${cfg.maxVisits}")
    val top = database.entries.map(e => (e, Score.frecency(e, now, cfg))).sortWith(rankLess).take(5)
    if top.nonEmpty then
      println("top 5")
      top.foreach { case (e, s) => println(f"  $s%8.3f  ${e.path}") }
    0

  // ---------------------------------------------------------------- config

  private def cmdConfig(dir: Path, args: Vector[String]): Int =
    val p = divide(args)
    val pos = p.positionals
    val database = db(dir)
    if pos.isEmpty then
      val c = database.config
      val shown = if c.tau == c.tau.floor then c.tau.toLong.toString else c.tau.toString
      println(f"alpha=${c.alpha}")
      println(s"tau=$shown")
      println(s"max_visits=${c.maxVisits}")
      println("set values with: zam config alpha=1.5 tau=43200 max_visits=50000   (or: config reset)")
      0
    else if pos.length == 1 && pos.head == "reset" then
      Store.save(dir, database.copy(config = Model.Config.Default))
      errln("zam: config restored to defaults")
      0
    else
      var cfg = database.config
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
        Store.save(dir, database.copy(config = cfg))
        errln("zam: config updated")
        0
      else 2

  // ---------------------------------------------------------------- shell

  private def cmdShell(args: Vector[String]): Int =
    val p = divide(args)
    val which = p.positionals.headOption.getOrElse("powershell")
    which match
      case "powershell" | "pwsh" =>
        println(Shell.powershellSnippet)
        0
      case "bash" =>
        println(Shell.bashSnippet)
        0
      case "zsh" =>
        println(Shell.zshSnippet)
        0
      case "fish" =>
        println(Shell.fishSnippet)
        0
      case other =>
        errln(s"zam: unsupported shell '$other' (bundled: powershell, bash, zsh, fish)")
        2

  // ----------------------------------------------------------- import/export

  private def cmdImport(dir: Path, args: Vector[String]): Int =
    val p = divide(args)
    val verify = flag(p, "verify")
    p.positionals.headOption match
      case None =>
        errln("zam: 'import' requires a file path (use '-' to read stdin)")
        2
      case Some(file) =>
        val linesOpt: Option[Vector[String]] =
          if file == "-" then Some(scala.io.Source.stdin.getLines().toVector)
          else
            val f = Paths.get(file)
            if !Files.isRegularFile(f) then
              errln(s"zam: no such file: $file")
              None
            else Some(Files.readAllLines(f, java.nio.charset.StandardCharsets.UTF_8).asScala.toVector)
        linesOpt match
          case None => 2
          case Some(lines) =>
            val paths = ListBuffer.empty[String]
            var skipped = 0
            lines.foreach { raw =>
              // strict parsing: only bare absolute paths and cd-family / zoxide
              // commands yield candidates; everything else is skipped so a
              // whole shell history cannot pollute the database
              Store.parseHistoryLine(raw) match
                case Some(path) => paths += path
                case None       => if raw.trim.nonEmpty && !raw.trim.startsWith("#") then skipped += 1
            }
            val database = db(dir)
            val (nd, added) = Store.add(database, paths.toSeq, verify = verify, nowEpoch)
            Store.save(dir, nd)
            val from = if file == "-" then "stdin" else file
            errln(s"zam: imported $added path(s) from $from" +
              (if skipped > 0 then s" (skipped $skipped line(s): not absolute directory paths)" else ""))
            0

  private def cmdExport(dir: Path, args: Vector[String]): Int =
    val p = divide(args)
    val name = p.positionals.headOption.getOrElse("zam-export.txt")
    // relative targets land in the current directory (absolute paths pass
    // through); only the default file goes to the data directory
    val target =
      val n = Paths.get(name)
      if n.isAbsolute then n
      else if p.positionals.isEmpty then dir.resolve(name)
      else n.toAbsolutePath
    val database = db(dir)
    val now = nowEpoch
    val ranked = database.entries
      .map(e => (e, Score.frecency(e, now, database.config)))
      .sortWith(rankLess)
      .map(_._1.path)
    if target.getParent != null then Files.createDirectories(target.getParent)
    Files.writeString(target, ranked.mkString("", System.lineSeparator(), System.lineSeparator()))
    errln(s"zam: exported ${ranked.length} path(s) to $target")
    0

  // ----------------------------------------------------------------- alias

  private def cmdAlias(dir: Path, args: Vector[String]): Int =
    val p = divide(args)
    val delete = flag(p, "d") || flag(p, "delete") || flag(p, "remove")
    val pos = p.positionals
    val database = db(dir)
    if pos.isEmpty then
      if database.aliases.isEmpty then
        println("(no aliases — set one with: zam alias <name> <path-or-query>)")
      else
        database.aliases.foreach { (name, path) =>
          val exists = Files.isDirectory(Paths.get(path))
          println(f"$name%-24s $path${if exists then "" else "   [gone]"}")
        }
      0
    else if delete then
      if pos.length != 1 then
        errln("zam: 'alias -d' takes exactly one name")
        2
      else if database.findAlias(pos.head).isEmpty then
        errln(s"zam: no such alias: ${pos.head}")
        1
      else
        Store.save(dir, database.withoutAlias(pos.head))
        errln(s"zam: alias '${pos.head}' removed")
        0
    else if pos.length == 1 then
      // `zam alias work` resolves the alias (handy inside scripts)
      database.findAlias(pos.head) match
        case Some(path) => println(path); 0
        case None =>
          errln(s"zam: no such alias: ${pos.head}")
          1
    else if pos.length == 2 then
      val name = pos.head
      if name.isBlank || name.exists(_.isWhitespace) || name.startsWith("-") then
        errln("zam: alias name must be a single word without leading '-'")
        2
      else
        val rawTarget = pos(1)
        val target =
          if Files.isDirectory(Paths.get(rawTarget)) then Store.canonicalize(rawTarget)
          else
            // not a path: treat it as a query against the database
            val now = nowEpoch
            database.entries
              .filter(e => Matcher.matches(e.path, rawTarget) && Files.isDirectory(Paths.get(e.path)))
              .map(e => (e, Score.frecency(e, now, database.config)))
              .sortWith(rankLess)
              .headOption match
                case Some((e, _)) => e.path
                case None =>
                  errln(s"zam: alias target '$rawTarget' is not a directory and matches nothing remembered")
                  return 1
        Store.save(dir, database.withAlias(name, target))
        errln(s"zam: alias '$name' -> $target")
        0
    else
      errln("zam: usage: zam alias [<name> <path-or-query>] | alias -d <name>")
      2

  // ------------------------------------------------------------------ move

  private def cmdMove(dir: Path, args: Vector[String]): Int =
    val p = divide(args)
    val verify = !flag(p, "no-verify")
    val pos = p.positionals
    if pos.length != 2 then
      errln("zam: usage: zam move <old-path> <new-path>")
      2
    else
      val database = db(dir)
      if verify && !Files.isDirectory(Paths.get(pos(1))) then
        errln(s"zam: target is not a directory: ${pos(1)} (use --no-verify to move anyway)")
        1
      else
        Store.move(database, pos.head, pos(1)) match
          case Right((nd, to)) =>
            Store.save(dir, nd)
            errln(s"zam: moved -> $to")
            0
          case Left(msg) =>
            errln(msg)
            1

  // ---------------------------------------------------------------- doctor

  /** Sanity-check the database: structure, duplicates, dead entries, future
    * timestamps, dangling aliases. Exits 1 when anything looks off. */
  private def cmdDoctor(dir: Path): Int =
    val database = db(dir)
    val now = nowEpoch
    var problems = 0
    def report(problem: Boolean, msg: => String): Unit =
      if problem then
        problems += 1
        errln(s"zam: [!] $msg")

    report(database.config.alpha <= 0 || database.config.tau <= 0 || database.config.maxVisits <= 0,
      s"invalid scoring config: alpha=${database.config.alpha} tau=${database.config.tau} max_visits=${database.config.maxVisits}")

    val byKey = database.entries.groupBy(e => Store.key(e.path))
    byKey.valuesIterator.filter(_.length > 1).foreach { dupes =>
      problems += 1
      errln(s"zam: [!] ${dupes.length} entries are the same directory under different spellings:")
      dupes.foreach(e => errln(s"      ${e.path}  (${e.count} visits)"))
      errln("      (clean up with: zam remove <unwanted-spelling>)")
    }

    val dead = database.entries.filterNot(e => Files.isDirectory(Paths.get(e.path)))
    report(dead.nonEmpty, s"${dead.length} remembered path(s) no longer exist" +
      s" (review with: zam query -l -a, then remove, or zam prune)")

    val future = database.entries.flatMap(_.t).filter(_ > now + 3600)
    report(future.nonEmpty, s"${future.length} visit timestamp(s) lie in the future (clock skew?)")

    database.aliases.foreach { (name, path) =>
      report(!Files.isDirectory(Paths.get(path)), s"alias '$name' points to a missing directory: $path" +
        s" (fix: zam alias $name <path>, or drop: zam alias -d $name)")
    }

    if problems == 0 then
      println(s"zam: all good — ${database.entryCount} entr${if database.entryCount == 1 then "y" else "ies"}, " +
        s"${database.totalVisits} visits, ${database.aliases.length} alias(es) in ${dir.resolve("db.json")}")
      0
    else
      errln(s"zam: $problems problem(s) found")
      1

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