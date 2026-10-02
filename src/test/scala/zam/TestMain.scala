package zam

import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters.*

object TestMain:
  private var total = 0
  private var failed = 0

  def check(name: String)(body: => Boolean): Unit =
    total += 1
    try
      if body then println(s"[ok]   $name")
      else
        failed += 1
        println(s"[FAIL] $name")
    catch
      case t: Throwable =>
        failed += 1
        println(s"[ERR ] $name -> $t")

  def eq[A](name: String, actual: A, expected: A): Unit =
    check(name)(actual == expected)

  def main(args: Array[String]): Unit =
    jsonTests()
    matcherTests()
    scoreTests()
    storeTests()
    importParserTests()
    cliTests()
    pickerTests()
    println("")
    println(s"$total checks, $failed failed")
    sys.exit(if failed == 0 then 0 else 1)

  // ------------------------------------------------------------------ json

  def jsonTests(): Unit =
    eq(
      "json: object keys",
      Json.parse("""{"a":1,"b":[1,2,3],"c":"x\"y","d":null}""").obj.map(_._1),
      Vector("a", "b", "c", "d")
    )
    eq("json: number", Json.parse("42").asLongOpt, Some(42L))
    eq("json: list len", Json.parse("""[-1,1.5,2.5e3]""").arr.length, 3)
    eq("json: escape", Json.parse("\"\\u00e9\"").str, "\u00e9")
    val value = Json.Value.obj(
      "version" -> Json.Value.num(1),
      "config" -> Json.Value.obj(
        "alpha" -> Json.Value.num(1.5),
        "tau" -> Json.Value.num(86400)
      ),
      "entries" -> Json.Value.arr(
        Vector(
          Json.Value.obj(
            "p" -> Json.Value.str("C:\\x y\\z"),
            "t" -> Json.Value.arr(Vector(Json.Value.num(123), Json.Value.num(456)))
          )
        )
      )
    )
    val rt = Json.parse(Json.render(value)).obj.toMap
    eq("json: round-trip path with backslash",
      rt.get("entries").flatMap(_.arr.headOption).get.obj.toMap.get("p").map(_.str),
      Some("C:\\x y\\z"))
    eq("json: round-trip visits",
      rt.get("entries").flatMap(_.arr.headOption).get.obj.toMap.get("t").map(_.arr.flatMap(_.asLongOpt)),
      Some(Vector(123L, 456L)))
    eq("json: round-trip config alpha",
      rt.get("config").flatMap(_.obj.toMap.get("alpha")).flatMap(_.asDoubleOpt),
      Some(1.5))

  // --------------------------------------------------------------- matcher

  def matcherTests(): Unit =
    def m(p: String, q: String): Boolean = Matcher.matches(p, q)
    check("wiki: z fo ba -> /foo/bar")(m("/foo/bar", "fo ba"))
    check("wiki: z fo ba !-> /bar/foo")(!m("/bar/foo", "fo ba"))
    check("wiki: z fo / ba -> /foo/bar")(m("/foo/bar", "fo / ba"))
    check("wiki: z fo / ba !-> /foobar")(!m("/foobar", "fo / ba"))
    check("wiki: z bar -> /foo/bar")(m("/foo/bar", "bar"))
    check("wiki: z bar !-> /bar/foo")(!m("/bar/foo", "bar"))
    check("wiki: z foo/bar -> /foo/bar")(m("/foo/bar", "foo/bar"))
    check("wiki: z foo/bar !-> /foo/bar/baz")(!m("/foo/bar/baz", "foo/bar"))
    check("matching is case-insensitive")(m("/USERS/Foo/bAr", "foo baR"))
    check("no match")(!m("/foo/bar", "qux"))
    check("windows separators")(m("C:\\Users\\you\\projects\\demo", "you demo"))
    check("multiple terms in order")(m("/home/usr/projects/release", "proj rel"))
    check("terms out of order rejected")(!m("/home/usr/projects/release", "rel proj"))

  // ----------------------------------------------------------------- score

  def scoreTests(): Unit =
    val cfg = Model.Config.Default
    val now = System.currentTimeMillis() / 1000L
    val one = Model.Entry("/one", Vector(now))
    val two = Model.Entry("/two", Vector(now, now - 1))
    check("visit weight decays monotonically") {
      val ages = Vector(0L, 3600L, 43200L, 86400L, 7L * 86400L, 30L * 86400L)
      ages.sliding(2).forall { case Seq(a, b) =>
        Score.visitWeight(a.toDouble, cfg) >= Score.visitWeight(b.toDouble, cfg)
      }
    }
    check("more visits -> higher score")(Score.frecency(two, now, cfg) > Score.frecency(one, now, cfg))
    check("fresh visit weighs more than stale")(
      Score.frecency(Model.Entry("/a", Vector(now)), now, cfg) >
        Score.frecency(Model.Entry("/b", Vector(now - 30L * 86400L)), now, cfg)
    )

    // the zoxide blind spot: same count, same last visit, different history
    val day = 86400L
    val burst = Model.Entry("/burst", (1 to 100).map(i => now - ((i % 7) + 1) * day).toVector :+ now)
    val spread = Model.Entry("/spread", (1 to 100).map(i => now - ((i % 90) + 1) * day).toVector :+ now)
    eq("blind-spot: same count", burst.count, spread.count)
    eq("blind-spot: same last visit", burst.lastVisit, spread.lastVisit)
    val zoxBurst = Score.zoxideModel(burst.count, burst.lastVisit.map(now - _).get)
    val zoxSpread = Score.zoxideModel(spread.count, spread.lastVisit.map(now - _).get)
    eq("blind-spot: zoxide model ties them", zoxBurst == zoxSpread, true)
    val zamBurst = Score.frecency(burst, now, cfg)
    val zamSpread = Score.frecency(spread, now, cfg)
    check("zam distinguishes burst from spread")(zamBurst > zamSpread)

  // ----------------------------------------------------------------- store

  def storeTests(): Unit =
    val dir = Files.createTempDirectory("zam-store-test")
    try
      val realDir = Files.createTempDirectory("zam-real")
      val d1 = realDir.toString
      val ghost = dir.resolve("ghost").toString
      val now = System.currentTimeMillis() / 1000L
      val db0 = Model.Db(Model.Config.Default, Vector.empty)

      val (db1, n1) = Store.add(db0, Seq(d1), verify = true, now)
      eq("add: recorded", n1, 1)
      eq("add: one entry", db1.entryCount, 1)
      val (db2, n2) = Store.add(db1, Seq(d1), verify = true, now + 10)
      eq("add existing: updated not duplicated", n2, 1)
      eq("add existing: still one entry", db2.entryCount, 1)
      eq("add existing: two visits", db2.entries.head.t.length, 2)

      val (db3, n3) = Store.add(db2, Seq(ghost), verify = true, now)
      eq("verify blocks non-existing", n3, 0)
      val (db4, n4) = Store.add(db2, Seq(ghost), verify = false, now)
      eq("no-verify accepts non-existing", n4, 1)

      val smallCfg = Model.Config(alpha = 1.0, tau = 86400.0, maxVisits = 3)
      val capped = Store.cap(Model.Db(smallCfg, Vector(Model.Entry("/a", Vector(1L, 2L, 3L, 4L, 5L)))))
      eq("cap drops oldest visits", capped.entries.head.t, Vector(3L, 4L, 5L))
      val emptied = Store.cap(Model.Db(Model.Config(1.0, 86400.0, 0L), Vector(Model.Entry("/a", Vector(now)))))
      eq("cap forgets emptied entries", emptied.entryCount, 0)

      val (rem, gone) = Store.remove(db1, Seq(d1))
      eq("remove reports gone", gone, 1)
      eq("remove empties db", rem.entryCount, 0)

      val rtDir = dir.resolve("rt")
      val db5 = Store.add(db0, Seq(d1), verify = true, now)._1
      Store.save(rtDir, db5)
      val loaded = Store.load(rtDir)
      eq("save/load: same entry count", loaded.entryCount, 1)
      eq("save/load: canonical path", loaded.entries.head.path, Store.canonicalize(d1))

      val cfgDir = dir.resolve("cfg")
      Store.save(cfgDir, db5.copy(config = Model.Config(alpha = 1.5, tau = 43200.0, maxVisits = 7L)))
      val cfgLoaded = Store.load(cfgDir).config
      eq("save/load: fractional alpha survives decode", cfgLoaded.alpha == 1.5, true)
      eq("save/load: tau survives decode", cfgLoaded.tau, 43200.0)
      eq("save/load: maxVisits survives decode", cfgLoaded.maxVisits, 7L)

      val stale = now - 200L * 86400L
      val mixed = Model.Db(
        Model.Config.Default,
        Vector(
          Model.Entry(Store.canonicalize(d1), Vector(stale)), // exists + old  -> keep
          Model.Entry(ghost, Vector(stale)),                  // gone + old    -> prune
          Model.Entry(dir.resolve("recent-ghost").toString, Vector(now - 86400L)) // gone + recent -> keep
        )
      )
      val pruned = Store.prune(mixed, 90)
      eq("prune keeps 2 of 3", pruned.entryCount, 2)

      // move: repoint, and merge when the destination already exists
      val movedDir = Files.createTempDirectory("zam-moved").toString
      val src = Model.Db(Model.Config.Default, Vector(
        Model.Entry(Store.canonicalize(d1), Vector(now - 10, now - 5)),
        Model.Entry(movedDir, Vector(now - 2))
      ))
      Store.move(src, d1, movedDir) match
        case Right((merged, to)) =>
          eq("move: merges into destination", to, Store.canonicalize(movedDir))
          eq("move: single merged entry", merged.entryCount, 1)
          eq("move: merged timestamps sorted", merged.entries.head.t, Vector(now - 10, now - 5, now - 2))
        case Left(msg) => check(s"move: unexpected failure: $msg")(false)
      Store.move(src, "/nowhere/remembered/xyz", movedDir) match
        case Left(_)   => check("move: unknown source is an error")(true)
        case Right(_)  => check("move: unknown source is an error")(false)
      Store.move(src, d1, d1) match
        case Left(_)   => check("move: same source and target is an error")(true)
        case Right(_)  => check("move: same source and target is an error")(false)

      // corrupt db: load quarantines the file and the previous .bak survives
      val corruptDir = dir.resolve("corrupt")
      val goodDb = Model.Db(Model.Config.Default, Vector(Model.Entry(Store.canonicalize(d1), Vector(now))))
      Store.save(corruptDir, goodDb)
      Store.save(corruptDir, goodDb) // two generations: db.json + db.json.bak
      val goodBak = Files.readAllBytes(corruptDir.resolve("db.json.bak"))
      Files.writeString(corruptDir.resolve("db.json"), "THIS IS NOT JSON {{{")
      val (recovered, warning) = Store.loadReport(corruptDir)
      eq("corrupt: loads empty", recovered.entryCount, 0)
      check("corrupt: warns")(warning.exists(_.contains("corrupt")))
      check("corrupt: quarantine file created")(
        Files.list(corruptDir).iterator().asScala.exists(_.getFileName.toString.startsWith("db.json.corrupt-"))
      )
      Store.save(corruptDir, goodDb) // first save after corruption...
      eq("corrupt: db.json.bak still holds the last good generation",
        Files.readAllBytes(corruptDir.resolve("db.json.bak")).toIndexedSeq, goodBak.toIndexedSeq)

      // aliases survive the codec round trip
      val aliasDb = goodDb.withAlias("work", Store.canonicalize(d1)).withAlias("home", movedDir)
      Store.save(dir.resolve("alias"), aliasDb)
      val aliasLoaded = Store.load(dir.resolve("alias"))
      eq("alias: round trip", aliasLoaded.aliases, aliasDb.aliases)
      eq("alias: lookup is case-insensitive", aliasLoaded.findAlias("WORK"), Some(Store.canonicalize(d1)))
      val dropped = aliasLoaded.withoutAlias("work")
      eq("alias: delete", dropped.findAlias("work"), None)
      eq("alias: delete keeps the other", dropped.findAlias("home"), Some(movedDir))
    finally deleteTree(dir)

  // ------------------------------------------------------------ import parser

  def importParserTests(): Unit =
    eq("hist: bare absolute path", Store.parseHistoryLine("C:\\src\\proj"), Some("C:\\src\\proj"))
    eq("hist: cd space", Store.parseHistoryLine("cd C:\\src\\proj"), Some("C:\\src\\proj"))
    eq("hist: cd tab", Store.parseHistoryLine("cd\tC:\\src\\proj"), Some("C:\\src\\proj"))
    eq("hist: cd quoted", Store.parseHistoryLine("cd \"C:\\src my\\proj\""), Some("C:\\src my\\proj"))
    eq("hist: chain &&", Store.parseHistoryLine("cd C:\\a && cd C:\\b"), Some("C:\\a"))
    eq("hist: chain ;", Store.parseHistoryLine("cd C:\\a; git status"), Some("C:\\a"))
    eq("hist: Set-Location", Store.parseHistoryLine("Set-Location C:\\Windows"), Some("C:\\Windows"))
    eq("hist: Set-Location -LiteralPath", Store.parseHistoryLine("Set-Location -LiteralPath C:\\Windows"), Some("C:\\Windows"))
    eq("hist: sl", Store.parseHistoryLine("sl C:\\Windows"), Some("C:\\Windows"))
    eq("hist: pushd", Store.parseHistoryLine("pushd C:\\Windows"), Some("C:\\Windows"))
    eq("hist: zoxide z", Store.parseHistoryLine("z C:\\src\\proj"), Some("C:\\src\\proj"))
    eq("hist: tilde", Store.parseHistoryLine("cd ~/proj").map(_ => "ok"), Some("ok"))
    eq("hist: bare tilde", Store.parseHistoryLine("~"), Some("~"))
    eq("hist: forward slashes", Store.parseHistoryLine("cd C:/src/proj"), Some("C:/src/proj"))
    eq("hist: git command skipped", Store.parseHistoryLine("git commit -m hi"), None)
    eq("hist: ls skipped", Store.parseHistoryLine("ls -la"), None)
    eq("hist: cd home dash skipped", Store.parseHistoryLine("cd -"), None)
    eq("hist: cd no-arg skipped", Store.parseHistoryLine("cd"), None)
    eq("hist: relative skipped", Store.parseHistoryLine("cd src/proj"), None)
    eq("hist: comment skipped", Store.parseHistoryLine("# cd C:\\junk"), None)
    eq("hist: empty skipped", Store.parseHistoryLine("   "), None)
    eq("hist: popd alone skipped", Store.parseHistoryLine("popd"), None)
    eq("hist: export skipped", Store.parseHistoryLine("export PATH=/usr/bin:$PATH"), None)

  // ------------------------------------------------------------------- cli

  private def runCli(args: String*): (Int, String) =
    val oldSys = System.out
    val out = new java.io.ByteArrayOutputStream()
    val code =
      try
        val captured = java.io.PrintStream(out, true, "UTF-8")
        System.setOut(captured)
        Console.withOut(captured) { Cli.run(args.toIndexedSeq) } // Cli prints via Console.out
      finally System.setOut(oldSys)
    (code, out.toString("UTF-8").trim)

  def cliTests(): Unit =
    eq("divide: flags", Cli.divide(Seq("--max-results=5", "-l", "foo", "bar")).flags.keySet, Set("max-results", "l"))
    eq("divide: positionals", Cli.divide(Seq("--max-results=5", "-l", "foo", "bar")).positionals, Vector("foo", "bar"))
    eq("divide: after --", Cli.divide(Seq("--", "-l", "x")).positionals, Vector("-l", "x"))

    val dataDir = Files.createTempDirectory("zam-cli-test")
    try
      val d1 = Files.createTempDirectory("zam-d1").toString
      val d2 = Files.createTempDirectory("zam-d2").toString
      val (c1, o1) = runCli("--data-dir", dataDir.toString, d1)
      eq("cli: bare existing dir -> add", (c1, o1), (0, ""))
      val (c2, _) = runCli("--data-dir", dataDir.toString, d2)
      eq("cli: add second dir", c2, 0)
      eq("cli: two entries on disk", Store.load(dataDir).entryCount, 2)
      val (cq, oq) = runCli("--data-dir", dataDir.toString, "query")
      eq("cli: query exits 0", cq, 0)
      check("cli: query returns a known dir")(
        oq.nonEmpty && Files.isDirectory(Paths.get(oq)) &&
          (Store.key(oq) == Store.key(d1) || Store.key(oq) == Store.key(d2))
      )
      val (cl, ol) = runCli("--data-dir", dataDir.toString, "query", "-l")
      eq("cli: query -l lists both", (cl, ol.split("\\R").length), (0, 2))
      val (cm, om) = runCli("--data-dir", dataDir.toString, "query", "does-not-exist-anywhere")
      eq("cli: no match exits 1", cm, 1)
      eq("cli: no match prints nothing", om, "")
      val (ce, oe) = runCli("--data-dir", dataDir.toString, "explain", "--list")
      eq("cli: explain needs a query", ce, 2)
      eq("cli: explain error on stderr-independent", oe, "")

      // bare run must print help and exit 0 (regression: help used to crash)
      val (ch, oh) = runCli()
      eq("cli: bare run prints help exits 0", ch, 0)
      check("cli: bare run help has Usage")(oh.contains("Usage:"))

      // --exclude entries are whitespace-trimmed (parity with the Ada port)
      val excl = s"  ${Store.canonicalize(d1)}, ${Store.canonicalize(d2)}"
      val (cx, ox) = runCli("--data-dir", dataDir.toString, "query", "-l", "-a", s"--exclude=$excl")
      eq("cli: exclude trims and removes both", (cx, ox), (1, ""))

      // import understands `cd` followed by a tab, not just a space
      val impDir = dataDir.resolve("imp")
      val histF = Files.createTempFile("zam-hist", ".txt")
      try
        Files.writeString(histF, s"# comment\ncd\t${Store.canonicalize(d1)}\ncd ${Store.canonicalize(d2)}\n")
        val (ci, _) = runCli("--data-dir", impDir.toString, "import", histF.toString)
        eq("cli: import handles cd-tab and cd-space", (ci, Store.load(impDir).entryCount), (0, 2))
      finally Files.deleteIfExists(histF)

      // export works on a data dir that does not exist yet (creates it)
      val expDir = dataDir.resolve("exp")
      val (cx2, _) = runCli("--data-dir", expDir.toString, "export")
      eq("cli: export creates the data dir", (cx2, Files.isDirectory(expDir)), (0, true))
      eq("cli: export writes a rank-ordered file", Files.isRegularFile(expDir.resolve("zam-export.txt")), true)

      // doctor is happy with a healthy database
      val (cdc, odc) = runCli("--data-dir", dataDir.toString, "doctor")
      check("cli: doctor clean db exits 0")(cdc == 0 && odc.contains("all good"))

      // a rejected path makes `add` exit non-zero (was 0 — scripts could not tell)
      val (cbad, _) = runCli("--data-dir", dataDir.toString, "add", dataDir.resolve("definitely-missing").toString)
      eq("cli: add nonexistent exits 1", cbad, 1)
      val (cbad2, _) = runCli("--data-dir", dataDir.toString, "add", "--no-verify", dataDir.resolve("definitely-missing").toString)
      eq("cli: add --no-verify still succeeds", cbad2, 0)

      // -s lists with scores (it used to print the bare best match)
      val (cs, os) = runCli("--data-dir", dataDir.toString, "query", "-s")
      check("cli: -s implies list with scores")(
        cs == 0 && os.split("\\R").length >= 2 && os.linesIterator.forall(l => l.matches("\\d+\\.\\d+\t.*"))
      )
      // --json emits a JSON array of {path, score, visits, last}
      val (cj, oj) = runCli("--data-dir", dataDir.toString, "query", "--json")
      val parsed = Json.parse(oj)
      check("cli: --json parses")(parsed.arr.nonEmpty)
      check("cli: --json has path+score fields")(
        parsed.arr.head.obj.toMap.contains("path") && parsed.arr.head.obj.toMap.contains("score")
      )
      // invalid --max-results is an error, not a silent ignore
      val (cmm, _) = runCli("--data-dir", dataDir.toString, "query", "--max-results=abc")
      eq("cli: bad --max-results exits 2", cmm, 2)

      // aliases: set, resolve, beats fuzzy matching, delete
      val (ca, _) = runCli("--data-dir", dataDir.toString, "alias", "wkh", d1)
      eq("cli: alias set", ca, 0)
      val (car, or) = runCli("--data-dir", dataDir.toString, "alias", "wkh")
      eq("cli: alias resolves", (car, or), (0, d1))
      val (caq, oq2) = runCli("--data-dir", dataDir.toString, "wkh")
      eq("cli: bare alias beats fuzzy", (caq, oq2), (0, d1))
      val (caa, _) = runCli("--data-dir", dataDir.toString, "alias", "-d", "wkh")
      eq("cli: alias delete", caa, 0)
      val (cax, _) = runCli("--data-dir", dataDir.toString, "alias", "wkh")
      eq("cli: deleted alias is gone", cax, 1)

      // move: repoint a remembered directory
      val (cmv, _) = runCli("--data-dir", dataDir.toString, "move", d1, d2)
      eq("cli: move repoints", cmv, 0)
      check("cli: move merged history")(
        Store.load(dataDir).entries.exists(e => Store.same(e.path, Store.canonicalize(d2)) && e.count >= 1)
      )
      eq("cli: move source gone",
        Store.load(dataDir).entries.exists(e => Store.same(e.path, Store.canonicalize(d1))), false)

      // import of a PowerShell-style history: no crash, garbage skipped
      val impDir2 = dataDir.resolve("imp2")
      val histF2 = Files.createTempFile("zam-hist-ps", ".txt")
      try
        Files.writeString(histF2,
          s"""Set-Location C:\\Windows
            |cd ${Store.canonicalize(d1)}
            |git commit -m "not a directory"
            |ls -la
            |cd
            |# comment
            |""".stripMargin)
        val (ci2, _) = runCli("--data-dir", impDir2.toString, "import", histF2.toString)
        eq("cli: PSReadLine import works", ci2, 0)
        eq("cli: PSReadLine import recorded 2 dirs", Store.load(impDir2).entryCount, 2)
      finally Files.deleteIfExists(histF2)
    finally deleteTree(dataDir)

  // ---------------------------------------------------------------- picker

  def pickerTests(): Unit =
    import Picker.{Action, Item, Mode, State}
    import Terminal.Key

    val items = Vector(
      Item("/foo/bar", 10.0),
      Item("/foo/baz", 50.0),
      Item("/qux/quux", 5.0),
      Item("/c:/users/ahri/demo", 1.0)
    )
    def st(query: String, mode: Mode = Mode.Fuzzy): State =
      State(items, Vector.empty, Map.empty, query, 0, 0, mode, 100, 24)

    eq("picker: empty query shows all in input order",
      Picker.recompute(st("")).view, items)

    eq("picker: fuzzy filters and keeps order",
      Picker.recompute(st("foo ba")).view.map(_.path),
      Vector("/foo/bar", "/foo/baz"))
    eq("picker: fuzzy does not blur irrelevant terms",
      Picker.recompute(st("foo qux")).view.isEmpty, true)
    check("picker: fuzzy records highlight offsets")(
      Picker.recompute(st("foo ba")).highlight.values.flatten.nonEmpty
    )

    val idv = Picker.recompute(st("3"))
    eq("picker: id query shows full list", idv.view, items)
    eq("picker: id query jumps to 3rd row", idv.cursor, 2)
    eq("picker: id 0 clamps to first row", Picker.recompute(st("0")).cursor, 0)
    eq("picker: oversized id clamps to last row", Picker.recompute(st("999")).cursor, items.size - 1)
    eq("picker: mixed digits stay a search", Picker.recompute(st("1a")).view.isEmpty, true)

    eq("picker: regex matches exactly",
      Picker.recompute(st("b..", Mode.Regex)).view.map(_.path),
      Vector("/foo/bar", "/foo/baz"))
    eq("picker: regex invalid collects nothing", Picker.recompute(st("*", Mode.Regex)).view.isEmpty, true)
    eq("picker: regex non-matching collects nothing", Picker.recompute(st("zzz", Mode.Regex)).view.isEmpty, true)

    val (sr, _) = Picker.update(st(""), Key.CtrlR)
    eq("picker: ctrl-r toggles regex mode", sr.mode, Mode.Regex)
    val (sp, _) = Picker.update(Picker.recompute(st("")).copy(cursor = 2), Key.CtrlP)
    eq("picker: ctrl-p moves up", sp.cursor, 1)
    val (sn, _) = Picker.update(Picker.recompute(st("")).copy(cursor = 2), Key.CtrlN)
    eq("picker: ctrl-n moves down", sn.cursor, 3)
    val (sk, _) = Picker.update(Picker.recompute(st("")).copy(cursor = 2), Key.CtrlK)
    eq("picker: ctrl-k moves up", sk.cursor, 1)
    val (sj, _) = Picker.update(Picker.recompute(st("")).copy(cursor = 2), Key.CtrlJ)
    eq("picker: ctrl-j moves down", sj.cursor, 3)
    val (su, _) = Picker.update(st("foo bar baz"), Key.CtrlU)
    eq("picker: ctrl-u clears query", su.query, "")
    val (sw, _) = Picker.update(st("foo bar baz  "), Key.CtrlW)
    eq("picker: ctrl-w drops last word", sw.query, "foo bar")
    val (sws, _) = Picker.update(st("onlyword"), Key.CtrlW)
    eq("picker: ctrl-w on single word clears", sws.query, "")

    val (sCh, act) = Picker.update(Picker.recompute(st("3")), Key.Enter)
    eq("picker: enter chooses the id row", act, Action.Choose("/qux/quux"))
    eq("picker: enter keeps non-empty state", sCh.view.nonEmpty, true)
    val (_, actCancel) = Picker.update(st(""), Key.Escape)
    eq("picker: escape cancels", actCancel, Action.Cancel)
    val (_, actCtrlC) = Picker.update(st(""), Key.CtrlC)
    eq("picker: ctrl-c cancels", actCtrlC, Action.Cancel)
    val (_, actEmpty) = Picker.update(st("zzz nope"), Key.Enter)
    eq("picker: enter on empty view cancels", actEmpty, Action.Cancel)

    // end of list navigations clamp the cursor, not hard-fail
    val (sd, _) = Picker.update(Picker.recompute(st("")).copy(cursor = Int.MaxValue), Key.Down)
    check("picker: down past bottom clamps cursor")(sd.cursor <= items.size - 1)

  private def deleteTree(dir: java.nio.file.Path): Unit =
    if Files.exists(dir) then
      Files.walk(dir)
        .sorted(java.util.Comparator.reverseOrder[java.nio.file.Path]())
        .iterator()
        .asScala
        .foreach(p => try Files.deleteIfExists(p) catch case _: Exception => ())