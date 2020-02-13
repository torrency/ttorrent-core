/*
 * Copyright (C) 2012 Turn, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.turn.ttorrent.common.protocol.udp;

import java.nio.ByteBuffer;

import com.turn.ttorrent.common.protocol.TrackerMessage;

/**
 * Base class for UDP tracker messages.
 *
 * @author mpetazzoni
 */
public abstract class UdpTrackerMessage extends TrackerMessage {

  private UdpTrackerMessage(final Type type, final ByteBuffer data) {
    super(type, data);
  }

  public abstract int getActionId();

  public abstract int getTransactionId();

  public abstract static class UdpTrackerRequestMessage extends UdpTrackerMessage {

    private static final int UDP_MIN_REQUEST_PACKET_SIZE = 16;

    protected UdpTrackerRequestMessage(final Type type, final ByteBuffer data) {
      super(type, data);
    }

    /**
     * Parse inbound response message.
     *
     * @param data tracker buffer
     *
     * @return HTTP request message
     *
     * @throws MessageValidationException Invalid message
     */
    public static UdpTrackerRequestMessage parse(final ByteBuffer data)
            throws MessageValidationException {
      if (data.remaining() < UDP_MIN_REQUEST_PACKET_SIZE) {
        throw new MessageValidationException("Invalid packet size!");
      }

      /**
       * UDP request packets always start with the connection ID (8 bytes), followed by the action
       * (4 bytes). Extract the action code accordingly.
       */
      data.mark();
      data.getLong();
      final int action = data.getInt();
      data.reset();

      if (action == Type.CONNECT_REQUEST.getId()) {
        return UdpConnectRequestMessage.parse(data);
      }
      if (action == Type.ANNOUNCE_REQUEST.getId()) {
        return UdpAnnounceRequestMessage.parse(data);
      }

      throw new MessageValidationException("Unknown UDP tracker request message!");
    }
  }

  public abstract static class UdpTrackerResponseMessage extends UdpTrackerMessage {

    private static final int UDP_MIN_RESPONSE_PACKET_SIZE = 8;

    protected UdpTrackerResponseMessage(final Type type, final ByteBuffer data) {
      super(type, data);
    }

    /**
     * Parse inbound response message.
     *
     * @param data tracker buffer
     *
     * @return HTTP request message
     *
     * @throws MessageValidationException Invalid message
     */
    public static UdpTrackerResponseMessage parse(final ByteBuffer data)
            throws MessageValidationException {
      if (data.remaining() < UDP_MIN_RESPONSE_PACKET_SIZE) {
        throw new MessageValidationException("Invalid packet size!");
      }

      /**
       * UDP response packets always start with the action (4 bytes), so we can extract it
       * immediately.
       */
      data.mark();
      final int action = data.getInt();
      data.reset();

      if (action == Type.CONNECT_RESPONSE.getId()) {
        return UdpConnectResponseMessage.parse(data);
      }
      if (action == Type.ANNOUNCE_RESPONSE.getId()) {
        return UdpAnnounceResponseMessage.parse(data);
      }
      if (action == Type.ERROR.getId()) {
        return UdpTrackerErrorMessage.parse(data);
      }

      throw new MessageValidationException("Unknown UDP tracker response message!");
    }
  }
}
