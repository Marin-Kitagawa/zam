package zam

import java.io.{FileDescriptor, OutputStream, PrintStream}
import java.nio.charset.StandardCharsets

/** System console output for plain (non-TUI) commands.
  *
  * The JDK prints to a Windows console through the active code page (CP437 /
  * CP850 / ...), which destroys every path that is not representable in it —
  * `println("C:\\Users\\中文")` comes out as `C:\Users\??`. Rust tools avoid
  * this by calling WriteConsoleW (UTF-16) directly; this does the same for
  * zam.
  *
  * When stdout is *not* a console (pipe, redirect — e.g. captured by the
  * shell `z` function) output is plain UTF-8 bytes; the shell snippets set
  * the receiving side to decode UTF-8.
  */
object ConsoleIO:

  private val isWindows =
    System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")

  /** Rebind System.out / System.err. Call once, before any output. */
  def setup(): Unit =
    if isWindows then
      System.setOut(printStream(FileDescriptor.out))
      System.setErr(printStream(FileDescriptor.err))

  private def printStream(fd: FileDescriptor): PrintStream =
    val out: OutputStream =
      if WideConsole.isConsole(fd) then WideConsole.outputStream(fd)
      else new java.io.FileOutputStream(fd)
    new PrintStream(out, true, StandardCharsets.UTF_8)

end ConsoleIO

/** UTF-16 console writes via WriteConsoleW, plus console-detection helpers.
  * JNA is only touched when the OS is Windows and this object is first used,
  * so Unix builds and the JVM test runner never load the binding. */
private[zam] object WideConsole:

  import com.sun.jna.{Library, Native, Pointer}
  import com.sun.jna.ptr.IntByReference
  import com.sun.jna.platform.win32.{Kernel32, Wincon, WinNT}

  private def onWindows: Boolean =
    System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")

  /** Minimal binding for the one call jna-platform does not map. */
  private trait RawK32 extends Library:
    def WriteConsoleW(hConsoleOutput: Pointer, lpBuffer: Array[Char],
      nNumberOfCharsToWrite: Int, lpNumberOfCharsWritten: IntByReference,
      lpReserved: Pointer): Boolean

  private lazy val raw: Option[RawK32] =
    if !onWindows then None
    else
      try Some(Native.load("kernel32", classOf[RawK32]))
      catch case _: Throwable => None

  private def stdHandle(which: Int): WinNT.HANDLE =
    Kernel32.INSTANCE.GetStdHandle(which)

  private def fdHandle(fd: FileDescriptor): WinNT.HANDLE =
    stdHandle(fd match
      case FileDescriptor.out => Wincon.STD_OUTPUT_HANDLE
      case _                  => Wincon.STD_ERROR_HANDLE)

  private def valid(h: WinNT.HANDLE): Boolean =
    h != null && h.getPointer != null && com.sun.jna.Pointer.nativeValue(h.getPointer) != -1L &&
      com.sun.jna.Pointer.nativeValue(h.getPointer) != 0L

  def isConsole(fd: FileDescriptor): Boolean =
    if !onWindows then false
    else
      try
        val h = fdHandle(fd)
        valid(h) && Kernel32.INSTANCE.GetConsoleMode(h, new IntByReference())
      catch case _: Throwable => false

  /** True when the *standard input* handle is a console — i.e. a human can
    * actually type to us. The picker refuses to start otherwise (a piped
    * stdin with an attached console used to block forever on CONIN$). */
  def isStdinConsole(): Boolean =
    if !onWindows then false
    else
      try
        val h = stdHandle(Wincon.STD_INPUT_HANDLE)
        valid(h) && Kernel32.INSTANCE.GetConsoleMode(h, new IntByReference())
      catch case _: Throwable => false

  /** Write UTF-16 text to a console handle. Returns false when the binding or
    * the handle is unavailable, so callers can fall back to byte writes. */
  def writeW(h: WinNT.HANDLE, s: String): Boolean =
    if !valid(h) then false
    else
      raw match
        case None => false
        case Some(k) =>
          val ptr = h.getPointer
          val chars = s.toCharArray
          var off = 0
          var ok = true
          // keep every WriteConsoleW call well inside the 64 KB buffer limit
          while ok && off < chars.length do
            val len = math.min(0x4000, chars.length - off)
            val slice = if off == 0 && len == chars.length then chars
                        else java.util.Arrays.copyOfRange(chars, off, off + len)
            ok = k.WriteConsoleW(ptr, slice, len, new IntByReference(), null)
            off += len
          ok

  /** Console output stream: decodes the UTF-8 bytes the PrintStream emits and
    * re-encodes them as UTF-16 for WriteConsoleW. A partial multi-byte
    * sequence at a chunk boundary is carried over to the next write. */
  def outputStream(fd: FileDescriptor): OutputStream = new OutputStream:
    private val h = fdHandle(fd)
    private var pending: Array[Byte] = Array.empty

    override def write(b: Int): Unit = write(Array(b.toByte), 0, 1)

    override def write(b: Array[Byte], off: Int, len: Int): Unit =
      val all = if pending.isEmpty && off == 0 && len == b.length then b
                else pending ++ java.util.Arrays.copyOfRange(b, off, off + len)
      val end = completeUtf8Length(all)
      if end > 0 then WideConsole.writeW(h, new String(all, 0, end, StandardCharsets.UTF_8))
      pending = if end < all.length then java.util.Arrays.copyOfRange(all, end, all.length) else Array.empty

    override def flush(): Unit = ()

    /** Length of the longest prefix of `b` made of complete UTF-8 sequences. */
    private def completeUtf8Length(b: Array[Byte]): Int =
      var i = 0
      while i < b.length do
        val c = b(i) & 0xFF
        val need =
          if c < 0x80 then 1
          else if c >= 0xC2 && c < 0xE0 then 2
          else if c >= 0xE0 && c < 0xF0 then 3
          else if c >= 0xF0 && c < 0xF5 then 4
          else 1 // invalid lead byte: emit as-is
        if i + need > b.length then return i
        i += need
      b.length

end WideConsole
