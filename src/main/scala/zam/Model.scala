package zam

import java.util.Locale

object Model:

  /** Scoring configuration. The score of an entry is a power-law sum over its
    * visit history; see Score.frecency. */
  final case class Config(
      alpha: Double = 1.0,
      tau: Double = 86400.0,
      maxVisits: Long = 100000L
  )

  object Config:
    val Default = Config()

  final case class Entry(path: String, t: Vector[Long]):
    def lastVisit: Option[Long] = t.lastOption
    def count: Long = t.length.toLong

  final case class Db(config: Config, entries: Vector[Entry]):
    def entryCount: Int = entries.length
    def totalVisits: Long = entries.foldLeft(0L)((acc, e) => acc + e.count)
    def isWindows: Boolean = osIsWindows

  private val osIsWindows =
    System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")

/** Frecently score.
  *
  * Unlike zoxide — which collapses a directory's history into (visit count,
  * last-access time) and therefore cannot distinguish "100 visits last week
  * plus one now" from "100 visits spread over three months plus one now" — zam
  * keeps the full visit history and scores it with a power-law aging kernel:
  *
  *   frecency(path) = sum over visits of (1 + age_sec / tau)^(-alpha)
  *
  * A visit counts ~1 while it is younger than tau seconds, and decays
  * polynomially afterwards. Frequency and recency therefore both matter, and
  * the shape of the history matters, not just its last element.
  */
object Score:

  /** Weight of a single visit `ageSec` seconds old. */
  def visitWeight(ageSec: Double, cfg: Model.Config): Double =
    val a = math.max(0.0, ageSec)
    math.pow(1.0 + a / cfg.tau, -cfg.alpha)

  /** Full-history power-law frecency. */
  def frecency(e: Model.Entry, now: Long, cfg: Model.Config): Double =
    e.t.foldLeft(0.0)((acc, ts) => acc + visitWeight((now - ts).toDouble, cfg))

  /** zoxide's aggregate model, kept for comparison in `explain`/`demo`: score
    * is the visit count scaled by a bucket based on the *last* access time. */
  def zoxideModel(count: Long, lastAgeSec: Long): Double =
    val bucket =
      if lastAgeSec < 3600 then 4.0
      else if lastAgeSec < 86400 then 2.0
      else if lastAgeSec < 604800 then 0.5
      else 0.25
    count.toDouble * bucket

  def humanAge(sec: Long): String =
    if sec < 60 then "just now"
    else if sec < 3600 then s"${sec / 60}m ago"
    else if sec < 86400 then s"${sec / 3600}h ago"
    else if sec < 604800 then s"${sec / 86400}d ago"
    else s"${sec / (86400L * 7)}w ago"