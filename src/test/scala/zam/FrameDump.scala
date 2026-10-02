package zam

import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters.*

/** Renders picker frames to ANSI text and an HTML preview so the design can
  * be reviewed outside a live terminal. Not part of the shipped jar. */
object FrameDump:

  def main(args: Array[String]): Unit =
    val outDir = if args.isEmpty then Paths.get("target") else Paths.get(args(0))
    val items = Vector(
      Picker.Item("C:\\Users\\Ahri\\Projects\\Plush", 12.42),
      Picker.Item("C:\\Users\\Ahri\\Documents\\Default Project", 8.117),
      Picker.Item("C:\\Users\\Ahri\\Projects\\zam", 6.301),
      Picker.Item("C:\\Users\\Ahri\\Pictures\\wallpapers\\sakura", 4.85),
      Picker.Item("C:\\Users\\Ahri\\Music\\lofi playlists", 3.207),
      Picker.Item("C:\\Users\\Ahri\\.zcode", 2.14),
      Picker.Item("C:\\Users\\Ahri\\AppData\\Local\\Temp", 1.003),
      Picker.Item("C:\\Windows\\System32", 0.512),
      Picker.Item("D:\\games\\cozy farm sims", 0.402),
      Picker.Item("C:\\Users\\Ahri\\Projects\\Plush\\docs", 0.301)
    )

    def st(query: String, mode: Picker.Mode = Picker.Mode.Fuzzy, cursor: Int = 0,
        cols: Int = 100, rows: Int = 24): Picker.State =
      Picker.recompute(
        Picker.State(items, Vector.empty, Map.empty, query, cursor, 0, mode, cols, rows))

    val scenarios = Vector(
      "1. default view, first row selected"        -> st(""),
      "2. fuzzy query with highlights"             -> st("proj pl"),
      "3. id query"                                -> st("3"),
      "4. regex mode"                              -> st("wall.*", Picker.Mode.Regex),
      "5. no results"                              -> st("zzzz nowhere"),
      "6. bad regex"                               -> st("*[", Picker.Mode.Regex),
      "7. narrow terminal (cols=50)"               -> st("", Picker.Mode.Fuzzy, 0, cols = 50),
      "8. scrolling list (rows=14, cursor at end)" -> st("", Picker.Mode.Fuzzy, cursor = 9, rows = 14),
      "9. mid-row selection"                       -> st("", Picker.Mode.Fuzzy, cursor = 3)
    )

    val frames = scenarios.map((name, s) => name -> Picker.frame(s))

    val text = frames.map((n, f) => s"===== $n =====\n$f\n").mkString
    Files.write(outDir.resolve("tui-frames.txt"), text.getBytes(java.nio.charset.StandardCharsets.UTF_8))

    Files.write(outDir.resolve("tui-preview.html"), htmlPreview(frames).getBytes(java.nio.charset.StandardCharsets.UTF_8))
    println(s"wrote ${frames.size} frames to ${outDir.resolve("tui-frames.txt")} and tui-preview.html")

  // ── ANSI → HTML preview ────────────────────────────────────────────────────

  private val csi = raw"\u001b\[([0-9;]*)([A-Za-z])".r

  private def x256(n: Int): String =
    if n < 16 then
      Vector("000000", "800000", "008000", "808000", "000080", "800080", "008080", "c0c0c0",
        "808080", "ff0000", "00ff00", "ffff00", "0000ff", "ff00ff", "00ffff", "ffffff")(n)
    else if n >= 232 then
      val v = 8 + (n - 232) * 10
      f"$v%02x$v%02x$v%02x"
    else
      val lv = Vector(0, 95, 135, 175, 215, 255)
      val r = (n - 16) / 36
      val g = ((n - 16) % 36) / 6
      val b = (n - 16) % 6
      f"${lv(r)}%02x${lv(g)}%02x${lv(b)}%02x"

  /** One line of ANSI SGR → a run of styled spans. */
  private def ansiToHtml(line: String): String =
    val sb = new StringBuilder
    var fg: Option[String] = None
    var bg: Option[String] = None
    var bold = false
    var italic = false
    var under = false

    def span(text: String): Unit =
      if text.nonEmpty then
        val style = new StringBuilder
        if bold then style.append("font-weight:700;")
        if italic then style.append("font-style:italic;")
        if under then style.append("text-decoration:underline;")
        fg.foreach(f => style.append(s"color:#$f;"))
        bg.foreach(b => style.append(s"background:#$b;"))
        val escaped = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        sb.append(s"""<span style="$style">$escaped</span>""")

    var pos = 0
    for m <- csi.findAllMatchIn(line) do
      span(line.substring(pos, m.start))
      val params = m.group(1)
      val cmd = m.group(2)
      if cmd == "m" then
        val codes =
          if params.isEmpty then Vector(0)
          else params.split(';').toVector.map(s => if s.isEmpty then 0 else s.toInt)
        var i = 0
        while i < codes.length do
          codes(i) match
            case 0  => fg = None; bg = None; bold = false; italic = false; under = false
            case 1  => bold = true
            case 3  => italic = true
            case 4  => under = true
            case 22 => bold = false
            case 23 => italic = false
            case 24 => under = false
            case 39 => fg = None
            case 49 => bg = None
            case c if c >= 30 && c <= 37   => fg = Some(x256(c - 30))
            case c if c >= 90 && c <= 97   => fg = Some(x256(c - 90 + 8))
            case c if c >= 40 && c <= 47   => bg = Some(x256(c - 40))
            case c if c >= 100 && c <= 107 => bg = Some(x256(c - 100 + 8))
            case 38 if i + 2 < codes.length && codes(i + 1) == 5 =>
              fg = Some(x256(codes(i + 2))); i += 2
            case 48 if i + 2 < codes.length && codes(i + 1) == 5 =>
              bg = Some(x256(codes(i + 2))); i += 2
            case _ => ()
          i += 1
      pos = m.end
    span(line.substring(pos))
    sb.toString

  private def htmlPreview(frames: Vector[(String, String)]): String =
    val body = frames.map { (name, frame) =>
      val lines = frame.split("\r\n|\n").toVector.map(l => ansiToHtml(l) + "\n").mkString
      s"""<h2>${name.replace("&", "&amp;")}</h2>
         |<div class="term">$lines</div>""".stripMargin
    }.mkString("\n")
    s"""<!doctype html><html><head><meta charset="utf-8"><title>zam picker preview</title>
       |<style>
       | body { background: radial-gradient(1200px 700px at 30% 0%, #241a2e, #150f1c 70%); margin: 0; padding: 28px;
       |        font-family: 'Cascadia Mono', 'Cascadia Code', Consolas, monospace; }
       | h2 { color: #ffafd7; font-weight: 600; font-size: 15px; margin: 26px 0 8px 4px; font-family: 'Segoe UI', sans-serif; }
       | .term { background: #191221; border: 1px solid #4a3050; border-radius: 14px; padding: 14px 18px;
       |         box-shadow: 0 10px 30px rgba(0,0,0,.45), inset 0 0 40px rgba(255,140,200,.05);
       |         font-size: 14px; line-height: 1.42; white-space: pre; overflow-x: auto; color: #e8e0ea; }
       |</style></head><body>
       |$body
       |</body></html>""".stripMargin
