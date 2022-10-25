package zio.http

import io.netty.channel.{Channel => JChannel, ChannelFuture => JChannelFuture}
import zio.http.netty.NettyFutureExecutor
import zio.{Task, Trace, UIO, ZIO}
import zio.stacktracer.TracingImplicits.disableAutoTrace // scalafix:ok;

/**
 * An immutable and type-safe representation of one or more netty channels. `A`
 * represents the type of messages that can be written on the channel.
 */
final case class ChannelNetty[-A](
                                   private val channel: JChannel,
                                   private val convert: A => Any,
                                 ) extends ChannelT[A] {
  self =>

  private def foreach[S](await: Boolean)(run: JChannel => JChannelFuture)(implicit trace: Trace): Task[Unit] = {
    if (await) NettyFutureExecutor.executed(run(channel))
    else ZIO.attempt(run(channel): Unit)
  }

  /**
   * When set to `true` (default) it will automatically read messages from the
   * channel. When set to false, the channel will not read messages until `read`
   * is called.
   */
  override def autoRead(flag: Boolean)(implicit trace: Trace): UIO[Unit] = ZIO.succeed(channel.config.setAutoRead(flag): Unit)

  /**
   * Provides a way to wait for the channel to be closed.
   */
  override def awaitClose(implicit trace: Trace): UIO[Unit] = ZIO.async[Any, Nothing, Unit] { register =>
    channel.closeFuture().addListener((_: JChannelFuture) => register(ZIO.unit))
    ()
  }

  /**
   * Closes the channel. Pass true to await to wait for the channel to be
   * closed.
   */
  override def close(await: Boolean = false)(implicit trace: Trace): Task[Unit] = foreach(await) { _.close() }

  /**
   * Creates a new channel that can write a different type of message by using a
   * transformation function.
   */
  override def contramap[A1](f: A1 => A): ChannelNetty[A1] = copy(convert = convert.compose(f))

  /**
   * Flushes the pending write operations on the channel.
   */
  override def flush(implicit trace: Trace): Task[Unit] = ZIO.attempt(channel.flush(): Unit)

  /**
   * Returns the globally unique identifier of this channel.
   */
  override def id(implicit trace: Trace): String = channel.id().asLongText()

  /**
   * Returns `true` if auto-read is set to true.
   */
  override def isAutoRead(implicit trace: Trace): UIO[Boolean] = ZIO.succeed(channel.config.isAutoRead)

  /**
   * Schedules a read operation on the channel. This is not necessary if
   * auto-read is enabled.
   */
  override def read(implicit trace: Trace): UIO[Unit] = ZIO.succeed(channel.read(): Unit)

  /**
   * Schedules a write operation on the channel. The actual write only happens
   * after calling `flush`. Pass `true` to await the completion of the write
   * operation.
   */
  override def write(msg: A, await: Boolean = false)(implicit trace: Trace): Task[Unit] = foreach(await) {
    _.write(convert(msg))
  }

  /**
   * Writes and flushes the message on the channel. Pass `true` to await the
   * completion of the write operation.
   */
  override def writeAndFlush(msg: A, await: Boolean = false)(implicit trace: Trace): Task[Unit] = foreach(await) {
    _.writeAndFlush(convert(msg))
  }
}

object ChannelNetty {
  def make[A](channel: JChannel): ChannelNetty[A] = ChannelNetty(channel, identity)
}
