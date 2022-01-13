package casestudies.treedb

import loci._
import loci.communicator.tcp._
import loci.contexts.Immediate.Implicits.global
import loci.serializer.upickle._
import loci.transmitter.transmittable._
import upickle.default._

import scala.collection.mutable
import scala.concurrent.Future

@multitier object TreeDB {

  @peergroup type CLI

  @peergroup type DataNode <: { type Tie <: Multiple[Child] }
  @peergroup type Parent <: DataNode { type Tie <: Multiple[Child] }
  @peergroup type Child <: DataNode { type Tie <: Single[Parent] with Multiple[Child] }
  @peer type DataSubNode <: Parent with Child with CLI { type Tie <: Single[Parent] with Multiple[Child] }

  @peer type Client <: Parent with CLI { type Tie <: Single[DataSubNode] }

  val n = 1000

  val partition: Local[mutable.Map[Int, String]] on DataNode = on[DataNode] local { implicit! =>
    mutable.Map.empty[Int, String]
  }

  var keyRange: Local[(Int, Int)] on DataNode = on[DataNode] local { implicit! => (0, 0) }
  val childrenKeyRanges: Local[mutable.Map[Remote[Child], (Int, Int)]] on DataNode = on[DataNode] local { implicit! =>
    mutable.Map.empty[Remote[Child], (Int, Int)]
  }

  def selectNode(key: Int)(children: Seq[Remote[Child]], self: SelfReference[Child]): Local[Remote[Child]] on Child = on[Child] { implicit! =>
    val keyHash = key % n
    println(keyHash)
    if (keyHash >= keyRange._1 && keyHash < keyRange._2) {
      self
    } else {
      children.find(child => keyHash >= childrenKeyRanges(child)._1 && keyHash < childrenKeyRanges(child)._2).get
    }
  }

  def insert(key: Int, value: String): Future[Option[String]] on Child = on[Child] { implicit! =>
    onAny.recursive[Child](selectNode(key) _).run.capture(key, value) { implicit! =>
      println(s"INSERT $key: $value")
      partition.put(key, value)
    }.asLocal
  }

  def get(key: Int): Future[Option[String]] on Child = on[Child] { implicit! =>
    onAny.recursive[Child](selectNode(key) _).run.capture(key) { implicit! =>
      println(s"GET $key")
      partition.get(key)
    }.asLocal
  }

  def getNodesInChildrenSubtrees(): Future[Int] on DataNode = on[DataNode] { implicit! =>
    val children = remote[Child].connected
    Future.sequence(
      children.map { child =>
        remote(child).call(getNodesInChildrenSubtrees()).asLocal.map((nodes: Int) => child -> nodes)
      }
    ).map(_.map(_._2).sum + 1)
  }

  def initKeyRanges(lo: Int, hi: Int): Unit on DataNode = on[DataNode] { implicit! =>
    val children = remote[Child].connected
    Future.sequence(
      children.map { child =>
        remote(child).call(getNodesInChildrenSubtrees()).asLocal.map((nodes: Int) => child -> nodes)
      }
    ).foreach { childNodes =>
      val overallNodes = childNodes.map(_._2).sum + 1
      val rangeSizePerNode = (hi - lo) / overallNodes
      var index = 0
      childNodes.foreach {
        case (child, nodesInChildSubtree) =>
          val childLo = lo + index * rangeSizePerNode
          val childHi = lo + (index + nodesInChildSubtree) * rangeSizePerNode
          remote(child).call(initKeyRanges(childLo, childHi))
          childrenKeyRanges.put(child, (childLo, childHi))
          index += nodesInChildSubtree
      }
      keyRange = (lo + index * rangeSizePerNode, hi)
      println(keyRange)
      println(childrenKeyRanges)
    }
  }

  def main(): Unit on CLI = on[DataSubNode] { implicit! =>
    for (_ <- scala.io.Source.stdin.getLines()) {
      println("INITIALIZE KEY RANGES")
      initKeyRanges(0, n)
    }
  } and on[Client] { implicit! =>
    for (line <- scala.io.Source.stdin.getLines()) {
      line.split(' ').toSeq match {
        case Seq("insert", key, value) => remote[DataSubNode].call(insert(key.toInt, value))
        case Seq("get", key) => remote[DataSubNode].call(get(key.toInt)).asLocal.foreach(println)
      }
    }
  }

}

object Root extends App {
  multitier start new Instance[TreeDB.DataSubNode](
    connect[TreeDB.Child](TCP("localhost", 50001)) and
      connect[TreeDB.Child](TCP("localhost", 50002)) and
      listen[TreeDB.Parent](TCP(50003))
  )
}

object Left extends App {
  multitier start new Instance[TreeDB.DataSubNode](
    listen[TreeDB.Parent](TCP(50001))
  )
}

object Right extends App {
  multitier start new Instance[TreeDB.DataSubNode](
    listen[TreeDB.Parent](TCP(50002))
  )
}

object Client extends App {
  multitier start new Instance[TreeDB.Client](
    connect[TreeDB.DataSubNode](TCP("localhost", 50003))
  )
}
