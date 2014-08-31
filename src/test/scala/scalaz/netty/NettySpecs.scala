package scalaz
package netty

import concurrent._
import stream._
import syntax.monad._

import scodec.bits._

import scala.concurrent.duration._

import org.specs2.mutable._
import org.specs2.time.NoTimeConversions
import org.scalacheck._

import java.net.InetSocketAddress
import java.util.concurrent.{Executors, ThreadFactory}

object NettySpecs extends Specification with NoTimeConversions {

  implicit val scheduler = {
    Executors.newScheduledThreadPool(4, new ThreadFactory {
      def newThread(r: Runnable) = {
        val t = Executors.defaultThreadFactory.newThread(r)
        t.setDaemon(true)
        t.setName("scheduled-task-thread")
        t
      }
    })
  }

  "netty" should {
    "round trip some simple data" in {
      val addr = new InetSocketAddress("localhost", 9090)

      val server = Netty server addr take 1 flatMap { incoming =>
        incoming flatMap { exchange =>
          exchange.read take 1 to exchange.write drain
        }
      }

      val client = Netty connect addr flatMap { exchange =>
        val data = ByteVector(12, 42, 1)
        val initiate = Process(data) to exchange.write

        val check = for {
          results <- exchange.read.runLog

          _ <- Task delay {
            results must haveSize(1)
            results must contain(data)
          }
        } yield ()

        Process.eval(initiate.run >> check).drain
      }

      Nondeterminism[Task].both(Task fork server.run, Task fork (Process sleep (200 millis) fby client).run).run

      ok
    }
  }
}
