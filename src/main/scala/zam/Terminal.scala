package zam

import java.io.{BufferedReader, File, FileInputStream, FileOutputStream, InputStream, OutputStream}
import java.nio.charset.StandardCharsets

/** Low-level console access for the interactive picker.
  *
  * The TUI writes to the *console* (CONOUT$ on Windows, /dev/tty elsewhere),
  * never to stdout — so when `z -i` runs under PowerShell the chosen path still
  * arrives cleanly on stdout for the parent shell to `cd` to.
  */
object Terminal:

  val isWindows: Boolean =
    System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")

  // ── ANSI catalog ──────────────────────────────────────────────────────────
  private val esc = "\u001b"
  val Reset     = esc + "[0m"
  val Bold      = esc + "[1m"
  val Dim       = esc + "[2m"
  val Italic    = esc + "[3m"
  val Underline = esc + "[4m"
  val Reverse   = esc + "[7m"

  val FgDefault  = esc + "[39m"
  val FgWhite    = esc + "[97m"
  val FgRed      = esc + "[91m"
  val FgGreen    = esc + "[92m"
  val FgYellow   = esc + "[93m"
  val FgBlue     = esc + "[94m"
  val FgMagenta  = esc + "[95m"
  val FgCyan     = esc + "[96m"
  val FgGray     = esc + "[90m"
  val FgBright   = FgWhite

  private def fg256(n: Int): String = esc + s"[38;5;${n}m"
  private def bg256(n: Int): String = esc + s"[48;5;${n}m"

  // ── the cozy pastel palette ───────────────────────────────────────────────
  // A soft sakura / strawberry-milk scheme: pinks and lavenders for accents,
  // peach and cream for warm details, dusty mauve for anything that should
  // quietly recede.
  val Accent      = fg256(218)  // sakura pink — the primary accent
  val AccentStrong = fg256(213) // bright orchid — prompt, hearts, markers
  val Lavender    = fg256(183)  // soft lavender — secondary accent (regex)
  val Peach       = fg256(223)  // warm peach — scores
  val Cream       = fg256(224)  // pale rose-cream — selected / typed text
  val Warning     = fg256(217)  // soft coral — id badge, gentle attention
  val Danger      = fg256(210)  // strawberry — errors
  val DimGray     = fg256(139)  // dusty mauve — muted-but-readable text
  val Rule        = fg256(131)  // quiet dusty rose — separators
  val SelBg       = bg256(53)   // deep plum — selected-row backdrop
  val TrackBg     = bg256(53)   // scrollbar track backdrop
  val ThumbFg     = fg256(218)  // pink scrollbar thumb
  val ScrollTrack = fg256(95)   // very muted mauve — scrollbar track glyph
  val CursorBg    = bg256(218)  // the little pink block cursor
  val CursorFg    = fg256(53)

  val ClearLine    = esc + "[2K"
  val ClearScreen  = esc + "[2J"
  val Home         = esc + "[H"
  val HideCursor   = esc + "[?25l"
  val ShowCursor   = esc + "[?25h"
  val EnterAlt     = esc + "[?1049h"
  val ExitAlt      = esc + "[?1049l"

  // ── raw-mode plumbing ─────────────────────────────────────────────────────
  private var active = false
  private var in: InputStream = null
  private var out: OutputStream = null
  private var savedUnix: String = null
  private var savedWinIn = 0
  private var savedWinOut = 0

  /** True when a human can type to this process: the standard input is an
    * interactive terminal. The picker must not start otherwise — with piped
    * stdin the CONIN$ read blocks forever even though a console exists. */
  def stdinIsInteractive(): Boolean =
    if isWindows then WideConsole.isStdinConsole()
    else
      try
        val pb = new ProcessBuilder("sh", "-c", "[ -t 0 ] && exit 0 || exit 1")
        pb.redirectInput(ProcessBuilder.Redirect.INHERIT)
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD)
        pb.redirectError(ProcessBuilder.Redirect.DISCARD)
        pb.start().waitFor() == 0
      catch case _: Throwable => false

  def enterRaw(): Boolean =
    if active then return true
    val ok = if isWindows then winEnterRaw() else unixEnterRaw()
    if ok then
      active = true
      Runtime.getRuntime.addShutdownHook(new Thread(() => leaveRaw()))
    ok

  def leaveRaw(): Unit =
    if active then
      try
        if isWindows then winLeaveRaw() else unixLeaveRaw()
      catch case _: Throwable => ()
      active = false

  private def unixEnterRaw(): Boolean =
    try
      val tty = new File("/dev/tty")
      if !tty.exists() then return false
      in = new FileInputStream(tty)
      out = new FileOutputStream(tty)
      sttyGet("-g") match
        case Some(saved) =>
          savedUnix = saved
          sttySet("raw", "-echo").getOrElse(false)
        case None => false
    catch case _: Throwable => false

  private def unixLeaveRaw(): Unit =
    if savedUnix != null then sttySet(savedUnix)
    try if in != null then in.close()
    catch case _: Throwable => ()
    try if out != null then out.close()
    catch case _: Throwable => ()

  private def sttyGet(args: String*): Option[String] =
    try
      val pb = new ProcessBuilder("stty" +: args*)
      pb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/tty")))
      pb.redirectOutput(ProcessBuilder.Redirect.PIPE)
      pb.redirectError(ProcessBuilder.Redirect.INHERIT)
      val pr = pb.start()
      val outStr = new String(pr.getInputStream.readAllBytes(), StandardCharsets.UTF_8).trim
      if pr.waitFor() == 0 then Some(outStr) else None
    catch case _: Throwable => None

  private def sttySet(args: String*): Option[Boolean] =
    try
      val pb = new ProcessBuilder("stty" +: args*)
      pb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/tty")))
      pb.redirectOutput(ProcessBuilder.Redirect.from(new File("/dev/tty")))
      pb.redirectError(ProcessBuilder.Redirect.INHERIT)
      Option(pb.start().waitFor() == 0)
    catch case _: Throwable => None

  private lazy val win: Option[WindowsConsole] =
    if isWindows then try Some(new WindowsConsole) catch case _: Throwable => None else None

  private def winEnterRaw(): Boolean =
    win match
      case Some(c) =>
        if !c.open() then false
        else
          savedWinIn = c.queryInMode()
          savedWinOut = c.queryOutMode()
          val rawIn = (savedWinIn & ~(1 | 2 | 4 | 16) // drop processed/line/echo/mouse
            & ~(0x0040 | 0x0020) // drop quick-edit + insert: a stray mouse drag
                                 // must not freeze the picker mid-read
            | 0x0080) // ENABLE_EXTENDED_FLAGS, required to apply the above
            | 0x0200 // ENABLE_VIRTUAL_TERMINAL_INPUT
          val rawOut = savedWinOut | 0x0004
          c.setInMode(rawIn) && c.setOutMode(rawOut)
      case None => false

  private def winLeaveRaw(): Unit =
    win.foreach { c =>
      c.setInMode(savedWinIn)
      c.setOutMode(savedWinOut)
      c.close()
    }

  def rawStdin: InputStream = in
  def rawStdout: OutputStream = out

  // Console bytes are pulled by one dedicated daemon thread into a queue, so
  // reads can block forever (waiting for a key) or time out (lone ESC) without
  // ever racing a second ReadFile against the same console handle.
  @volatile private var eof = false
  private lazy val keyQueue: java.util.concurrent.BlockingQueue[Integer] =
    val q = new java.util.concurrent.LinkedBlockingQueue[Integer]()
    val t = new Thread(() =>
      var go = true
      while go do
        val b = readByteRaw()
        q.put(b)
        if b == -1 then
          eof = true
          go = false
    , "zam-console-reader")
    t.setDaemon(true)
    t.start()
    q

  private def readByteRaw(): Int =
    if isWindows then
      win match
        case Some(c) => c.readByte()
        case None    => -1
    else if in != null then in.read()
    else -1

  /** Blocking read of a single byte from the console. */
  def readByte(): Int =
    if eof && keyQueue.isEmpty then return -1
    try keyQueue.take().intValue
    catch case _: Throwable => -1

  /** Read a single byte if it arrives within `timeoutMs`; -1 on timeout. Used
    * after ESC so a lone Escape is not mistaken for the start of a VT
    * sequence — and does not block until the next keypress. */
  def readByteTimeout(timeoutMs: Long): Int =
    try
      keyQueue.poll(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) match
        case null => -1
        case v    => v.intValue
    catch case _: Throwable => -1

  def write(s: String): Unit =
    val bytes = s.getBytes(StandardCharsets.UTF_8)
    if isWindows then
      win match
        case Some(c) => c.write(bytes)
        case None => ()
    else if out != null then
      out.write(bytes)
      out.flush()

  def flush(): Unit =
    if !isWindows && out != null then
      try out.flush()
      catch case _: Throwable => ()

  // ── key decoding (VT, identical on both hosts) ────────────────────────────
  sealed trait Key
  object Key:
    case object Enter extends Key
    case object Escape extends Key
    case object Up extends Key
    case object Down extends Key
    case object Left extends Key
    case object Right extends Key
    case object Home extends Key
    case object End extends Key
    case object PageUp extends Key
    case object PageDown extends Key
    case object Backspace extends Key
    case object Delete extends Key
    case object Tab extends Key
    case object CtrlC extends Key
    case object CtrlD extends Key
    case object CtrlJ extends Key
    case object CtrlK extends Key
    case object CtrlN extends Key
    case object CtrlP extends Key
    case object CtrlR extends Key
    case object CtrlU extends Key
    case object CtrlW extends Key
    case class Ch(c: scala.Char) extends Key
    case object Unknown extends Key

  def readKey(): Key =
    val b = readByte()
    b match
      case -1 => Key.Unknown
      case 27 => // ESC: lone key or a CSI/SS3 sequence — wait briefly so a
                 // lone Escape press does not swallow the next keystroke
        val next = readByteTimeout(40)
        if next == -1 then Key.Escape
        else if next == '[' then readCsi()
        else if next == 'O' then readSs3()
        else Key.Unknown
      case 13 | 10 => Key.Enter
      case 8 | 127 => Key.Backspace
      case 9 => Key.Tab
      case b if b >= 1 && b <= 26 =>
        (b + 96).toChar match
          case 'c' => Key.CtrlC
          case 'd' => Key.CtrlD
          case 'j' => Key.CtrlJ
          case 'k' => Key.CtrlK
          case 'n' => Key.CtrlN
          case 'p' => Key.CtrlP
          case 'r' => Key.CtrlR
          case 'u' => Key.CtrlU
          case 'w' => Key.CtrlW
          case _ => Key.Unknown
      case b if b >= 32 && b < 127 => Key.Ch(b.toChar)
      case _ => Key.Unknown

  private def readCsi(): Key =
    val params = new StringBuilder
    var c = readByte()
    while c >= 0x20 && c <= 0x3f do
      params.append(c.toChar)
      c = readByte()
    val p = params.toString
    c match
      case 'A' => Key.Up
      case 'B' => Key.Down
      case 'C' => Key.Right
      case 'D' => Key.Left
      case 'H' => Key.Home
      case 'F' => Key.End
      case '~' =>
        p match
          case "1" | "7" => Key.Home
          case "4" | "8" => Key.End
          case "5" => Key.PageUp
          case "6" => Key.PageDown
          case "3" => Key.Delete
          case _ => Key.Unknown
      case _ => Key.Unknown

  private def readSs3(): Key =
    readByte() match
      case 'H' => Key.Home
      case 'F' => Key.End
      case 'A' => Key.Up
      case 'B' => Key.Down
      case 'C' => Key.Right
      case 'D' => Key.Left
      case _ => Key.Unknown

  // ── window size ───────────────────────────────────────────────────────────
  def size(): (Int, Int) =
    if isWindows then
      win match
        case Some(c) => c.size()
        case None => (80, 24)
    else
      try
        val pb = new ProcessBuilder("stty", "size")
        pb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/tty")))
        pb.redirectOutput(ProcessBuilder.Redirect.PIPE)
        pb.redirectError(ProcessBuilder.Redirect.INHERIT)
        val pr = pb.start()
        val reader = new BufferedReader(new java.io.InputStreamReader(pr.getInputStream, StandardCharsets.UTF_8))
        val line = reader.readLine()
        reader.close()
        pr.waitFor()
        val parts = line.trim.split("\\s+")
        if parts.length >= 2 then (parts(1).toInt, parts(0).toInt) else (80, 24)
      catch case _: Throwable => (80, 24)

