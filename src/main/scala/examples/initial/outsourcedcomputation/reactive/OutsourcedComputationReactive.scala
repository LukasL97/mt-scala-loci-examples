package examples.initial.outsourcedcomputation.reactive

import loci._
import loci.communicator.tcp._
import loci.transmitter.rescala._
import loci.serializer.upickle._
import rescala.default._

@multitier object OutsourcedComputationReactive {

  @peer type Client <: { type Tie <: Single[PowerfulServer] with Single[WeakServer] }
  @peer type PowerfulServer <: { type Tie <: Single[Client] }
  @peer type WeakServer <: { type Tie <: Single[Client] }

  val inputNumber: Evt[Int] on Client = on[Client] { Evt[Int] }

  val powerfulComputedOutputNumber: Event[Int] on PowerfulServer = on[PowerfulServer] { implicit! =>
    (inputNumber.asLocal && (_ >= 10)).map { value: Int =>
      println("Doing some powerful GPU computation stuff but really quick...")
      value + 1
    }
  }

  val weakComputedOutputNumber: Event[Int] on WeakServer = on[WeakServer] { implicit! =>
    (inputNumber.asLocal && (_ < 10)).map { value: Int =>
      println("Compute on shitty slow CPU...")
      value + 1
    }
  }

  val outputNumber: Event[Int] on Client = on[Client] { implicit! =>
    powerfulComputedOutputNumber.asLocal || weakComputedOutputNumber.asLocal
  }

  def main() = on[Client] {
    outputNumber observe println
    for (line <- scala.io.Source.stdin.getLines)
      inputNumber.fire(line.toInt)
  }

}

object Client extends App {
  multitier start new Instance[OutsourcedComputationReactive.Client](
    connect[OutsourcedComputationReactive.PowerfulServer] {
      TCP("localhost", 40001)
    } and
      connect[OutsourcedComputationReactive.WeakServer] {
        TCP("localhost", 40002)
      }
  )
}

object PowerfulServer extends App {
  multitier start new Instance[OutsourcedComputationReactive.PowerfulServer](
    listen[OutsourcedComputationReactive.Client] {
      TCP(40001)
    }
  )
}

object WeakServer extends App {
  multitier start new Instance[OutsourcedComputationReactive.WeakServer](
    listen[OutsourcedComputationReactive.Client] {
      TCP(40002)
    }
  )
}
