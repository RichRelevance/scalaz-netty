# scalaz-netty

[![Build Status](https://travis-ci.org/RichRelevance/scalaz-netty.svg?branch=master)](http://travis-ci.org/RichRelevance/scalaz-netty)
[![Gitter Chat](https://badges.gitter.im/RichRelevance/scalaz-netty.svg)](https://gitter.im/RichRelevance/scalaz-netty)

## Getting Started

First, you'll need to add the RichRelevance Bintray resolver setting to your SBT file:

```sbt
resolvers += "RichRelevance Bintray" at "http://dl.bintray.com/rr/releases"
```

If you want to be able to use snapshot releases, replace `releases` with `snapshots`.  With the resolver configured, add the following dependency specification:

```sbt
libraryDependencies += "org.scalaz.netty" %% "scalaz-netty" % "0.4.2"
```

Builds are published for Scala 2.11.8, and for 2.12.x for versions at or above 0.4.2.  The latest stable release is **0.4.2**.

Versions that end in 'a' in the 0.3.x series use scalaz 7.2.x, versions without use scalaz 7.1.x.

The 0.3.2 versions contain one additional feature over the 0.3, https://github.com/RichRelevance/scalaz-netty/pull/28, which has only been tested on Linux and may not work elsewhere.

The upstream dependencies for this project include the following:

For version 0.4.1 and 0.4.2:
- scalaz 7.2.7
- scalaz-stream 0.8.6a
- scodec-bits 1.1.2
- netty 4.0.42.Final

For version 0.4:
- scalaz 7.2.5
- scalaz-stream 0.8.4a
- scodec-bits 1.1.0
- netty 4.0.40.Final

For version 0.3 and 0.3.2:

- scalaz 7.1.7
- scalaz-stream 0.8
- scodec-bits 1.0.12
- netty 4.0.36.Final


And for version 0.3a and 0.3.2a:

- scalaz 7.2.2
- scalaz-stream 0.8a
- scodec-bits 1.0.12
- netty 4.0.36.Final

Snapshot releases follow the version scheme `master-<sha1>`, where the "`sha1`" component is the Git commit that was snapshotted.  Not all commits will have corresponding snapshot releases.  You can browse the list of snapshot releases [on bintray](https://bintray.com/rr/snapshots/scalaz-netty/view).

## Example

```scala
import scalaz.netty._
import scalaz.stream._
import scalaz.concurrent._
import java.net.InetSocketAddress
import scodec.bits.ByteVector

/*
 * A simple server which accepts a connection, echos the incoming
 * data back to the sender, waiting for the client to close the connection.
 */

def log(msg: String): Task[Unit] = Task.delay(println(s"$msg"))

val address = new InetSocketAddress("localhost", 9090)

val EchoServer = merge.mergeN(Netty server address map { incoming =>
  for {
    exchange <- incoming
    _ <- Process.eval(log("accepted connection"))
    _ <- exchange.read to exchange.write
  } yield ()
})

/*
 * A simple client which sends ByteVector(1, 2, 3) to the server,
 * prints its response and then shuts down.
 */

val BasicClient = Netty connect address flatMap { exchange =>
  for {
    _ <- Process(ByteVector(1, 2, 3)) to exchange.write
    data <- exchange.read take 1
    _ <- Process.eval(log(s"received data = $data"))
  } yield ()
}

// Usage:
// scala> EchoServer.run.runAsync(_ => ())  // press Enter when this completes to acquire new prompt
// scala> BasicClient.run.run
```

## Future Work

- Byte buffers are copied upon receipt.  The only way to *safely* address this problem will be to integrate with Scodec and decode against the directly allocated byte buffers.  Not hard to do, really...
- Exceptions probably don't propagate properly under all circumstances.

## License

Licensed under the Apache License 2.0.  For more information, please see `LICENSE.txt`.  Opening a pull request signifies your consent to license your contributions under the Apache License 2.0.  Don't open a pull request if you don't know what this means.