/** Windows console handles via JNA. The JNA classes are only ever touched on
  * Windows, so the pure-Scala builds (tests, Unix) never load the binding. */
private class WindowsConsole:
  import com.sun.jna.{Memory, Pointer}
  import com.sun.jna.platform.win32.{Kernel32, WinNT, Wincon}
  import com.sun.jna.ptr.IntByReference

  private val k32 = Kernel32.INSTANCE
  private val openExisting = 0x00000003
  private var hIn: WinNT.HANDLE = null
  private var hOut: WinNT.HANDLE = null

  private def valid(h: WinNT.HANDLE): Boolean =
    h != null && Pointer.nativeValue(h.getPointer) != -1L

  def open(): Boolean =
    try
      hIn = k32.CreateFile("CONIN$", WinNT.GENERIC_READ | WinNT.GENERIC_WRITE,
        WinNT.FILE_SHARE_READ | WinNT.FILE_SHARE_WRITE, null, openExisting, 0, null)
      hOut = k32.CreateFile("CONOUT$", WinNT.GENERIC_READ | WinNT.GENERIC_WRITE,
        WinNT.FILE_SHARE_READ | WinNT.FILE_SHARE_WRITE, null, openExisting, 0, null)
      valid(hIn) && valid(hOut)
    catch case _: Throwable => false

  def close(): Unit =
    try if valid(hIn) then k32.CloseHandle(hIn)
    catch case _: Throwable => ()
    try if valid(hOut) then k32.CloseHandle(hOut)
    catch case _: Throwable => ()

  def queryInMode(): Int =
    val r = new IntByReference()
    if k32.GetConsoleMode(hIn, r) then r.getValue() else 0

  def queryOutMode(): Int =
    val r = new IntByReference()
    if k32.GetConsoleMode(hOut, r) then r.getValue() else 0

  def setInMode(mode: Int): Boolean =
    try k32.SetConsoleMode(hIn, mode)
    catch case _: Throwable => false

  def setOutMode(mode: Int): Boolean =
    try k32.SetConsoleMode(hOut, mode)
    catch case _: Throwable => false

  def readByte(): Int =
    val buf = new Array[Byte](1)
    val n = new IntByReference()
    try
      if k32.ReadFile(hIn, buf, 1, n, null) && n.getValue() > 0 then buf(0) & 0xFF
      else -1
    catch case _: Throwable => -1

  def write(bytes: Array[Byte]): Unit =
    val text = new String(bytes, StandardCharsets.UTF_8)
    // UTF-16 via WriteConsoleW: box drawing, accents and CJK render correctly
    // regardless of the console's active code page
    if !WideConsole.writeW(hOut, text) then
      val n = new IntByReference()
      try k32.WriteFile(hOut, bytes, bytes.length, n, null)
      catch case _: Throwable => ()

  def size(): (Int, Int) =
    val info = new Wincon.CONSOLE_SCREEN_BUFFER_INFO()
    try
      if k32.GetConsoleScreenBufferInfo(hOut, info) then
        val cols = info.srWindow.Right - info.srWindow.Left + 1
        val rows = info.srWindow.Bottom - info.srWindow.Top + 1
        (math.max(20, cols.toInt), math.max(8, rows.toInt))
      else (80, 24)
    catch case _: Throwable => (80, 24)