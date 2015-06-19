/*
 * Copyright 2015 RichRelevance
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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

private[netty] final class Client(limit: Int) {
  // this isn't ugly or anything...
  @volatile
  private var channel: _root_.io.netty.channel.Channel = _

  private val queue = async.boundedQueue[ByteVector](limit)

  def read: Process[Task, ByteVector] = queue.dequeue

  def write(implicit pool: ExecutorService): Sink[Task, ByteVector] = {
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

  def shutdown(implicit pool: ExecutorService): Task[Unit] = {
    for {
      _ <- Netty toTask channel.close()
      _ <- queue.close
    } yield ()
  }

  private final class Handler extends ChannelInboundHandlerAdapter {

    override def channelInactive(ctx: ChannelHandlerContext): Unit = {
      // if the connection is remotely closed, we need to clean things up on our side
      queue.close.run

      super.channelInactive(ctx)
    }

    override def channelRead(ctx: ChannelHandlerContext, msg: AnyRef): Unit = {
      val buf = msg.asInstanceOf[ByteBuf]
      val dst = Array.ofDim[Byte](buf.capacity())
      buf.getBytes(0, dst)
      val bv = ByteVector(dst) // copy data (alternatives are insanely clunky)

      buf.release()

      // because this is run and not runAsync, we have backpressure propagation
      queue.enqueueOne(bv).run
    }

    override def exceptionCaught(ctx: ChannelHandlerContext, t: Throwable): Unit = {
      queue.fail(t).run

      // super.exceptionCaught(ctx, t)
    }
  }
}

private[netty] object Client {
  def apply(to: InetSocketAddress, config: ClientConfig)(implicit pool: ExecutorService): Task[Client] = Task delay {
    val client = new Client(config.limit)
    val bootstrap = new Bootstrap

    bootstrap.group(Netty.workerGroup)
    bootstrap.channel(classOf[NioSocketChannel])

    bootstrap.option[java.lang.Boolean](ChannelOption.SO_KEEPALIVE, config.keepAlive)

    bootstrap.handler(new ChannelInitializer[SocketChannel] {
      def initChannel(ch: SocketChannel): Unit = {
        ch.pipeline
          .addLast("frame encoding", new LengthFieldPrepender(4))
          .addLast("frame decoding", new LengthFieldBasedFrameDecoder(Int.MaxValue, 0, 4, 0, 4))
          .addLast("incoming handler", new client.Handler)
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
