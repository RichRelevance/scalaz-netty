package scalaz.netty

import java.util.concurrent.ThreadFactory

import io.netty.channel.Channel
import io.netty.channel.EventLoopGroup
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.epoll.EpollEventLoopGroup
import io.netty.channel.epoll.EpollServerSocketChannel
import io.netty.channel.ServerChannel
import io.netty.channel.epoll.EpollSocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel

sealed trait EventLoopType {
  private[netty] def serverWorkerGroup(numberOfThreads: Int) = eventLoopGroup(numberOfThreads, "netty-server-worker")

  private[netty] def clientWorkerGroup(numberOfThreads: Int) = eventLoopGroup(numberOfThreads, "netty-client-worker")

  private[netty] def bossGroup: EventLoopGroup = eventLoopGroup(1, "netty-boss-group")

  private[netty] def eventLoopGroup(numberOfThreads: Int, name: String): EventLoopGroup

  private[netty] def channel: Class[_ <: Channel]

  private[netty] def serverChannel: Class[_ <: ServerChannel]
}


object EventLoopType {

  object Select extends EventLoopType {

    private[netty] def eventLoopGroup(numberOfThreads: Int, name: String) = new NioEventLoopGroup(numberOfThreads, workerGroup(name))

    private[netty] def serverChannel = classOf[NioServerSocketChannel]

    private[netty] def channel: Class[_ <: Channel] = classOf[NioSocketChannel]

  }

  object Epoll extends EventLoopType {

    private[netty] def eventLoopGroup(numberOfThreads: Int, name: String) = new EpollEventLoopGroup(numberOfThreads, workerGroup(name))

    private[netty] def serverChannel = classOf[EpollServerSocketChannel]

    private[netty] def channel = classOf[EpollSocketChannel]

  }

  private def workerGroup(name: String) = new ThreadFactory {
    def newThread(r: Runnable): Thread = {
      val back = new Thread(r)
      back.setName(name)
      back.setDaemon(true) // we're never going to clean this up
      back.setPriority(8) // get in, and get out.  fast.
      back
    }
  }

}