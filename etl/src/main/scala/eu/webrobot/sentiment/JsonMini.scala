package eu.webrobot.sentiment

/**
 * Tiny dependency-free JSON parser used by SentimentLlm to avoid pulling Jackson into the plugin JAR.
 * Plugins compile against the SDK only — the platform classpath has Jackson at runtime, but for build
 * isolation we keep the SDK plugin self-contained.
 *
 * Returns: Map[String, Any] / List[Any] / String / Double / Boolean / null
 */
object JsonMini {

  def parse(s: String): Any = {
    val p = new Parser(s)
    p.skipWs()
    val v = p.parseValue()
    p.skipWs()
    v
  }

  def stringify(v: Any): String = v match {
    case null            => "null"
    case b: Boolean      => b.toString
    case s: String       => "\"" + escape(s) + "\""
    case n: Int          => n.toString
    case n: Long         => n.toString
    case n: Double       => if (n.isNaN || n.isInfinite) "0" else n.toString
    case n: Float        => if (n.isNaN || n.isInfinite) "0" else n.toString
    case n: BigDecimal   => n.toString
    case m: Map[_, _]    => "{" + m.map { case (k, v) => "\"" + escape(k.toString) + "\":" + stringify(v) }.mkString(",") + "}"
    case it: Iterable[_] => "[" + it.map(stringify).mkString(",") + "]"
    case other           => "\"" + escape(other.toString) + "\""
  }

  private def escape(s: String): String =
    s.flatMap {
      case '"'  => "\\\""
      case '\\' => "\\\\"
      case '\n' => "\\n"
      case '\r' => "\\r"
      case '\t' => "\\t"
      case '\b' => "\\b"
      case '\f' => "\\f"
      case c if c.toInt < 0x20 => f"\\u${c.toInt}%04x"
      case c    => c.toString
    }

  private final class Parser(s: String) {
    var i: Int = 0

    def skipWs(): Unit = while (i < s.length && Character.isWhitespace(s.charAt(i))) i += 1

    def parseValue(): Any = {
      skipWs()
      if (i >= s.length) throw new RuntimeException("unexpected end")
      s.charAt(i) match {
        case '{' => parseObject()
        case '[' => parseArray()
        case '"' => parseString()
        case 't' | 'f' => parseBool()
        case 'n' => parseNull()
        case c if c == '-' || (c >= '0' && c <= '9') => parseNumber()
        case c   => throw new RuntimeException(s"unexpected char '$c' at $i")
      }
    }

    def parseObject(): Map[String, Any] = {
      i += 1 // {
      val out = scala.collection.mutable.LinkedHashMap.empty[String, Any]
      skipWs()
      if (i < s.length && s.charAt(i) == '}') { i += 1; return out.toMap }
      var done = false
      while (!done) {
        skipWs()
        val key = parseString()
        skipWs()
        if (i >= s.length || s.charAt(i) != ':') throw new RuntimeException(s"expected ':' at $i")
        i += 1
        val v = parseValue()
        out += (key -> v)
        skipWs()
        if (i < s.length && s.charAt(i) == ',') { i += 1 }
        else if (i < s.length && s.charAt(i) == '}') { i += 1; done = true }
        else throw new RuntimeException(s"expected ',' or '}' at $i")
      }
      out.toMap
    }

    def parseArray(): List[Any] = {
      i += 1 // [
      val out = scala.collection.mutable.ListBuffer.empty[Any]
      skipWs()
      if (i < s.length && s.charAt(i) == ']') { i += 1; return out.toList }
      var done = false
      while (!done) {
        val v = parseValue()
        out += v
        skipWs()
        if (i < s.length && s.charAt(i) == ',') { i += 1 }
        else if (i < s.length && s.charAt(i) == ']') { i += 1; done = true }
        else throw new RuntimeException(s"expected ',' or ']' at $i")
      }
      out.toList
    }

    def parseString(): String = {
      if (s.charAt(i) != '"') throw new RuntimeException(s"expected '\"' at $i")
      i += 1
      val sb = new StringBuilder
      while (i < s.length && s.charAt(i) != '"') {
        val c = s.charAt(i)
        if (c == '\\') {
          i += 1
          val esc = s.charAt(i)
          esc match {
            case '"'  => sb += '"';  i += 1
            case '\\' => sb += '\\'; i += 1
            case '/'  => sb += '/';  i += 1
            case 'n'  => sb += '\n'; i += 1
            case 'r'  => sb += '\r'; i += 1
            case 't'  => sb += '\t'; i += 1
            case 'b'  => sb += '\b'; i += 1
            case 'f'  => sb += '\f'; i += 1
            case 'u'  =>
              val hex = s.substring(i + 1, i + 5)
              sb += Integer.parseInt(hex, 16).toChar
              i += 5
            case other => throw new RuntimeException(s"bad escape '\\$other' at $i")
          }
        } else {
          sb += c
          i += 1
        }
      }
      if (i >= s.length) throw new RuntimeException("unterminated string")
      i += 1
      sb.toString
    }

    def parseBool(): Boolean = {
      if (s.startsWith("true", i))  { i += 4; true }
      else if (s.startsWith("false", i)) { i += 5; false }
      else throw new RuntimeException(s"expected bool at $i")
    }

    def parseNull(): Null = {
      if (s.startsWith("null", i)) { i += 4; null }
      else throw new RuntimeException(s"expected null at $i")
    }

    def parseNumber(): Any = {
      val start = i
      if (s.charAt(i) == '-') i += 1
      while (i < s.length && Character.isDigit(s.charAt(i))) i += 1
      var isFloat = false
      if (i < s.length && s.charAt(i) == '.') {
        isFloat = true; i += 1
        while (i < s.length && Character.isDigit(s.charAt(i))) i += 1
      }
      if (i < s.length && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
        isFloat = true; i += 1
        if (i < s.length && (s.charAt(i) == '+' || s.charAt(i) == '-')) i += 1
        while (i < s.length && Character.isDigit(s.charAt(i))) i += 1
      }
      val raw = s.substring(start, i)
      if (isFloat) raw.toDouble else raw.toLong
    }
  }
}
