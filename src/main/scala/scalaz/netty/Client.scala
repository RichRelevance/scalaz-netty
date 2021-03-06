/*
 * Copyright 2016 RichRelevance
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

import java.net.InetSocketAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicReference

import io.netty.bootstrap._
import io.netty.buffer._
import io.netty.channel._
import io.netty.channel.socket._
import io.netty.handler.codec._
import scodec.bits.ByteVector

import scalaz.concurrent._
import scalaz.stream._
import scalaz.syntax.monad._

private[netty] final class Client(channel: _root_.io.netty.channel.Channel, queue: BPAwareQueue[ByteVector], halt: AtomicReference[Cause]) {

  def read: Process[Task, ByteVector] = queue.dequeue(channel.config)

  def write(implicit pool: ExecutorService): Sink[Task, ByteVector] = {
    def inner(bv: ByteVector): Task[Unit] = {
      Task delay {
        val data = bv.toArray
        val buf = channel.alloc().buffer(data.length)
        buf.writeBytes(data)

        channel.eventLoop().execute(new Runnable() {
          override def run: Unit = {
            channel.writeAndFlush(buf)
          }
        })
      }
    }

    // TODO termination
    Process constant inner _
  }

  def shutdown(implicit pool: ExecutorService): Process[Task, Nothing] = {
    val close = for {
      _ <- Netty toTask channel.close()
      _ <- queue.close
    } yield ()

    Process eval_ close causedBy halt.get
  }
}

private[netty] final class ClientHandler(queue: BPAwareQueue[ByteVector], halt: AtomicReference[Cause]) extends ChannelInboundHandlerAdapter {

  override def channelInactive(ctx: ChannelHandlerContext): Unit = {
    // if the connection is remotely closed, we need to clean things up on our side
    queue.close.unsafePerformSync

    super.channelInactive(ctx)
  }

  override def channelRead(ctx: ChannelHandlerContext, msg: AnyRef): Unit = {
    val buf = msg.asInstanceOf[ByteBuf]
    val dst = Array.ofDim[Byte](buf.readableBytes())
    buf.readBytes(dst)
    val bv = ByteVector.view(dst)

    buf.release()

    queue.enqueueOne(ctx.channel.config, bv).unsafePerformSync
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, t: Throwable): Unit = {
    halt.set(Cause.Error(t))
    queue.close.unsafePerformSync
  }
}

private[netty] object Client {
  def apply(to: InetSocketAddress, config: ClientConfig)(implicit pool: ExecutorService, S: Strategy): Task[Client] = Task delay {
    //val client = new Client(config.limit)
    val bootstrap = new Bootstrap

    val queue = BPAwareQueue[ByteVector](config.limit)
    val halt = new AtomicReference[Cause](Cause.End)

    val workerPool = config.eventLoopPool.getOrElse(config.eventLoopType.clientWorkerGroup(1))

    bootstrap.group(workerPool)
    bootstrap.channel(config.eventLoopType.channel)
    bootstrap.option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)

    bootstrap.option[java.lang.Boolean](ChannelOption.SO_KEEPALIVE, config.keepAlive)
    bootstrap.option[java.lang.Boolean](ChannelOption.TCP_NODELAY, config.tcpNoDelay)
    config.soSndBuf.foreach(bootstrap.option[java.lang.Integer](ChannelOption.SO_SNDBUF, _))
    config.soRcvBuf.foreach(bootstrap.option[java.lang.Integer](ChannelOption.SO_RCVBUF, _))

    bootstrap.handler(new ChannelInitializer[SocketChannel] {
      def initChannel(ch: SocketChannel): Unit = {
        ch.pipeline
          .addLast("frame encoding", new LengthFieldPrepender(4))
          .addLast("frame decoding", new LengthFieldBasedFrameDecoder(Int.MaxValue, 0, 4, 0, 4))
          .addLast("incoming handler", new ClientHandler(queue, halt))
      }
    })

    val connectF = bootstrap.connect(to)

    for {
      _ <- Netty toTask connectF
      client <- Task delay {
        new Client(connectF.channel(), queue, halt)
      }
    } yield client
  } join
}

final case class ClientConfig(keepAlive: Boolean, limit: Int, tcpNoDelay: Boolean, soSndBuf: Option[Int],
                              soRcvBuf: Option[Int], eventLoopType: EventLoopType, eventLoopPool: Option[EventLoopGroup])

object ClientConfig {
  def apply(keepAlive: Boolean, limit: Int, tcpNoDelay: Boolean, soSndBuf: Option[Int], soRcvBuf: Option[Int],
            eventLoopType: EventLoopType): ClientConfig = new ClientConfig(keepAlive, limit, tcpNoDelay, soSndBuf, soRcvBuf, eventLoopType, None)

  val Default = ClientConfig(true, 1000, false, None, None, EventLoopType.Select, None)
}
