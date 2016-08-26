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

import concurrent._
import stream._
import syntax.monad._

import scodec.bits.ByteVector

import java.net.InetSocketAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicReference

import _root_.io.netty.bootstrap._
import _root_.io.netty.buffer._
import _root_.io.netty.channel._
import _root_.io.netty.channel.nio._
import _root_.io.netty.channel.socket._
import _root_.io.netty.channel.socket.nio._
import _root_.io.netty.handler.codec._

private[netty] class Server(bossGroup: EventLoopGroup, channel: _root_.io.netty.channel.Channel, queue: async.mutable.Queue[Process[Task, Exchange[ByteVector, ByteVector]]]) {
  server =>

  def listen: Process[Task, Process[Task, Exchange[ByteVector, ByteVector]]] =
    queue.dequeue

  def shutdown(implicit pool: ExecutorService): Task[Unit] = {
    for {
      _ <- Netty toTask channel.close()
      _ <- queue.close

      _ <- Task delay {
        bossGroup.shutdownGracefully()
      }
    } yield ()
  }
}

private[netty] final class ServerHandler(channel: SocketChannel, serverQueue: async.mutable.Queue[Process[Task, Exchange[ByteVector, ByteVector]]], limit: Int)(implicit pool: ExecutorService, S: Strategy) extends ChannelInboundHandlerAdapter {

  private val channelConfig = channel.config

  // data from a single connection
  private val queue = BPAwareQueue[ByteVector](limit)
  private val halt: AtomicReference[Cause] = new AtomicReference(Cause.End)

  override def channelActive(ctx: ChannelHandlerContext): Unit = {
    val process: Process[Task, Exchange[ByteVector, ByteVector]] =
      Process(Exchange(read, write)) onComplete shutdown

    serverQueue.enqueueOne(process).unsafePerformSync

    super.channelActive(ctx)
  }

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

    // because this is run and not runAsync, we have backpressure propagation
    queue.enqueueOne(channelConfig, bv).unsafePerformSync
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, t: Throwable): Unit = {
    halt.set(Cause.Error(t))
    queue.close.unsafePerformSync
  }

  // do not call more than once!
  private def read: Process[Task, ByteVector] = queue.dequeue(channelConfig)

  private def write: Sink[Task, ByteVector] = {
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

  def shutdown: Process[Task, Nothing] = {
    val close = for {
      _ <- Netty toTask channel.close()
      _ <- queue.close
    } yield ()

    Process eval_ close causedBy halt.get
  }
}

private[netty] object Server {
  def apply(bind: InetSocketAddress, config: ServerConfig)(implicit pool: ExecutorService, S: Strategy): Task[Server] = Task delay {
    val bossGroup = config.eventLoopType.bossGroup


    //val server = new Server(bossGroup, config.limit)
    val bootstrap = new ServerBootstrap
    bootstrap.childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)

    val serverQueue = async.boundedQueue[Process[Task, Exchange[ByteVector, ByteVector]]](config.limit)

    bootstrap.group(bossGroup, config.eventLoopType.serverWorkerGroup(config.numThreads))
      .channel(config.eventLoopType.serverChannel)
      .childOption[java.lang.Boolean](ChannelOption.SO_KEEPALIVE, config.keepAlive)
      .option[java.lang.Boolean](ChannelOption.TCP_NODELAY, config.tcpNoDelay)

    // these do not seem to work with childOption
    config.soSndBuf.foreach(bootstrap.option[java.lang.Integer](ChannelOption.SO_SNDBUF, _))
    config.soRcvBuf.foreach(bootstrap.option[java.lang.Integer](ChannelOption.SO_RCVBUF, _))

    bootstrap.childHandler(new ChannelInitializer[SocketChannel] {
      def initChannel(ch: SocketChannel): Unit = {
        if (config.codeFrames) {
          ch.pipeline
            .addLast("frame encoding", new LengthFieldPrepender(4))
            .addLast("frame decoding", new LengthFieldBasedFrameDecoder(Int.MaxValue, 0, 4, 0, 4))
        }

        ch.pipeline.addLast("incoming handler", new ServerHandler(ch, serverQueue, config.limit))
      }
    })

    val bindF = bootstrap.bind(bind)

    for {
      _ <- Netty toTask bindF
      server <- Task delay {
        new Server(bossGroup, bindF.channel(), serverQueue) // yeah!  I <3 Netty
      }
    } yield server
  } join
}

final case class ServerConfig(keepAlive: Boolean, numThreads: Int, limit: Int, codeFrames: Boolean, tcpNoDelay: Boolean, soSndBuf: Option[Int], soRcvBuf: Option[Int], eventLoopType: EventLoopType)

object ServerConfig {
  // 1000?  does that even make sense?
  val Default = ServerConfig(true, Runtime.getRuntime.availableProcessors/2, 1000, true, false, None, None, EventLoopType.Select)

}
