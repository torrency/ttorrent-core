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

package com.turn.ttorrent.common.protocol.http;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import com.turn.ttorrent.bcodec.BeDecoder;
import com.turn.ttorrent.bcodec.BeEncoder;
import com.turn.ttorrent.bcodec.BeValue;
import com.turn.ttorrent.bcodec.InvalidBEncodingException;
import com.turn.ttorrent.common.Torrent;
import com.turn.ttorrent.common.protocol.TrackerMessage.ErrorMessage;
import lombok.Getter;

/**
 * An error message from an HTTP tracker.
 *
 * @author mpetazzoni
 */
public class HttpTrackerErrorMessage extends HttpTrackerMessage implements ErrorMessage {

  @Getter
  private final String reason;

  private HttpTrackerErrorMessage(final ByteBuffer data, final String reason) {
    super(Type.ERROR, data);
    this.reason = reason;
  }

  public static HttpTrackerErrorMessage parse(final ByteBuffer data)
          throws IOException, MessageValidationException {
    final BeValue decoded = BeDecoder.bdecode(data);
    if (decoded == null) {
      throw new MessageValidationException(
              "Could not decode tracker message (not B-encoded?)!");
    }

    final Map<String, BeValue> params = decoded.getMap();

    try {
      return new HttpTrackerErrorMessage(data,
                                         params.get("failure reason")
                                                 .getString(Torrent.BYTE_ENCODING));
    } catch (final InvalidBEncodingException ibee) {
      throw new MessageValidationException("Invalid tracker error "
                                           + "message!", ibee);
    }
  }

  public static HttpTrackerErrorMessage craft(final ErrorMessage.FailureReason reason)
          throws IOException, MessageValidationException {
    return HttpTrackerErrorMessage.craft(reason.getMessage());
  }

  public static HttpTrackerErrorMessage craft(final String reason)
          throws IOException, MessageValidationException {
    final Map<String, BeValue> params = new HashMap<>();
    params.put("failure reason", new BeValue(reason, Torrent.BYTE_ENCODING));
    return new HttpTrackerErrorMessage(BeEncoder.bencode(params), reason);
  }
}
