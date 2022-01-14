package casestudies.primesieve

object ScalaSieve {

  def countPrimesBetween(bottom: Long, top: Long): Int = {
    sieve(Math.max(bottom, 2) to top).size
  }

  private def sieve(numbers: Seq[Long]): Seq[Long] =  {
    numbers match {
      case Seq() => Seq()
      case numbers => numbers.head +: sieve(numbers.tail.filter(x => x % numbers.head != 0))
    }
  }
}
