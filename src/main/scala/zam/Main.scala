package zam

object Main:
  def main(args: Array[String]): Unit =
    val code = Cli.run(args.toIndexedSeq)
    System.out.flush()
    System.err.flush()
    System.exit(code)