package zam

/** Interactive directory picker — a small, self-contained TUI roughly in the
  * spirit of bubbletea's list.Model. Runs on top of [[Terminal]], which owns
  * the raw console I/O; this object owns the Model/Update/View loop.
  *
  * Modes:
  *   - fuzzy : type anything; ordered, case-insensitive zoxide-style matching
  *   - regex : Ctrl-R toggles a proper regular-expression filter
  *   - id    : a query made entirely of digits selects the Nth row directly
  */
object Picker:

  enum Mode:
    case Fuzzy, Regex

  enum Result:
    case Chosen(path: String)
    case Cancelled
    case Unavailable

  final case class Item(path: String, score: Double)

  final case class State(
      items: Vector[Item],
      view: Vector[Item],
      highlight: Map[Int, Vector[Int]],
      query: String,
      cursor: Int,
      top: Int,
      mode: Mode,
      cols: Int,
      rows: Int
  )

  private def itemRows(s: State): Int = math.max(1, s.rows - 5)

  private def fixTop(s: State): State =
    val n = itemRows(s)
    if s.cursor < s.top then s.copy(top = s.cursor)
    else if s.cursor >= s.top + n then s.copy(top = s.cursor - n + 1)
    else s

  private def isValidRegex(p: String): Boolean =
    try { p.r; true }
    catch case _: Throwable => false

  private def filterFor(mode: Mode, items: Vector[Item], q: String): Vector[(Item, Vector[Int])] =
    if q.isEmpty then items.map(i => (i, Vector.empty))
    else
      mode match
        case Mode.Fuzzy =>
          items.flatMap { i => Matcher.matchPositions(i.path, q).map(offs => (i, offs)) }
        case Mode.Regex =>
          if !isValidRegex(q) then Vector.empty
          else items.flatMap { i => Matcher.regexPositions(i.path, q).map(offs => (i, offs)) }

  def isIdQuery(q: String): Boolean = q.nonEmpty && q.forall(_.isDigit)

  /** Re-derive the displayed list from the query and mode. All-digit queries
    * become an id-based selection over the full list. */
  def recompute(s: State): State =
    if isIdQuery(s.query) then
      val idx = math.max(0, math.min(s.query.toInt - 1, s.items.size - 1))
      fixTop(s.copy(view = s.items, highlight = Map.empty, cursor = idx))
    else
      val f = filterFor(s.mode, s.items, s.query)
      val view = f.map(_._1)
      val hl = f.zipWithIndex.map { case ((it, offs), i) => i -> offs }.toMap
      val cur = if view.isEmpty then 0 else math.max(0, math.min(s.cursor, view.size - 1))
      fixTop(s.copy(view = view, highlight = hl, cursor = cur))

  enum Action:
    case Continue
    case Choose(path: String)
    case Cancel

  def update(s: State, k: Terminal.Key): (State, Action) =
    val cursor = s.cursor
    k match
      case Terminal.Key.Enter =>
        if s.view.nonEmpty && cursor >= 0 && cursor < s.view.size then
          (s, Action.Choose(s.view(cursor).path))
        else (s, Action.Cancel)
      case Terminal.Key.Escape | Terminal.Key.CtrlC => (s, Action.Cancel)
      case Terminal.Key.Up => (recompute(s.copy(cursor = cursor - 1)), Action.Continue)
      case Terminal.Key.Down => (recompute(s.copy(cursor = cursor + 1)), Action.Continue)
      case Terminal.Key.Home => (recompute(s.copy(cursor = 0)), Action.Continue)
      case Terminal.Key.End => (recompute(s.copy(cursor = Int.MaxValue)), Action.Continue)
      case Terminal.Key.PageUp => (recompute(s.copy(cursor = cursor - itemRows(s))), Action.Continue)
      case Terminal.Key.PageDown => (recompute(s.copy(cursor = cursor + itemRows(s))), Action.Continue)
      case Terminal.Key.Backspace =>
        if s.query.nonEmpty then (recompute(s.copy(query = s.query.dropRight(1))), Action.Continue)
        else (s, Action.Continue)
      case Terminal.Key.Delete => (s, Action.Continue)
      case Terminal.Key.CtrlR =>
        (recompute(s.copy(mode = if s.mode == Mode.Fuzzy then Mode.Regex else Mode.Fuzzy)), Action.Continue)
      case Terminal.Key.CtrlU => (recompute(s.copy(query = "")), Action.Continue)
      case Terminal.Key.CtrlW =>
        val words = s.query.trim.split("\\s+")
        val trimmed = if words.length <= 1 then "" else words.dropRight(1).mkString(" ")
        (recompute(s.copy(query = trimmed)), Action.Continue)
      case Terminal.Key.Ch(c) => (recompute(s.copy(query = s.query + c)), Action.Continue)
      case _ => (s, Action.Continue)

  // ── rendering ─────────────────────────────────────────────────────────────

  private def truncate(path: String, maxW: Int): String =
    if path.length <= maxW then path
    else if maxW <= 1 then path.take(math.max(0, maxW))
    else path.take(maxW - 1) + "…"

  private def padLeft(s: String, w: Int): String =
    if s.length >= w then s else " " * (w - s.length) + s

  private def fmtScore(s: Double): String =
    val t = if s >= 1000 || s < 1 then f"$s%.0f" else f"$s%.2f"
    padLeft(t, 7)

  private def scrollbar(s: State): Vector[String] =
    val n = itemRows(s)
    val total = s.view.size
    if total <= n then Vector.fill(n)("  ")
    else
      val thumbLen = math.max(1, (n.toDouble * n / total).round.toInt)
      val thumbPos = math.min(n - thumbLen, ((total - n).toDouble * s.top / math.max(1, total - n)).round.toInt)
      (0 until n).map { i =>
        if i >= thumbPos && i < thumbPos + thumbLen then
          Terminal.ThumbFg + "▓▓"
        else
          Terminal.DimGray + "░░"
      }.toVector

  private def header(s: State, sb: StringBuilder): Unit =
    sb.append(Terminal.Bold).append(Terminal.FgBright).append(" zam")
    sb.append(Terminal.Reset).append(Terminal.DimGray).append("  jump to directory ")

    val badge =
      if isIdQuery(s.query) then Terminal.Warning + "[id #" + s.query + "]"
      else if s.mode == Mode.Regex then Terminal.FgMagenta + "[regex]"
      else Terminal.Accent + "[fuzzy]"
    val count = s.view.size.toString
    sb.append(badge)
    sb.append(Terminal.DimGray)
    sb.append("  " + count + " item" + (if count == "1" then "" else "s"))

  private def input(s: State, sb: StringBuilder): Unit =
    sb.append("  ").append(Terminal.Accent).append(Terminal.Bold).append(">").append(Terminal.Reset).append(" ")
    if s.query.nonEmpty then sb.append(Terminal.FgBright).append(s.query)
    sb.append(Terminal.Reverse).append(" ").append(Terminal.Reset)

  private def sep(sb: StringBuilder, cols: Int): Unit =
    sb.append(Terminal.DimGray)
    if cols > 0 then sb.append("─" * cols)

  private def row(s: State, sb: StringBuilder, i: Int, selected: Boolean): Unit =
    val it = s.view(i)
    val offs = s.highlight.getOrElse(i, Vector.empty).toSet
    val marker = if selected then Terminal.Bold + Terminal.Accent + "▸ " else "  "
    val idStr = padLeft((i + 1).toString, 3)

    val base = if selected then Terminal.SelBg + Terminal.FgWhite else Terminal.FgDefault
    val hi = Terminal.Bold + Terminal.Underline + Terminal.Accent

    val pathW = math.max(4, s.cols - 17)
    val visible = truncate(it.path, pathW)

    sb.append(Terminal.Reset).append(base)
    sb.append(" ").append(marker)
    sb.append(Terminal.Reset).append(base)
    if selected then sb.append(idStr)
    else sb.append(Terminal.DimGray).append(idStr)
    sb.append(base).append(" ")

    // path with highlight spans (kept inside the active row style)
    if offs.isEmpty then sb.append(visible)
    else
      var k = 0
      while k < visible.length do
        if offs.contains(k) then sb.append(hi).append(visible(k))
        else sb.append(base).append(visible(k))
        k += 1

    sb.append(base).append(" ")
    if selected then sb.append(fmtScore(it.score))
    else sb.append(Terminal.DimGray).append(fmtScore(it.score))

  private def footer(s: State, sb: StringBuilder): Unit =
    sb.append(Terminal.DimGray)
    sb.append("  ").append(Terminal.Accent).append("↑↓").append(Terminal.DimGray).append(" move  ")
    sb.append(Terminal.Accent).append("↵").append(Terminal.DimGray).append(" open  ")
    sb.append(Terminal.Accent).append("esc").append(Terminal.DimGray).append(" cancel  ")
    sb.append(Terminal.Accent).append("ctrl-r").append(Terminal.DimGray)
    if s.mode == Mode.Regex then sb.append(" regex on") else sb.append(" regex")
    if isIdQuery(s.query) then sb.append("  ").append(Terminal.Warning).append("selecting by id")
    else sb.append("  ").append(Terminal.Accent).append("digits").append(Terminal.DimGray).append(" jump  ")

  private def finalizeLine(sb: StringBuilder, cols: Int, visible: Int): Unit =
    if visible < cols then sb.append(" " * (cols - visible))
    sb.append(Terminal.Reset).append("\r\n")

  private def frame(s: State): String =
    val cols = s.cols
    val sb = new StringBuilder
    sb.append(Terminal.Home)
    sb.append(Terminal.ClearScreen)

    header(s, sb); finalizeLine(sb, cols, cols)
    input(s, sb); finalizeLine(sb, cols, 4 + math.min(s.query.length, cols - 4))
    sep(sb, cols); finalizeLine(sb, cols, cols)

    val n = itemRows(s)
    val bars = scrollbar(s)
    val start = s.top
    val end = math.min(s.view.size, start + n)
    var i = 0
    while i < n do
      val idx = start + i
      if idx < end then
        row(s, sb, idx, idx == s.cursor)
        sb.append(bars(i))
      else
        sb.append(" " * (cols - 2)).append("  ")
      finalizeLine(sb, cols, cols)
      i += 1

    if s.view.isEmpty then
      val msg =
        if s.mode == Mode.Regex && !isValidRegex(s.query) then
          s"bad regex '${s.query}' — ctrl-r to switch back · esc to cancel"
        else if s.query.nonEmpty then
          s""""${s.query}" found nothing — try fewer terms · esc to cancel"""
        else "nothing to show"
      sb.append(Terminal.DimGray).append("  ").append(msg)
      finalizeLine(sb, cols, 2 + msg.length)

    sep(sb, cols); finalizeLine(sb, cols, cols)
    footer(s, sb); finalizeLine(sb, cols, cols)
    sb.toString

  // ── entry point ───────────────────────────────────────────────────────────

  def run(items: Vector[Item]): Result =
    if items.isEmpty then return Result.Unavailable
    if !Terminal.enterRaw() then return Result.Unavailable
    try
      val (cols, rows) = Terminal.size()
      if cols < 40 || rows < 10 then
        Terminal.leaveRaw()
        return Result.Unavailable
      Terminal.write(Terminal.EnterAlt + Terminal.ClearScreen + Terminal.HideCursor)
      var s = recompute(State(items, items, Map.empty, "", 0, 0, Mode.Fuzzy, cols, rows))
      var result: Result = Result.Cancelled
      var go = true
      while go do
        Terminal.write(frame(s))
        Terminal.flush()
        update(s, Terminal.readKey()) match
          case (ns, Action.Continue) => s = ns
          case (_, Action.Choose(p)) => result = Result.Chosen(p); go = false
          case (_, Action.Cancel) => result = Result.Cancelled; go = false
      Terminal.write(Terminal.ShowCursor + Terminal.ExitAlt)
      Terminal.flush()
      result
    catch
      case _: Throwable =>
        try
          Terminal.write(Terminal.ShowCursor + Terminal.ExitAlt)
          Terminal.flush()
        catch case _: Throwable => ()
        Result.Cancelled
    finally Terminal.leaveRaw()