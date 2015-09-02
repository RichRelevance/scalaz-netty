package scalaz.netty

import java.util.concurrent.atomic.AtomicLong
import io.netty.channel.ChannelConfig
import scalaz.concurrent.{Strategy, Task}
import scalaz.stream._


/**
 * A wrapper around async queue to implement back pressure by means of calling Netty's ChannelConfig.setAutoRead when needed.
 *
 * @param limit the target limit. It's the number of logical messages after which back pressure kicks in. Please note that the actual number of messages in the queue will be
 * higher as netty will continue flushing its buffer.
 *
 * @tparam A the type of used messages
 */
private[netty] final class BPAwareQueue[A](val limit: Int) {

  // we don't need a bound here as we do back pressure by calling channelConfig.setAutoRead
  private val queue = async.unboundedQueue[A](Strategy.Sequential)

  // we do the counting here as getting it from the async queue would introduce a latency
  private val queueSize = new AtomicLong(0)

  private val lowerBound: Int = Math.max(1, limit / 2)

  def enqueueOne(channelConfig: ChannelConfig, a: A): Task[Unit] = Task.delay(enableBPIfNecessary(channelConfig)).flatMap(b => queue.enqueueOne(a))

  def dequeue(channelConfig: ChannelConfig): Process[Task, A] = queue.dequeue.map { bv =>
    disableBPIfNecessary(channelConfig)
    bv
  }

  def close: Task[Unit] = queue.close

  def fail(rsn: Throwable): Task[Unit] = queue.fail(rsn)

  @inline
  private def increase: Long = queueSize.incrementAndGet

  @inline
  private def decrease: Long = queueSize.decrementAndGet

  /**
   * This method needs to be called whenever the netty-worker thread calls us with a received message and we insert it into to the queue.
   *
   * As a side-effect it may disable auto-read should the queue size go above the limit
   */
  private def enableBPIfNecessary(channelConfig: ChannelConfig): Unit =
    if (increase >= limit && channelConfig.isAutoRead) {
      channelConfig.setAutoRead(false)
    }


  /**
   * This method needs to be called when we dequeue a message form the message queue.
   *
   * As a side-effect it may enable autoread should the queue size go below half of the limit
   *
   */
  private def disableBPIfNecessary(channelConfig: ChannelConfig): Unit =
    if (decrease <= lowerBound && !channelConfig.isAutoRead) {
      channelConfig.setAutoRead(true)
    }

}

private[netty] final object BPAwareQueue {
  def apply[A](limit: Int) = new BPAwareQueue[A](limit)
}