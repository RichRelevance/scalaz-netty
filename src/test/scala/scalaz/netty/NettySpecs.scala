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

import scodec.bits._

import scala.concurrent.duration._

import org.specs2.mutable._

import org.scalacheck._

import java.net.InetSocketAddress
import java.util.concurrent.{Executors, ThreadFactory}

object NettySpecs extends Specification {

  sequential

  val scheduler = {
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
          results <- exchange.read.runLog timed (5 seconds)

          _ <- Task delay {
            results must haveSize(1)
            results must contain(data)
          }
        } yield ()

        Process.eval(initiate.run >> check).drain
      }

      val delay = time.sleep(200 millis)(Strategy.DefaultStrategy, scheduler)

      val test = server.drain merge (delay ++ client)

      test.run timed (15 seconds) run

      ok
    }

    "round trip some simple data to ten simultaneous clients" in {
      val addr = new InetSocketAddress("localhost", 9090)

      val server = merge.mergeN(Netty server addr map { incoming =>
        incoming flatMap { exchange =>
          exchange.read take 1 to exchange.write drain
        }
      })

      def client(n: Int) = Netty connect addr flatMap { exchange =>
        val data = ByteVector(n)

        for {
          _ <- Process(data) to exchange.write
          results <- Process eval (exchange.read.runLog timed (5 seconds))
          bv <- Process emitAll results
        } yield (n -> bv)
      }

      val delay = time.sleep(200 millis)(Strategy.DefaultStrategy, scheduler)

      val test = (server.drain wye merge.mergeN(Process.range(0, 10) map { n => delay ++ client(n) }))(wye.mergeHaltBoth)

      val results = test.runLog timed (15 seconds) run

      results must haveSize(10)
      results must containAllOf(0 until 10 map { n => n -> ByteVector(n) })
    }

    "terminate a client process with an error if connection failed" in {
      val addr = new InetSocketAddress("localhost", 51235) // hopefully no one is using this port...

      val client = Netty connect addr map { _ => () }

      val result = client.run.attempt.run

      result must beLike {
        case -\/(_) => ok
      }
    }

    "terminate a client process if connection times out" in {
      val addr = new InetSocketAddress("100.64.0.1", 51234) // reserved IP, very weird port

      val client = Netty connect addr map { _ => () }

      val result = client.run.attempt.run

      result must eventually(beLike[Throwable \/ Unit] {
        case -\/(_) => ok
      })
    }

    "not lose data on client in rapid-closure scenario" in {
      forall(0 until 10) { i =>
        val addr = new InetSocketAddress("localhost", 9090 + i)
        val data = ByteVector(1, 2, 3)

        val server = for {
          incoming <- Netty server addr take 1
          Exchange(_, write) <- incoming
          _ <- write take 1 evalMap { _(data) }
        } yield ()        // close connection instantly

        val client = for {
          _ <- time.sleep(500 millis)(Strategy.DefaultStrategy, scheduler) ++ Process.emit(())
          Exchange(read, _) <- Netty connect addr
          back <- read take 1
        } yield back

        val driver: Process[Task, ByteVector] = server.drain merge client
        val task = (driver wye time.sleep(3 seconds)(Strategy.DefaultStrategy, scheduler))(wye.mergeHaltBoth).runLast

        task.run must beSome(data)
      }
    }

    "not lose data on server in rapid-closure scenario" in {
      forall(0 until 10) { i =>
        val addr = new InetSocketAddress("localhost", 9090 + i)
        val data = ByteVector(1, 2, 3)

        val server = for {
          incoming <- Netty server addr take 1
          Exchange(read, _) <- incoming
          back <- read take 1
        } yield back

        val client = for {
          _ <- time.sleep(500 millis)(Strategy.DefaultStrategy, scheduler) ++ Process.emit(())
          Exchange(_, write) <- Netty connect addr
          _ <- write take 1 evalMap { _(data) }
        } yield ()      // close connection instantly

        val task = ((server merge client.drain) wye time.sleep(3 seconds)(Strategy.DefaultStrategy, scheduler))(wye.mergeHaltBoth).runLast

        task.run must beSome(data)
      }
    }

    def roundTripTest(port: Int,
                      noOfPackets: Int,
                      clientBpQueueLimit: Int,
                      serverBpQueueLimit: Int,
                      clientSendSpeed: Int, // messages per second
                      clientReceiveSpeed: Int, // messages per second
                      serverSendSpeed: Int, // messages per second
                      serverReceiveSpeed: Int, // messages per second
                      dataMultiplier: Int = 1 // sizing the packet
                       ) = {

      val deadbeef = ByteVector(0xDE, 0xAD, 0xBE, 0xEF) //4 bytes
      val addr = new InetSocketAddress("localhost", port)
      val data =  (1 to dataMultiplier).foldLeft(deadbeef)((a, counter) => a++deadbeef)

      val clientReceiveClock = time.awakeEvery((1000000 / clientReceiveSpeed).microseconds)(Strategy.DefaultStrategy, Executors.newScheduledThreadPool(1))
      val clientSendClock = time.awakeEvery((1000000 / clientSendSpeed).microseconds)(Strategy.DefaultStrategy, Executors.newScheduledThreadPool(1))

      val serverReceiveClock = time.awakeEvery((1000000 / serverReceiveSpeed).microseconds)(Strategy.DefaultStrategy, Executors.newScheduledThreadPool(1))
      val serverSendClock = time.awakeEvery((1000000 / serverSendSpeed).microseconds)(Strategy.DefaultStrategy, Executors.newScheduledThreadPool(1))

      val server = (Netty.server(addr,
        ServerConfig(
          keepAlive = true,
          numThreads = Runtime.getRuntime.availableProcessors,
          limit = serverBpQueueLimit,
          codeFrames = true,
          tcpNoDelay = true,
          soSndBuf = None,
          soRcvBuf = None
        ))) take 1 flatMap { incoming =>
          incoming flatMap { exchange =>
            exchange.read.zip(serverReceiveClock).map {
              case (a, _) => a
            }.take(noOfPackets).zip(serverSendClock).map {
              case (a, _) => a
            } to exchange.write drain
          }
        }

      val client = Netty.connect(addr,
        ClientConfig(
          keepAlive = true,
          limit = clientBpQueueLimit,
          tcpNoDelay = true,
          soSndBuf = None,
          soRcvBuf = None)
      ).flatMap { exchange =>
        val initiate = Process(data).repeat.take(noOfPackets).zip(clientSendClock).map {
          case (a, _) => a
        } to exchange.write

        val check = for {
          results <- exchange.read.zip(clientReceiveClock).map {
            case (a, _) => a
          }.runLog
          _ <- Task delay {
            results must haveSize(noOfPackets)
          }
        } yield ()

        Process.eval_(for (_ <- initiate.run; last <- check) yield (()))
      }

      val delay = time.sleep(500 millis)(Strategy.DefaultStrategy, scheduler)

      val test = server merge (delay ++ client)

      test.run timed ((10 + noOfPackets / Math.min(Math.min(serverReceiveSpeed, serverSendSpeed), Math.min(clientReceiveSpeed, clientSendSpeed))) seconds) run

      ok
    }

    "round trip more data with slow client receive" in {
      roundTripTest(
        port = 51236,
        noOfPackets = 1000,
        clientBpQueueLimit = 10,
        serverBpQueueLimit = 1000,
        clientSendSpeed = 10000,
        clientReceiveSpeed = 200,
        serverSendSpeed = 10000,
        serverReceiveSpeed = 10000
      )
    }


    "round trip more data with slow server receive" in {
      roundTripTest(
        port = 51237,
        noOfPackets = 1000,
        clientBpQueueLimit = 10,
        serverBpQueueLimit = 1000,
        clientSendSpeed = 10000,
        clientReceiveSpeed = 10000,
        serverSendSpeed = 10000,
        serverReceiveSpeed = 200
      )
    }

    "round trip lots of data fast with small buffers" in {
      roundTripTest(
        port = 51238,
        noOfPackets = 10000,
        clientBpQueueLimit = 10,
        serverBpQueueLimit = 10,
        clientSendSpeed = 2000,
        clientReceiveSpeed = 2000,
        serverSendSpeed = 2000,
        serverReceiveSpeed = 2000
      )
    }

    "round trip some huge packets" in {
      roundTripTest(
        port = 51239,
        noOfPackets = 10,
        clientBpQueueLimit = 10,
        serverBpQueueLimit = 10,
        clientSendSpeed = 10000,
        clientReceiveSpeed = 10000,
        serverSendSpeed = 10000,
        serverReceiveSpeed = 10000,
        dataMultiplier  = 256*256
      )
    }

  }
}
