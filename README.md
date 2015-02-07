# scalaz-netty

[![Join the chat at https://gitter.im/RichRelevance/scalaz-netty](https://badges.gitter.im/Join%20Chat.svg)](https://gitter.im/RichRelevance/scalaz-netty?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)

[![Build Status](https://travis-ci.org/RichRelevance/scalaz-netty.svg?branch=master)](http://travis-ci.org/RichRelevance/scalaz-netty)
[![Gitter Chat](https://badges.gitter.im/RichRelevance/scalaz-netty.svg)](https://gitter.im/RichRelevance/scalaz-netty)

Some basic usage below:

```scala
import scalaz.netty._

/*
 * A simple server which accepts a connection, echos the incoming
 * data back to the sender, waiting for the client to close the connection.
 */

def log(msg: String): Task[Unit] = ???

val address = new InetSocketAddress("localhost", 9090)

val EchoServer = merge.mergeN(Netty server address map { incoming =>
  case (addr, incoming) => {
    for {
      exchange <- incoming
      _ <- Process.eval(log(s"accepted connection from $addr"))
      _ <- exchange.read to exchange.write
    } yield ()
  }
})

/*
 * A simple client which sends ByteVector(1, 2, 3) to the server,
 * prints its response and then shuts down.
 */

val DumbClient = Netty client address flatMap { exchange =>
  for {
    _ <- Process(ByteVector(1, 2, 3)) to exchange.write
    data <- exchange.read take 1
    _ <- Process.eval(log(s"received data = $data"))
  } yield ()
}
```

## Future Work

- Byte buffers are copied upon receipt.  The only way to *safely* address this problem will be to integrate with Scodec and decode against the directly allocated byte buffers.  Not hard to do, really...
- Exceptions probably don't propagate properly under all circumstances.

## License

Licensed under the Apache License 2.0.  For more information, please see `LICENSE.txt`.  Opening a pull request signifies your consent to license your contributions under the Apache License 2.0.  Don't open a pull request if you don't know what this means.
