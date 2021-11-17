package test

import loci._
import loci.communicator.tcp._
import loci.language.from
import loci.serializer.upickle._
import loci.transmitter.transmittable._
import upickle.default._

import scala.concurrent.Future

@multitier trait Mixin {
  @peer type A
  @peer type B
}

@multitier object Module extends Mixin {
  val x: Int on (A | B) = on[A | B] { implicit! => 42 }
}
