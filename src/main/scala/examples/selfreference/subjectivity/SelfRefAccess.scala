package examples.selfreference.subjectivity

import loci._
import loci.communicator.tcp._
import loci.serializer.upickle._
import loci.transmitter.transmittable._

@multitier object SelfRefAccess {

  @peer type Node <: { type Tie <: Multiple[Node] }

  def selectNode(
    local: Boolean
  )(
    connected: Seq[Remote[Node]],
    self: SelfReference[Node]
  ): Local[Remote[Node]] on Node = on[Node] { implicit! =>
    if (local) self else connected.head
  }

  def main(): Unit on Node = on[Node] { implicit! =>
    for (line <- scala.io.Source.stdin.getLines) {
      onAny.apply[Node](selectNode(line.strip().isEmpty) _).run.sbj { implicit! =>
        r: Remote[Node] =>
          println(s"Connected: ${r.connected}")
          println(s"as remote: ${r.asRemote[Node]}")
          on(r).run { implicit! => println("remote block on SelfReference worked") }
      }
    }
  }

}

object A extends App {
  multitier start new Instance[SelfRefAccess.Node](
    listen[SelfRefAccess.Node](TCP(40001))
  )
}

object B extends App {
  multitier start new Instance[SelfRefAccess.Node](
    connect[SelfRefAccess.Node](TCP("localhost", 40001))
  )
}
