package examples.initial.outsourcedcomputation.remoteblock

import loci._
import loci.communicator.tcp._
import loci.transmitter.rescala._
import loci.serializer.upickle._
import rescala.default._

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.util.Random

@multitier object OutsourcedComputationRemoteBlock {

  @peer type Server

  @peer type Client <: Server { type Tie <: Single[PowerfulServer] with Single[WeakServer] }
  @peer type PowerfulServer <: Server { type Tie <: Single[Client] }
  @peer type WeakServer <: Server { type Tie <: Single[Client] }

  val inputNumber: Evt[Int] on Client = on[Client] { implicit! => Evt[Int] }

  def compute(value: Int):  Int on Server = (
    on[Server] { implicit! =>
      throw new NotImplementedError
    }
  ) and (
    on[PowerfulServer] { implicit! =>
      println("Doing some powerful GPU computation stuff but really quick...")
      value + 1
    }
  ) and (
    on[WeakServer] { implicit! =>
      println("Compute on shitty slow CPU...")
      value + 1
    }
  )

  def selectServer: Local[Remote[Server]] on Client = on[Client] { implicit! =>
    val servers = remote[Server].connected.now
    servers((new Random).nextInt(servers.size))
  }

  val outputNumber: Event[Future[Int]] on Client = on[Client] { implicit! =>
    inputNumber.map { value =>
      on(selectServer).run.capture(value) { implicit! =>
        compute(value)
      }.asLocal
    }
  }

  def main() = on[Client] {
    outputNumber observe {
      output =>
        println(Await.result(output, 5.seconds))
    }
    for (line <- scala.io.Source.stdin.getLines)
      inputNumber.fire(line.toInt)
  }

}

object Client extends App {
  multitier start new Instance[OutsourcedComputationRemoteBlock.Client](
    connect[OutsourcedComputationRemoteBlock.PowerfulServer] { TCP("localhost", 40001) } and
      connect[OutsourcedComputationRemoteBlock.WeakServer] { TCP("localhost", 40002) }
  )
}

object PowerfulServer extends App {
  multitier start new Instance[OutsourcedComputationRemoteBlock.PowerfulServer](
    listen[OutsourcedComputationRemoteBlock.Client] { TCP(40001) }
  )
}

object WeakServer extends App {
  multitier start new Instance[OutsourcedComputationRemoteBlock.WeakServer](
    listen[OutsourcedComputationRemoteBlock.Client] { TCP(40002) }
  )
}
