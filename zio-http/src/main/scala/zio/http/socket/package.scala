package zio.http

package object socket {

  /**
   * A channel that allows websocket frames to be written to it.
   */
  type WebSocketChannel = ChannelT[WebSocketFrame]

  /**
   * A channel that allows websocket frames to be read and write to it.
   */
  type WebSocketChannelEvent = ChannelEvent[WebSocketFrame, WebSocketFrame]
}
