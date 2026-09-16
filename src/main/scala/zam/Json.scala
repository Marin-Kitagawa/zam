package zam

import scala.collection.mutable.ListBuffer

/** Minimal JSON reader/writer, dependency-free. */
object Json:

  enum Value:
    case Str(value: String)
    case Num(value: Double)
    case Bool(value: Boolean)
    case Nul
    case Arr(items: Vector[Value])
    case Obj(fields: Vector[(String, Value)])

  object Value:
    def str(s: String): Value = Str(s)
    def num(d: Double): Value = Num(d)
    def bool(b: Boolean): Value = Bool(b)
    def nul: Value = Nul
    def arr(items: Vector[Value]): Value = Arr(items)
    def obj(fields: (String, Value)*): Value = Obj(fields.toVector)

  import Value.*

  final class ParseError(msg: String) extends RuntimeException(msg)

  def parse(input: String): Value =
    val p = new Parser(input)
    val v = p.parseValue()
    p.skipWs()
    if !p.eof then throw ParseError("trailing tokens")
    v

  private final class Parser(s: String):
    private var i = 0
    def eof: Boolean = i >= s.length
    private def peek: Char = s.charAt(i)
    def skipWs(): Unit =
      while !eof && s.charAt(i).isWhitespace do i += 1
    def parseValue(): Value =
      skipWs()
      peek match
        case '{' => parseObj()
        case '[' => parseArr()
        case '"' => Str(parseString())
        case 't' | 'f' => parseBool()
        case 'n' => parseNul()
        case c if c == '-' || c.isDigit => parseNumber()
        case c => throw ParseError(s"unexpected char '$c' at $i")
    private def expect(ch: Char): Unit =
      if eof || peek != ch then throw ParseError(s"expected '$ch' at $i") else i += 1
    private def parseObj(): Value =
      expect('{')
      skipWs()
      val buf = ListBuffer.empty[(String, Value)]
      if !eof && peek == '}' then
        i += 1
        return Obj(buf.toVector)
      while true do
        skipWs()
        val k = parseString()
        skipWs()
        expect(':')
        skipWs()
        buf += ((k, parseValue()))
        skipWs()
        peek match
          case ',' => i += 1
          case '}' =>
            i += 1
            return Obj(buf.toVector)
          case c => throw ParseError(s"expected ',' or '}' at $i")
      Obj(buf.toVector)
    private def parseArr(): Value =
      expect('[')
      skipWs()
      val buf = ListBuffer.empty[Value]
      if !eof && peek == ']' then
        i += 1
        return Arr(buf.toVector)
      while true do
        buf += parseValue()
        skipWs()
        peek match
          case ',' => i += 1
          case ']' =>
            i += 1
            return Arr(buf.toVector)
          case c => throw ParseError(s"expected ',' or ']' at $i")
      Arr(buf.toVector)
    private def parseString(): String =
      expect('"')
      val sb = new StringBuilder
      while true do
        if eof then throw ParseError("unterminated string")
        val c = peek
        if c == '"' then
          i += 1
          return sb.toString()
        else if c == '\\' then
          i += 1
          if eof then throw ParseError("bad escape")
          peek match
            case '"'  => sb += '"'; i += 1
            case '\\' => sb += '\\'; i += 1
            case '/'  => sb += '/'; i += 1
            case 'b'  => sb += '\b'; i += 1
            case 'f'  => sb += '\f'; i += 1
            case 'n'  => sb += '\n'; i += 1
            case 'r'  => sb += '\r'; i += 1
            case 't'  => sb += '\t'; i += 1
            case 'u' =>
              if i + 5 >= s.length then throw ParseError("bad \\u escape")
              val hex = s.substring(i + 1, i + 5)
              sb += Integer.parseInt(hex, 16).toChar
              i += 5
            case c => throw ParseError(s"bad escape '\\$c'")
        else
          sb += c
          i += 1
      sb.toString()
    private def parseNumber(): Value =
      val start = i
      while !eof && (peek.isDigit || peek == '.' || peek == 'e' || peek == 'E' || peek == '+' || peek == '-') do i += 1
      Num(s.substring(start, i).toDouble)
    private def parseBool(): Value =
      if s.startsWith("true", i) then
        i += 4
        Bool(true)
      else if s.startsWith("false", i) then
        i += 5
        Bool(false)
      else throw ParseError("bad boolean literal")
    private def parseNul(): Value =
      if s.startsWith("null", i) then
        i += 4
        Nul
      else throw ParseError("bad null literal")
  end Parser

  def render(v: Value, pretty: Boolean = true): String =
    val sb = new StringBuilder
    write(sb, v, 0, pretty)
    sb.toString()

  private def write(sb: StringBuilder, v: Value, indent: Int, pretty: Boolean): Unit =
    v match
      case Str(s)      => writeString(sb, s)
      case Num(d)      => sb.append(formatNum(d))
      case Bool(b)     => sb.append(if b then "true" else "false")
      case Nul         => sb.append("null")
      case Arr(items)  =>
        if items.isEmpty then sb.append("[]")
        else
          sb.append('[')
          var first = true
          for it <- items do
            if !first then sb.append(',')
            first = false
            if pretty then
              sb.append('\n')
              pad(sb, indent + 1)
            write(sb, it, indent + 1, pretty)
          if pretty then
            sb.append('\n')
            pad(sb, indent)
          sb.append(']')
      case Obj(fields) =>
        if fields.isEmpty then sb.append("{}")
        else
          sb.append('{')
          var first = true
          for (k, vv) <- fields do
            if !first then sb.append(',')
            first = false
            if pretty then
              sb.append('\n')
              pad(sb, indent + 1)
            writeString(sb, k)
            sb.append(':')
            if pretty then sb.append(' ')
            write(sb, vv, indent + 1, pretty)
          if pretty then
            sb.append('\n')
            pad(sb, indent)
          sb.append('}')
  end write

  private def pad(sb: StringBuilder, n: Int): Unit =
    var k = 0
    while k < n do
      sb.append("  ")
      k += 1

  private def formatNum(d: Double): String =
    if d == d.floor && !d.isInfinite && math.abs(d) < 1e15 then d.toLong.toString else d.toString

  private def writeString(sb: StringBuilder, s: String): Unit =
    sb.append('"')
    for c <- s do
      c match
        case '"'  => sb.append("\\\"")
        case '\\' => sb.append("\\\\")
        case '\n' => sb.append("\\n")
        case '\r' => sb.append("\\r")
        case '\t' => sb.append("\\t")
        case '\b' => sb.append("\\b")
        case '\f' => sb.append("\\f")
        case c if c < ' ' => sb.append(f"\\u${c.toInt}%04x")
        case c    => sb.append(c)
    sb.append('"')

  extension (v: Value)
    def str: String = v match
      case Str(s) => s
      case _      => ""
    def asLongOpt: Option[Long] = v match
      case Num(d) => Some(d.toLong)
      case _      => None
    def asDoubleOpt: Option[Double] = v match
      case Num(d) => Some(d)
      case _      => None
    def arr: Vector[Value] = v match
      case Arr(a) => a
      case _      => Vector.empty
    def obj: Vector[(String, Value)] = v match
      case Obj(f) => f
      case _      => Vector.empty