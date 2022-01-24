package test

import loci._
import loci.valueref._
import loci.communicator.tcp._
import loci.serializer.upickle._
import loci.transmitter.transmittable._
import upickle.default._

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

@multitier object Module {
  @peer type Node <: { type Tie <: Multiple[Node] }
  @peer type Other <: { type Tie <: Multiple[Node] }

  def f(x: Int via Node): Unit on Node = on[Node] { implicit! =>
    val y: Remote[Node] = x.getRemote
  }
}
