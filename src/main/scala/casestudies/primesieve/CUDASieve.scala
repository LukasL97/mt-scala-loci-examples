package casestudies.primesieve

import java.io.InputStream
import java.nio.charset.StandardCharsets
import scala.sys.process._
import scala.util.matching.Regex

object CUDASieve {

  private val outputPattern = new Regex(""".*100% complete(\d+) primes counted.*""")

  private def createCommand(bottom: Long, top: Long): Seq[String] =
    Seq("/home/lukas/Documents/thesis-scala-loci/CUDASieve/cudasieve", "-b", bottom.toString, "-t", top.toString)

  def countPrimesBetween(bottom: Long, top: Long): Int = {
    var result: Int = 0

    def extractResultFromOutput(output: InputStream): Unit = {
      val outputStr = new String(output.readAllBytes(), StandardCharsets.UTF_8)
      outputStr.filter(_ >= ' ') match {
        case outputPattern(number) => result = number.toInt
        case _ => throw new RuntimeException("Could not parse result from process output")
      }
      output.close()
    }

    val p = createCommand(bottom, top).run(new ProcessIO(_.close(), extractResultFromOutput, _.close()))
    p.exitValue()

    result
  }

}
