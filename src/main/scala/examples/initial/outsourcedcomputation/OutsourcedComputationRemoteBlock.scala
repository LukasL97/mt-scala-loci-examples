package examples.initial.outsourcedcomputation

import loci._
import loci.transmitter.rescala._
import loci.serializer.upickle._
import loci.communicator.tcp._
import rescala.default._

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

@multitier object OutsourcedComputationRemoteBlock {

  @peer type Client <: { type Tie <: Single[PowerfulServer] with Single[WeakServer] }
  @peer type PowerfulServer <: { type Tie <: Single[Client] }
  @peer type WeakServer <: { type Tie <: Single[Client] }

  val inputNumber: Evt[Int] on Client = on[Client] { Evt[Int] }

  def powerfulCompute(value: Int): Int on PowerfulServer = on[PowerfulServer] {
    println("Doing some powerful GPU computation stuff but really quick...")
    value + 1
  }

  def weakCompute(value: Int): Int on WeakServer = on[WeakServer] {
    println("Compute on shitty slow CPU...")
    value + 1
  }

  val outputNumber = on[Client] {
    inputNumber.map {
      case value if value >= 10 =>
        on[PowerfulServer].run.capture(value) { implicit! =>
          powerfulCompute(value)
        }.asLocal
      case value =>
        on[WeakServer].run.capture(value) { implicit! =>
          weakCompute(value)
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
