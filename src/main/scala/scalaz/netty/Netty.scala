package scalaz
package netty

import concurrent._
import stream._

import scodec.bits.ByteVector

import java.net.InetSocketAddress

import _root_.io.netty.channel._

/**
 * It is highly advisable to fork the resulting tasks out of this thread pool
 * if you are planning on doing any non-trivial computation with the data retrieved.
 */
object Netty {

  def server(bind: InetSocketAddress, config: ServerConfig = ServerConfig.Default): Process[Task, (InetSocketAddress, Process[Task, Exchange[ByteVector, ByteVector]])] = {
    Process.await(Server(bind, config)) { server: Server =>
      server.listen onComplete Process.eval(server.shutdown).drain
    }
  }

  def connect(to: InetSocketAddress, config: ClientConfig = ClientConfig.Default): Process[Task, Exchange[ByteVector, ByteVector]] = {
    Process.await(Client(to, config)) { client: Client =>
      Process(Exchange(client.read, client.write)) onComplete Process.eval(client.shutdown).drain
    }
  }

  private[netty] def toTask(f: ChannelFuture): Task[Unit] = Task fork {
    Task async { (cb: (Throwable \/ Unit) => Unit) =>
      f.addListener(new ChannelFutureListener {
        def operationComplete(f: ChannelFuture): Unit = {
          if (f.isSuccess)
            cb(\/-(()))
          else
            cb(-\/(f.cause))
        }
      })
    }
  }
}

