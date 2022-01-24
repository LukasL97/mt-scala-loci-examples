package casestudies.primesieve

import loci._
import loci.communicator.tcp._
import loci.serializer.upickle._
import loci.transmitter.transmittable._
import upickle.default._

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

/**
 * Showcase for peergroup and dynamic remote selection with self reference.
 *
 * The Client requests the number of primes upto a certain number from the Server. If the number is not too big, the
 * Server executes the request itself (via self reference). Otherwise, it passes it on to a connected GPUServer (via remote reference).
 * The GPUServer implements the `countPrimesBetween` method using CUDA on the GPU, while the Server uses normal Scala code on the CPU instead.
 */
@multitier object PrimeCounter {
  @peergroup type PrimeCounter
  @peer type Server <: PrimeCounter { type Tie <: Multiple[Client] with Multiple[GPUServer] }
  @peer type GPUServer <: PrimeCounter { type Tie <: Single[Server] }
  @peer type Client <: { type Tie <: Single[Server] }

  def countPrimesBetween(bottom: Long, top: Long): Int on PrimeCounter = on[Server] { implicit! =>
    println(s"Count primes between $bottom and $top on Server")
    ScalaSieve.countPrimesBetween(bottom, top)
  } and on [GPUServer] { implicit! =>
    println(s"Count primes between $bottom and $top on GPU")
    CUDASieve.countPrimesBetween(bottom, top)
  }

  def selectPrimeCounter(top: Long)(connected: Seq[Remote[PrimeCounter]]): Local[Remote[PrimeCounter]] on Server = on[Server] { implicit! =>
    if (top < 10000) {
      self
    } else {
      connected.head
    }
  }

  def requestPrimesBetween(bottom: Long, top: Long): Future[Int] on Server = on[Server] { implicit! =>
    remoteAny.apply[PrimeCounter](selectPrimeCounter(top)(_)).call(countPrimesBetween(bottom, top)).asLocal
  }

  def main(): Unit on Client = on[Client] { implicit! =>
    for (line <- scala.io.Source.stdin.getLines()) {
      remote[Server].call(requestPrimesBetween(0, line.toLong)).asLocal.foreach { primes =>
        println(s"Found $primes primes between 0 and $line")
      }
    }
  }

}

object Server extends App {
  multitier start new Instance[PrimeCounter.Server](
    listen[PrimeCounter.Client](TCP(50001)) and
      listen[PrimeCounter.GPUServer](TCP(50002))
  )
}

object GPUServer extends App {
  multitier start new Instance[PrimeCounter.GPUServer](
    connect[PrimeCounter.Server](TCP("localhost", 50002))
  )
}

object Client extends App {
  multitier start new Instance[PrimeCounter.Client](
    connect[PrimeCounter.Server](TCP("localhost", 50001))
  )
}
