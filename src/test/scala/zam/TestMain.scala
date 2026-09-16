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
    cliTests()
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
    check("windows separators")(m("C:\\Users\\Ahri\\projects\\demo", "ahri demo"))
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
    finally deleteTree(dir)

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
    finally deleteTree(dataDir)

  private def deleteTree(dir: java.nio.file.Path): Unit =
    if Files.exists(dir) then
      Files.walk(dir)
        .sorted(java.util.Comparator.reverseOrder[java.nio.file.Path]())
        .iterator()
        .asScala
        .foreach(p => try Files.deleteIfExists(p) catch case _: Exception => ())