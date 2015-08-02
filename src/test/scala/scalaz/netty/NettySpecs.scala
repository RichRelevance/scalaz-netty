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

      val server = Netty server addr take 1 flatMap {
        case (_, incoming) => {
          incoming flatMap { exchange =>
            exchange.read take 1 to exchange.write drain
          }
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

      val server = Netty server addr flatMap {
        case (_, incoming) => {
          incoming flatMap { exchange =>
            exchange.read take 1 to exchange.write drain
          }
        }
      }

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
      val addr = new InetSocketAddress("localhost", 51235)         // hopefully no one is using this port...

      val client = Netty connect addr map { _ => () }

      val result = client.run.attempt.run

      result must beLike {
        case -\/(_) => ok
      }
    }

    "terminate a client process if connection times out" in {
      val addr = new InetSocketAddress("100.64.0.1", 51234)        // reserved IP, very weird port

      val client = Netty connect addr map { _ => () }

      val result = client.run.attempt.run

      result must eventually(beLike[Throwable \/ Unit] {
        case -\/(_) => ok
      })
    }
  }
}
