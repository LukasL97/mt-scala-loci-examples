package examples.recursive.abc

import loci._
import loci.communicator.tcp._
import loci.serializer.upickle._
import loci.transmitter.transmittable._

@multitier object ABC {

  @peer type Node <: { type Tie <: Multiple[Node] }
  @peer type A <: Node { type Tie <: Multiple[Node] }
  @peer type B <: Node { type Tie <: Multiple[Node] }
  @peer type C <: Node { type Tie <: Multiple[Node] }

  def selectNode(connected: Seq[Remote[Node]], self: SelfReference[Node]): Local[Remote[Node]] on Node = on[Node] { implicit! =>
    throw new NotImplementedError
  } and on[A] { implicit! =>
    println("Selection on A => Selected B")
    connected.flatMap(_.asRemote[B]).head
  } and on[B] { implicit! =>
    println("Selection on B => Selected C")
    connected.flatMap(_.asRemote[C]).head
  } and on[C] { implicit! =>
    println("Selection on C => Selected self")
    self
  }

  def f(x: Int)(y: Int): Int on Node = on[Node] { implicit! =>
    throw new NotImplementedError
  } and on[A] { implicit! =>
    println("A")
    x + y
  } and on[B] { implicit! =>
    println("B")
    x + y
  } and on[C] { implicit! =>
    println("C")
    x + y
  }

  def main(): Unit on Node = on[Node] { implicit! =>
    for (_ <- scala.io.Source.stdin.getLines) {
      remoteAny.recursive[Node](selectNode _).call(f(1)(2)).asLocal
    }
  }

}

object A extends App {
  multitier start new Instance[ABC.A](
    listen[ABC.B](TCP(40001))
  )
}

object B extends App {
  multitier start new Instance[ABC.B](
    connect[ABC.A](TCP("localhost", 40001)) and
      listen[ABC.C](TCP(40002))
  )
}

object C extends App {
  multitier start new Instance[ABC.C](
    connect[ABC.B](TCP("localhost", 40002))
  )
}
