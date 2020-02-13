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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import com.turn.ttorrent.bcodec.BeDecoder;
import com.turn.ttorrent.bcodec.BeEncoder;
import com.turn.ttorrent.bcodec.BeValue;
import com.turn.ttorrent.bcodec.InvalidBEncodingException;
import com.turn.ttorrent.common.Peer;
import com.turn.ttorrent.common.Torrent;
import com.turn.ttorrent.common.protocol.TrackerMessage.AnnounceResponseMessage;
import lombok.Getter;

/**
 * The announce response message from an HTTP tracker.
 *
 * @author mpetazzoni
 */
@Getter
public class HttpAnnounceResponseMessage extends HttpTrackerMessage
        implements AnnounceResponseMessage {

  private final int interval;

  private final int complete;

  private final int incomplete;

  private final List<Peer> peers;

  private HttpAnnounceResponseMessage(final ByteBuffer data,
                                      final int interval,
                                      final int complete,
                                      final int incomplete,
                                      final List<Peer> peers) {
    super(Type.ANNOUNCE_RESPONSE, data);
    this.interval = interval;
    this.complete = complete;
    this.incomplete = incomplete;
    this.peers = peers;
  }

  /**
   * Parse response message.
   *
   * @param data tracker message buffer
   *
   * @return message
   *
   * @throws IOException                unable to bdecode
   * @throws MessageValidationException invalid message
   */
  public static HttpAnnounceResponseMessage parse(final ByteBuffer data)
          throws IOException, MessageValidationException {
    final BeValue decoded = BeDecoder.bdecode(data);
    if (decoded == null) {
      throw new MessageValidationException("Could not decode tracker message (not B-encoded?)!");
    }

    final Map<String, BeValue> params = decoded.getMap();

    if (params.get("interval") == null) {
      throw new MessageValidationException("Tracker message missing mandatory field 'interval'!");
    }

    try {
      List<Peer> peers;

      try {
        // First attempt to decode a compact response, since we asked
        // for it.
        peers = toPeerList(params.get("peers").getBytes());
      } catch (final InvalidBEncodingException ibee) {
        // Fall back to peer list, non-compact response, in case the
        // tracker did not support compact responses.
        peers = toPeerList(params.get("peers").getList());
      }

      return new HttpAnnounceResponseMessage(
              data,
              params.get("interval").getInt(),
              params.get("complete") != null ? params.get("complete").getInt() : 0,
              params.get("incomplete") != null ? params.get("incomplete").getInt() : 0,
              peers);
    } catch (final InvalidBEncodingException ibee) {
      throw new MessageValidationException("Invalid response from tracker!", ibee);
    } catch (final UnknownHostException uhe) {
      throw new MessageValidationException("Invalid peer in tracker response!", uhe);
    }
  }

  /**
   * Build a peer list as a list of {@link Peer}s from the announce response's peer list (in
   * non-compact mode).
   *
   * @param peers The list of {@link BeValue}s dictionaries describing the peers from the announce
   *              response.
   *
   * @return A {@link List} of {@link Peer}s representing the peers' addresses. Peer IDs are lost,
   *         but they are not crucial.
   */
  private static List<Peer> toPeerList(final List<BeValue> peers) throws InvalidBEncodingException {
    final List<Peer> result = new LinkedList<>();

    for (BeValue peer : peers) {
      final Map<String, BeValue> peerInfo = peer.getMap();
      result.add(new Peer(
              peerInfo.get("ip").getString(Torrent.BYTE_ENCODING),
              peerInfo.get("port").getInt()));
    }

    return result;
  }

  /**
   * Build a peer list as a list of {@link Peer}s from the announce response's binary compact peer
   * list.
   *
   * @param data The bytes representing the compact peer list from the announce response.
   *
   * @return A {@link List} of {@link Peer}s representing the peers' addresses. Peer IDs are lost,
   *         but they are not crucial.
   */
  private static List<Peer> toPeerList(final byte[] data) throws InvalidBEncodingException,
                                                                 UnknownHostException {
    if (data.length % 6 != 0) {
      throw new InvalidBEncodingException("Invalid peers binary information string!");
    }

    final List<Peer> result = new LinkedList<>();
    final ByteBuffer peers = ByteBuffer.wrap(data);

    for (int i = 0; i < data.length / 6; i++) {
      final byte[] ipBytes = new byte[4];
      peers.get(ipBytes);
      final InetAddress ip = InetAddress.getByAddress(ipBytes);
      final int port = (0xFF & (int) peers.get()) << 8 | (0xFF & (int) peers.get());
      result.add(new Peer(new InetSocketAddress(ip, port)));
    }

    return result;
  }

  /**
   * Craft a compact announce response message.
   *
   * @param interval    Announce interval
   * @param minInterval announce minimum interval
   * @param trackerId   tracker id
   * @param complete    Number of complete
   * @param incomplete  Number of incomplete
   * @param peers       Peer information
   *
   * @return BeEncoded response
   *
   * @throws IOException                  Unable to encode response
   * @throws UnsupportedEncodingException Character encoding not supported
   */
  public static HttpAnnounceResponseMessage craft(final int interval,
                                                  final int minInterval,
                                                  final String trackerId,
                                                  final int complete,
                                                  final int incomplete,
                                                  final List<Peer> peers)
          throws IOException, UnsupportedEncodingException {
    final Map<String, BeValue> response = new HashMap<>();
    response.put("interval", new BeValue(interval));
    response.put("complete", new BeValue(complete));
    response.put("incomplete", new BeValue(incomplete));

    final ByteBuffer data = ByteBuffer.allocate(peers.size() * 6);
    for (Peer peer : peers) {
      final byte[] ip = peer.getRawIp();
      if (ip == null || ip.length != 4) {
        continue;
      }
      data.put(ip);
      data.putShort((short) peer.getPort());
    }
    response.put("peers", new BeValue(data.array()));

    return new HttpAnnounceResponseMessage(BeEncoder.bencode(response),
                                           interval,
                                           complete,
                                           incomplete,
                                           peers);
  }
}
