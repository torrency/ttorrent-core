/*
 * Copyright (C) 2011-2012 Turn, Inc.
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

package com.turn.ttorrent.common;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

import lombok.Data;

/**
 * A basic BitTorrent peer.
 *
 * <p>
 * This class is meant to be a common base for the tracker and client, which would presumably
 * subclass it to extend its functionality and fields.
 * </p>
 *
 * @author mpetazzoni
 */
@Data
public class Peer {

  private final InetSocketAddress address;

  /**
   * this peer's host identifier ("host:port").
   */
  private final String hostId;

  private ByteBuffer peerId;

  /**
   * The hexadecimal-encoded string representation of this peer's ID.
   */
  private String hexPeerId;

  /**
   * Instantiate a new peer.
   *
   * @param address The peer's address, with port.
   */
  public Peer(final InetSocketAddress address) {
    this(address, null);
  }

  /**
   * Instantiate a new peer.
   *
   * @param ip   The peer's IP address.
   * @param port The peer's port.
   */
  public Peer(final String ip, final int port) {
    this(new InetSocketAddress(ip, port), null);
  }

  /**
   * Instantiate a new peer.
   *
   * @param ip     The peer's IP address.
   * @param port   The peer's port.
   * @param peerId The byte-encoded peer ID.
   */
  public Peer(final String ip, final int port, final ByteBuffer peerId) {
    this(new InetSocketAddress(ip, port), peerId);
  }

  /**
   * Instantiate a new peer.
   *
   * @param address The peer's address, with port.
   * @param peerId  The byte-encoded peer ID.
   */
  public Peer(final InetSocketAddress address, final ByteBuffer peerId) {
    this.address = address;
    this.hostId = String.format("%s:%d",
                                this.address.getAddress(),
                                this.address.getPort());

    this.setPeerId(peerId);
  }

  /**
   * Tells whether this peer has a known peer ID yet or not.
   *
   * @return if we have peer
   */
  public boolean hasPeerId() {
    return this.peerId != null;
  }

  /**
   * Set a peer ID for this peer (usually during handshake).
   *
   * @param peerId The new peer ID for this peer.
   */
  public void setPeerId(final ByteBuffer peerId) {
    if (peerId != null) {
      this.peerId = peerId;
      this.hexPeerId = Utils.bytesToHex(peerId.array());
    } else {
      this.peerId = null;
      this.hexPeerId = null;
    }
  }

  /**
   * Get the shortened hexadecimal-encoded peer ID.
   *
   * @return get peer id in short hex format
   */
  public String getShortHexPeerId() {
    return String.format("..%s",
                         this.hexPeerId.substring(this.hexPeerId.length() - 6).toUpperCase());
  }

  /**
   * Returns this peer's IP address.
   *
   * @return get peer IP
   */
  public String getIp() {
    return this.address.getAddress().getHostAddress();
  }

  /**
   * Returns this peer's InetAddress.
   *
   * @return get peer
   */
  public InetAddress getAddress() {
    return this.address.getAddress();
  }

  /**
   * Returns this peer's port number.
   *
   * @return get peer host port
   */
  public int getPort() {
    return this.address.getPort();
  }

  /**
   * Returns a binary representation of the peer's IP.
   *
   * @return get raw IP of peer
   */
  public byte[] getRawIp() {
    return this.address.getAddress().getAddress();
  }

  /**
   * Returns a human-readable representation of this peer.
   *
   * @return peer in more readable format
   */
  @Override
  public String toString() {
    final StringBuilder s = new StringBuilder("peer://")
            .append(this.getIp()).append(":").append(this.getPort())
            .append("/");

    s.append(this.hasPeerId()
             ? s.append(this.hexPeerId.substring(this.hexPeerId.length() - 6))
             : "?"
    );

    if (this.getPort() < 10000) {
      s.append(" ");
    }

    return s.toString();
  }

  /**
   * Tells if two peers seem to look alike (i.e.they have the same IP, port and peer ID if they have
   * one).
   *
   * @param other the peer to compare
   *
   * @return true if 2 peers similar
   */
  public boolean looksLike(final Peer other) {
    if (other == null) {
      return false;
    }

    return this.hostId.equals(other.hostId)
           && (!this.hasPeerId() || this.hexPeerId.equals(other.hexPeerId));
  }
}
