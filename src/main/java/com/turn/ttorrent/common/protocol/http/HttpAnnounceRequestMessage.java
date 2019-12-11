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
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import com.turn.ttorrent.bcodec.BeDecoder;
import com.turn.ttorrent.bcodec.BeEncoder;
import com.turn.ttorrent.bcodec.BeValue;
import com.turn.ttorrent.bcodec.InvalidBEncodingException;
import com.turn.ttorrent.common.Peer;
import com.turn.ttorrent.common.Torrent;
import com.turn.ttorrent.common.Utils;
import com.turn.ttorrent.common.protocol.TrackerMessage.AnnounceRequestMessage;
import lombok.Getter;

/**
 * The announce request message for the HTTP tracker protocol.
 *
 * <p>
 * This class represents the announce request message in the HTTP tracker protocol. It doesn't add
 * any specific fields compared to the generic announce request message, but it provides the means
 * to parse such messages and craft them.
 * </p>
 *
 * @author mpetazzoni
 */
public class HttpAnnounceRequestMessage extends HttpTrackerMessage
        implements AnnounceRequestMessage {

  @Getter
  private final byte[] infoHash;

  private final Peer peer;

  @Getter
  private final long uploaded;

  @Getter
  private final long downloaded;

  @Getter
  private final long left;

  private final boolean compact;

  private final boolean noPeerId;

  @Getter
  private final RequestEvent event;

  @Getter
  private final int numWant;

  private HttpAnnounceRequestMessage(final ByteBuffer data,
                                     final byte[] infoHash,
                                     final Peer peer,
                                     final long uploaded,
                                     final long downloaded,
                                     final long left,
                                     final boolean compact,
                                     final boolean noPeerId,
                                     final RequestEvent event,
                                     final int numWant) {
    super(Type.ANNOUNCE_REQUEST, data);
    this.infoHash = infoHash;
    this.peer = peer;
    this.downloaded = downloaded;
    this.uploaded = uploaded;
    this.left = left;
    this.compact = compact;
    this.noPeerId = noPeerId;
    this.event = event;
    this.numWant = numWant;
  }

  @Override
  public String getHexInfoHash() {
    return Utils.bytesToHex(this.infoHash);
  }

  @Override
  public byte[] getPeerId() {
    return this.peer.getPeerId().array();
  }

  @Override
  public String getHexPeerId() {
    return this.peer.getHexPeerId();
  }

  @Override
  public int getPort() {
    return this.peer.getPort();
  }

  @Override
  public boolean getNoPeerIds() {
    return this.noPeerId;
  }

  @Override
  public String getIp() {
    return this.peer.getIp();
  }

  @Override
  public boolean getCompact() {
    return this.compact;
  }

  /**
   * Build the announce request URL for the given tracker announce URL.
   *
   * @param trackerAnnounceUrl The tracker's announce URL.
   *
   * @return The URL object representing the announce request URL.
   *
   * @throws UnsupportedEncodingException Character encoding not supported
   * @throws MalformedURLException        URL invalid
   */
  public URL buildAnnounceUrl(final URL trackerAnnounceUrl) throws UnsupportedEncodingException,
                                                                   MalformedURLException {
    final String base = trackerAnnounceUrl.toString();
    final StringBuilder url = new StringBuilder(base);
    url.append(base.contains("?") ? "&" : "?")
            .append("info_hash=")
            .append(URLEncoder.encode(new String(this.getInfoHash(), Torrent.BYTE_ENCODING),
                                      Torrent.BYTE_ENCODING))
            .append("&peer_id=")
            .append(URLEncoder.encode(new String(this.getPeerId(), Torrent.BYTE_ENCODING),
                                      Torrent.BYTE_ENCODING))
            .append("&port=").append(this.getPort())
            .append("&uploaded=").append(this.getUploaded())
            .append("&downloaded=").append(this.getDownloaded())
            .append("&left=").append(this.getLeft())
            .append("&compact=").append(this.getCompact() ? 1 : 0)
            .append("&no_peer_id=").append(this.getNoPeerIds() ? 1 : 0);

    if (this.getEvent() != null
        && !RequestEvent.NONE.equals(this.getEvent())) {
      url.append("&event=").append(this.getEvent().getEventName());
    }

    if (this.getIp() != null) {
      url.append("&ip=").append(this.getIp());
    }

    return new URL(url.toString());
  }

  public static HttpAnnounceRequestMessage parse(final ByteBuffer data)
          throws IOException, MessageValidationException {
    final BeValue decoded = BeDecoder.bdecode(data);
    if (decoded == null) {
      throw new MessageValidationException("Could not decode tracker message (not B-encoded?)!");
    }

    final Map<String, BeValue> params = decoded.getMap();

    if (!params.containsKey("info_hash")) {
      throw new MessageValidationException(ErrorMessage.FailureReason.MISSING_HASH.getMessage());
    }
    if (!params.containsKey("peer_id")) {
      throw new MessageValidationException(ErrorMessage.FailureReason.MISSING_PEER_ID.getMessage());
    }
    if (!params.containsKey("port")) {
      throw new MessageValidationException(ErrorMessage.FailureReason.MISSING_PORT.getMessage());
    }

    try {
      final byte[] infoHash = params.get("info_hash").getBytes();
      final byte[] peerId = params.get("peer_id").getBytes();
      final int port = params.get("port").getInt();

      // Default 'uploaded' and 'downloaded' to 0 if the client does not provide it
      long uploaded = 0;
      if (params.containsKey("uploaded")) {
        uploaded = params.get("uploaded").getLong();
      }

      long downloaded = 0;
      if (params.containsKey("downloaded")) {
        downloaded = params.get("downloaded").getLong();
      }

      // Default 'left' to -1 to avoid peers entering the COMPLETED state when not provided
      long left = -1;
      if (params.containsKey("left")) {
        left = params.get("left").getLong();
      }

      boolean compact = false;
      if (params.containsKey("compact")) {
        compact = params.get("compact").getInt() == 1;
      }

      boolean noPeerId = false;
      if (params.containsKey("no_peer_id")) {
        noPeerId = params.get("no_peer_id").getInt() == 1;
      }

      int numWant = AnnounceRequestMessage.DEFAULT_NUM_WANT;
      if (params.containsKey("numwant")) {
        numWant = params.get("numwant").getInt();
      }

      String ip = null;
      if (params.containsKey("ip")) {
        ip = params.get("ip").getString(Torrent.BYTE_ENCODING);
      }

      RequestEvent event = RequestEvent.NONE;
      if (params.containsKey("event")) {
        event = RequestEvent.getByName(params.get("event")
                .getString(Torrent.BYTE_ENCODING));
      }

      return new HttpAnnounceRequestMessage(data, infoHash,
                                            new Peer(ip, port, ByteBuffer.wrap(peerId)),
                                            uploaded, downloaded, left, compact, noPeerId,
                                            event, numWant);
    } catch (final InvalidBEncodingException ibee) {
      throw new MessageValidationException("Invalid HTTP tracker request!", ibee);
    }
  }

  private static Map<String, BeValue> assemble(final byte[] infoHash,
                                               final byte[] peerId,
                                               final int port,
                                               final long uploaded,
                                               final long downloaded,
                                               final long left,
                                               final boolean compact,
                                               final boolean noPeerId,
                                               final RequestEvent event,
                                               final String ip,
                                               final int numWant)
          throws UnsupportedEncodingException {
    final Map<String, BeValue> params = new HashMap<>();
    params.put("info_hash", new BeValue(infoHash));
    params.put("peer_id", new BeValue(peerId));
    params.put("port", new BeValue(port));
    params.put("uploaded", new BeValue(uploaded));
    params.put("downloaded", new BeValue(downloaded));
    params.put("left", new BeValue(left));
    params.put("compact", new BeValue(compact ? 1 : 0));
    params.put("no_peer_id", new BeValue(noPeerId ? 1 : 0));

    if (event != null) {
      params.put("event", new BeValue(event.getEventName(), Torrent.BYTE_ENCODING));
    }

    if (ip != null) {
      params.put("ip", new BeValue(ip, Torrent.BYTE_ENCODING));
    }

    if (numWant != AnnounceRequestMessage.DEFAULT_NUM_WANT) {
      params.put("numwant", new BeValue(numWant));
    }
    return params;
  }

  public static HttpAnnounceRequestMessage craft(final byte[] infoHash,
                                                 final byte[] peerId,
                                                 final int port,
                                                 final long uploaded,
                                                 final long downloaded,
                                                 final long left,
                                                 final boolean compact,
                                                 final boolean noPeerId,
                                                 final RequestEvent event,
                                                 final String ip,
                                                 final int numWant)
          throws IOException, MessageValidationException, UnsupportedEncodingException {
    final Map<String, BeValue> params = assemble(infoHash, peerId, port,
                                                 uploaded, downloaded, left,
                                                 compact, noPeerId, event, ip, numWant);
    return new HttpAnnounceRequestMessage(BeEncoder.bencode(params),
                                          infoHash, new Peer(ip, port, ByteBuffer.wrap(peerId)),
                                          uploaded, downloaded, left,
                                          compact, noPeerId, event, numWant);
  }
}
