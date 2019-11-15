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

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;

import com.turn.ttorrent.common.Utils;
import com.turn.ttorrent.common.protocol.TrackerMessage;
import lombok.Getter;

/**
 * The announce request message for the UDP tracker protocol.
 *
 * @author mpetazzoni
 */
public class UdpAnnounceRequestMessage extends UdpTrackerMessage.UdpTrackerRequestMessage
        implements TrackerMessage.AnnounceRequestMessage {

  private static final int UDP_ANNOUNCE_REQUEST_MESSAGE_SIZE = 98;

  @Getter
  private final long connectionId;

  @Getter
  private final int actionId = Type.ANNOUNCE_REQUEST.getId();

  @Getter
  private final int transactionId;

  @Getter
  private final byte[] infoHash;

  @Getter
  private final byte[] peerId;

  @Getter
  private final long downloaded;

  @Getter
  private final long uploaded;

  @Getter
  private final long left;

  @Getter
  private final RequestEvent event;

  private final InetAddress ip;

  @Getter
  private final int numWant;

  @Getter
  private final int key;

  private final short port;

  private UdpAnnounceRequestMessage(final ByteBuffer data,
                                    final long connectionId,
                                    final int transactionId,
                                    final byte[] infoHash,
                                    final byte[] peerId,
                                    final long downloaded,
                                    final long uploaded,
                                    final long left,
                                    final RequestEvent event,
                                    final InetAddress ip,
                                    final int key,
                                    final int numWant,
                                    final short port) {
    super(Type.ANNOUNCE_REQUEST, data);
    this.connectionId = connectionId;
    this.transactionId = transactionId;
    this.infoHash = infoHash;
    this.peerId = peerId;
    this.downloaded = downloaded;
    this.uploaded = uploaded;
    this.left = left;
    this.event = event;
    this.ip = ip;
    this.key = key;
    this.numWant = numWant;
    this.port = port;
  }

  @Override
  public String getHexInfoHash() {
    return Utils.bytesToHex(this.infoHash);
  }

  @Override
  public String getHexPeerId() {
    return Utils.bytesToHex(this.peerId);
  }

  @Override
  public int getPort() {
    return this.port;
  }

  @Override
  public boolean getCompact() {
    return true;
  }

  @Override
  public boolean getNoPeerIds() {
    return true;
  }

  @Override
  public String getIp() {
    return this.ip.toString();
  }

  public static UdpAnnounceRequestMessage parse(final ByteBuffer data)
          throws MessageValidationException {
    if (data.remaining() != UDP_ANNOUNCE_REQUEST_MESSAGE_SIZE) {
      throw new MessageValidationException("Invalid announce request message size!");
    }

    final long connectionId = data.getLong();

    if (data.getInt() != Type.ANNOUNCE_REQUEST.getId()) {
      throw new MessageValidationException("Invalid action code for announce request!");
    }

    final int transactionId = data.getInt();
    final byte[] infoHash = new byte[20];
    data.get(infoHash);
    final byte[] peerId = new byte[20];
    data.get(peerId);
    final long downloaded = data.getLong();
    final long uploaded = data.getLong();
    final long left = data.getLong();

    final RequestEvent event = RequestEvent.getById(data.getInt());
    if (event == null) {
      throw new MessageValidationException("Invalid event type in announce request!");
    }

    InetAddress ip = null;
    try {
      final byte[] ipBytes = new byte[4];
      data.get(ipBytes);
      ip = InetAddress.getByAddress(ipBytes);
    } catch (final UnknownHostException uhe) {
      throw new MessageValidationException("Invalid IP address in announce request!");
    }

    final int key = data.getInt();
    final int numWant = data.getInt();
    final short port = data.getShort();

    return new UdpAnnounceRequestMessage(data,
                                         connectionId,
                                         transactionId,
                                         infoHash,
                                         peerId,
                                         downloaded,
                                         uploaded,
                                         left,
                                         event,
                                         ip,
                                         key,
                                         numWant,
                                         port);
  }

  public static UdpAnnounceRequestMessage craft(final long connectionId,
                                                final int transactionId,
                                                final byte[] infoHash,
                                                final byte[] peerId,
                                                final long downloaded,
                                                final long uploaded,
                                                final long left,
                                                final RequestEvent event,
                                                final InetAddress ip,
                                                final int key,
                                                final int numWant,
                                                final int port) {
    if (infoHash.length != 20 || peerId.length != 20) {
      throw new IllegalArgumentException();
    }

    if (!(ip instanceof Inet4Address)) {
      throw new IllegalArgumentException("Only IPv4 addresses are "
                                         + "supported by the UDP tracer protocol!");
    }

    final ByteBuffer data = ByteBuffer.allocate(UDP_ANNOUNCE_REQUEST_MESSAGE_SIZE);
    data.putLong(connectionId);
    data.putInt(Type.ANNOUNCE_REQUEST.getId());
    data.putInt(transactionId);
    data.put(infoHash);
    data.put(peerId);
    data.putLong(downloaded);
    data.putLong(left);
    data.putLong(uploaded);
    data.putInt(event.getId());
    data.put(ip.getAddress());
    data.putInt(key);
    data.putInt(numWant);
    data.putShort((short) port);
    return new UdpAnnounceRequestMessage(data,
                                         connectionId,
                                         transactionId,
                                         infoHash,
                                         peerId,
                                         downloaded,
                                         uploaded,
                                         left,
                                         event,
                                         ip,
                                         key,
                                         numWant,
                                         (short) port);
  }
}
