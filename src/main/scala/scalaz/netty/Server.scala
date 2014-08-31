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

private[netty] class Server(bossGroup: NioEventLoopGroup, workerGroup: NioEventLoopGroup, limit: Int) { server =>
  // this isn't ugly or anything...
  private var channel: _root_.io.netty.channel.Channel = _

  // represents incoming connections
  private val queue = async.boundedQueue[Process[Task, Exchange[ByteVector, ByteVector]]](limit)

  def listen: Process[Task, Process[Task, Exchange[ByteVector, ByteVector]]] =
    queue.dequeue

  def shutdown: Task[Unit] = {
    for {
      _ <- Netty toTask channel.close()
      _ <- queue.close

      _ <- Task delay {
        workerGroup.shutdownGracefully()
        bossGroup.shutdownGracefully()
      }
    } yield ()
  }

  private final class Handler(channel: SocketChannel) extends ChannelInboundHandlerAdapter {

    // data from a single connection
    private val queue = async.boundedQueue[ByteVector](limit)

    override def channelActive(ctx: ChannelHandlerContext): Unit = {
      val process: Process[Task, Exchange[ByteVector, ByteVector]] =
        Process(Exchange(read, write)) onComplete Process.eval(shutdown).drain

      server.queue.enqueueOne(process).run

      super.channelActive(ctx)
    }

    override def channelInactive(ctx: ChannelHandlerContext): Unit = {
      // if the connection is remotely closed, we need to clean things up on our side
      queue.close.run

      super.channelInactive(ctx)
    }

    override def channelRead(ctx: ChannelHandlerContext, msg: AnyRef): Unit = {
      val buf = msg.asInstanceOf[ByteBuf]
      val bv = ByteVector(buf.nioBuffer)       // copy data (alternatives are insanely clunky)
      buf.release()

      // because this is run and not runAsync, we have backpressure propagation
      queue.enqueueOne(bv).run
    }

    override def exceptionCaught(ctx: ChannelHandlerContext, t: Throwable): Unit = {
      queue.fail(t).run

      // super.exceptionCaught(ctx, t)
    }

    // do not call more than once!
    private def read: Process[Task, ByteVector] = queue.dequeue

    private def write: Sink[Task, ByteVector] = {
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
      } yield ()
    }
  }
}

private[netty] object Server {
  def apply(bind: InetSocketAddress, config: ServerConfig): Task[Server] = Task delay {
    val bossGroup = new NioEventLoopGroup(config.numThreads)

    // TODO share this pool
    val workerGroup = new NioEventLoopGroup(1)

    val server = new Server(bossGroup, workerGroup, config.limit)
    val bootstrap = new ServerBootstrap

    bootstrap.group(bossGroup, workerGroup)
      .channel(classOf[NioServerSocketChannel])
      .childOption[java.lang.Boolean](ChannelOption.SO_KEEPALIVE, config.keepAlive)
      .childHandler(new ChannelInitializer[SocketChannel] {
        def initChannel(ch: SocketChannel): Unit = {
          // TODO if we want this to be a generally-useful library, we should probably make frame coding configurable
          ch.pipeline
            .addLast("frame encoding", new LengthFieldPrepender(4))
            .addLast("frame decoding", new LengthFieldBasedFrameDecoder(Int.MaxValue, 0, 4, 0, 4))
            .addLast("incoming handler", new server.Handler(ch))
        }
      })

    val bindF = bootstrap.bind(bind)

    for {
      _ <- Netty toTask bindF
      _ <- Task delay {
        server.channel = bindF.channel()      // yeah!  I <3 Netty
      }
    } yield server
  } join
}

final case class ServerConfig(keepAlive: Boolean, numThreads: Int, limit: Int)

object ServerConfig {
  // 1000?  does that even make sense?
  val Default = ServerConfig(true, Runtime.getRuntime.availableProcessors, 1000)
}
