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

  val Accent  = fg256(81)   // soft cyan
  val Warning = fg256(214)  // amber
  val Danger  = fg256(203)  // coral red
  val DimGray = fg256(243)  // muted text
  val SelBg   = bg256(237)  // selected-row backdrop
  val TrackBg = bg256(235)  // scrollbar track
  val ThumbFg = fg256(245)  // scrollbar thumb

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
          val rawIn = (savedWinIn & ~(1 | 2 | 4 | 16)) | 0x0200
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

  /** Blocking read of a single byte from the console. */
  def readByte(): Int =
    if isWindows then
      win match
        case Some(c) => c.readByte()
        case None => -1
    else if in != null then in.read()
    else -1

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
    case object CtrlR extends Key
    case object CtrlU extends Key
    case object CtrlW extends Key
    case object CtrlC extends Key
    case class Ch(c: scala.Char) extends Key
    case object Unknown extends Key

  def readKey(): Key =
    val b = readByte()
    b match
      case -1 => Key.Unknown
      case 27 => // ESC: lone key or a CSI/SS3 sequence
        val next = readByte()
        if next == -1 then Key.Escape
        else if next == '[' then readCsi()
        else if next == 'O' then readSs3()
        else if next == 27 then Key.Escape
        else Key.Unknown
      case 13 | 10 => Key.Enter
      case 8 | 127 => Key.Backspace
      case 9 => Key.Tab
      case b if b >= 1 && b <= 26 =>
        (b + 96).toChar match
          case 'c' => Key.CtrlC
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