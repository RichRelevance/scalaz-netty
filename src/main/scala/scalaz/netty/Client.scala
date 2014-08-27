package scalaz
package netty

import concurrent._
import stream._
import syntax.monad._

import scodec.bits.ByteVector

import java.net.InetSocketAddress
import java.util.concurrent.ExecutorService

import _root_.io.netty.bootstrap._
import _root_.io.netty.buffer._
import _root_.io.netty.channel._
import _root_.io.netty.channel.nio._
import _root_.io.netty.channel.socket._
import _root_.io.netty.channel.socket.nio._
import _root_.io.netty.handler.codec._

private[netty] final class Client(workerGroup: NioEventLoopGroup, limit: Int) {
  // this isn't ugly or anything...
  private var channel: _root_.io.netty.channel.Channel = _

  private val queue = async.boundedQueue[ByteVector](limit)

  def read: Process[Task, ByteVector] = queue.dequeue

  def write: Sink[Task, ByteVector] = {
    def inner(bv: ByteVector): Task[Unit] = {
      Task delay {
        val data = bv.toArray
        val buf = channel.alloc().buffer(data.length)
        buf.writeBytes(data)

        Netty toTask channel.writeAndFlush(buf)
      } join
    }

    // TODO termination
    Process constant (inner _)
  }

  def shutdown: Task[Unit] = {
    for {
      _ <- Netty toTask channel.close()
      _ <- queue.close

      _ <- Task delay {
        workerGroup.shutdownGracefully()
      }
    } yield ()
  }
}

private[netty] object Client {
  def apply(to: InetSocketAddress, config: ClientConfig): Task[Client] = Task delay {
    // TODO share this pool
    val workerGroup = new NioEventLoopGroup(1)

    val client = new Client(workerGroup, config.limit)
    val bootstrap = new Bootstrap

    bootstrap.group(workerGroup)
    bootstrap.channel(classOf[NioSocketChannel])

    bootstrap.option[java.lang.Boolean](ChannelOption.SO_KEEPALIVE, config.keepAlive)

    bootstrap.handler(new ChannelInitializer[SocketChannel] {
      def initChannel(ch: SocketChannel): Unit = {
        ch.pipeline
          .addLast("frame encoding", new LengthFieldPrepender(4))
          .addLast("frame decoding", new LengthFieldBasedFrameDecoder(Int.MaxValue, 0, 4, 0, 4))
      }
    })

    val connectF = bootstrap.connect(to)

    for {
      _ <- Netty toTask connectF
      _ <- Task delay {
        client.channel = connectF.channel()
      }
    } yield client
  } join
}

final case class ClientConfig(keepAlive: Boolean, limit: Int)

object ClientConfig {
  val Default = ClientConfig(true, 1000)
}
