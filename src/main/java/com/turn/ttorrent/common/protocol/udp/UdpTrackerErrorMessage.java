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

import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;

import com.turn.ttorrent.common.Torrent;
import com.turn.ttorrent.common.protocol.TrackerMessage;
import lombok.Getter;

/**
 * The error message for the UDP tracker protocol.
 *
 * @author mpetazzoni
 */
@Getter
public class UdpTrackerErrorMessage extends UdpTrackerMessage.UdpTrackerResponseMessage
        implements TrackerMessage.ErrorMessage {

  private static final int UDP_TRACKER_ERROR_MIN_MESSAGE_SIZE = 8;

  private final int actionId = Type.ERROR.getId();

  private final int transactionId;

  private final String reason;

  private UdpTrackerErrorMessage(final ByteBuffer data,
                                 final int transactionId,
                                 final String reason) {
    super(Type.ERROR, data);
    this.transactionId = transactionId;
    this.reason = reason;
  }

  /**
   * Parse inbound tracker error message.
   *
   * @param data tracker buffer
   *
   * @return HTTP tracker error message
   *
   * @throws MessageValidationException Invalid message
   */
  public static UdpTrackerErrorMessage parse(final ByteBuffer data)
          throws MessageValidationException {
    if (data.remaining() < UDP_TRACKER_ERROR_MIN_MESSAGE_SIZE) {
      throw new MessageValidationException(
              "Invalid tracker error message size!");
    }

    if (data.getInt() != Type.ERROR.getId()) {
      throw new MessageValidationException(
              "Invalid action code for tracker error!");
    }

    final int transactionId = data.getInt();
    final byte[] reasonBytes = new byte[data.remaining()];
    data.get(reasonBytes);

    try {
      return new UdpTrackerErrorMessage(data,
                                        transactionId,
                                        new String(reasonBytes, Torrent.BYTE_ENCODING)
      );
    } catch (final UnsupportedEncodingException uee) {
      throw new MessageValidationException("Could not decode error message!", uee);
    }
  }

  /**
   * Create tracker error message.
   *
   * @param transactionId transaction id
   * @param reason        error reason
   *
   * @return message
   *
   * @throws UnsupportedEncodingException encoding not supported
   */
  public static UdpTrackerErrorMessage craft(final int transactionId,
                                             final String reason)
          throws UnsupportedEncodingException {
    final byte[] reasonBytes = reason.getBytes(Torrent.BYTE_ENCODING);
    final ByteBuffer data = ByteBuffer
            .allocate(UDP_TRACKER_ERROR_MIN_MESSAGE_SIZE + reasonBytes.length);
    data.putInt(Type.ERROR.getId());
    data.putInt(transactionId);
    data.put(reasonBytes);
    return new UdpTrackerErrorMessage(data, transactionId, reason);
  }
}
