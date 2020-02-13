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
import lombok.Getter;

/**
 * The connection response message for the UDP tracker protocol.
 *
 * @author mpetazzoni
 */
@Getter
public class UdpConnectResponseMessage extends UdpTrackerMessage.UdpTrackerResponseMessage
        implements TrackerMessage.ConnectionResponseMessage {

  private static final int UDP_CONNECT_RESPONSE_MESSAGE_SIZE = 16;

  private final int actionId = Type.CONNECT_RESPONSE.getId();

  private final int transactionId;

  private final long connectionId;

  private UdpConnectResponseMessage(final ByteBuffer data,
                                    final int transactionId,
                                    final long connectionId) {
    super(Type.CONNECT_RESPONSE, data);
    this.transactionId = transactionId;
    this.connectionId = connectionId;
  }

  /**
   * Parse inbound connect response message.
   *
   * @param data tracker buffer
   *
   * @return HTTP connect response message
   *
   * @throws MessageValidationException Invalid message
   */
  public static UdpConnectResponseMessage parse(final ByteBuffer data)
          throws MessageValidationException {
    if (data.remaining() != UDP_CONNECT_RESPONSE_MESSAGE_SIZE) {
      throw new MessageValidationException(
              "Invalid connect response message size!");
    }

    if (data.getInt() != Type.CONNECT_RESPONSE.getId()) {
      throw new MessageValidationException(
              "Invalid action code for connection response!");
    }

    return new UdpConnectResponseMessage(data,
                                         data.getInt(), // transactionId
                                         data.getLong() // connectionId
    );
  }

  /**
   * Create connect response message.
   *
   * @param transactionId transaction id
   * @param connectionId  connection id
   *
   * @return message
   *
   */
  public static UdpConnectResponseMessage craft(final int transactionId,
                                                final long connectionId) {
    final ByteBuffer data = ByteBuffer.allocate(UDP_CONNECT_RESPONSE_MESSAGE_SIZE);
    data.putInt(Type.CONNECT_RESPONSE.getId());
    data.putInt(transactionId);
    data.putLong(connectionId);
    return new UdpConnectResponseMessage(data,
                                         transactionId,
                                         connectionId);
  }
}
