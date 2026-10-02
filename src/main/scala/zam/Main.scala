package zam

object Main:
  def main(args: Array[String]): Unit =
    ConsoleIO.setup() // UTF-16 console writes on Windows before any output
    val code = Cli.run(args.toIndexedSeq)
    System.out.flush()
    System.err.flush()
    System.exit(code)