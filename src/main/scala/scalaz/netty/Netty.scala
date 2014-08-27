package scalaz
package netty

import concurrent._
import stream._

import scodec.bits.ByteVector

import java.net.InetSocketAddress

object Netty {
  def server(bind: InetSocketAddress): Process[Task, Process[Task, Exchange[ByteVector, ByteVector]]] = ???
  def connect(to: InetSocketAddress): Process[Task, Exchange[ByteVector, ByteVector]] = ???
}
