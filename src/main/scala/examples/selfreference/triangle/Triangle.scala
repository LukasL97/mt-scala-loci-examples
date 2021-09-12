package examples.selfreference.triangle

import loci._
import loci.communicator.tcp._
import loci.serializer.upickle._

import scala.util.Random


@multitier object Triangle {

  @peer type Node <: { type Tie <: Multiple[Node] }

  def selectNode(connected: Seq[Remote[Node]], self: SelfReference[Node]): Remote[Node] = {
    val nodes = connected :+ self
    nodes((new Random).nextInt(nodes.size))
  }

  def compute: Unit on Node = on[Node] { implicit! =>
    val result = onAny.apply[Node](selectNode _).run { implicit! =>
      println("I was selected")
      42
    }.asLocal_!
    println(result)
  }

  def main(): Unit on Node = on[Node] { implicit! =>
    for (line <- scala.io.Source.stdin.getLines) {
      compute
    }
  }

}

object NodeA extends App {
  multitier start new Instance[Triangle.Node](
    listen[Triangle.Node](TCP(40001)) and
      listen[Triangle.Node](TCP(40002))
  )
}

object NodeB extends App {
  multitier start new Instance[Triangle.Node](
    connect[Triangle.Node](TCP("localhost", 40001)) and
      listen[Triangle.Node](TCP(40003))
  )
}

object NodeC extends App {
  multitier start new Instance[Triangle.Node](
    connect[Triangle.Node](TCP("localhost", 40002)) and
      connect[Triangle.Node](TCP("localhost", 40003))
  )
}
