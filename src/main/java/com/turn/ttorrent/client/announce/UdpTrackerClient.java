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

package com.turn.ttorrent.client.announce;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.UnsupportedAddressTypeException;
import java.util.Calendar;
import java.util.Date;
import java.util.Random;

import com.turn.ttorrent.client.SharedTorrent;
import com.turn.ttorrent.common.Peer;
import com.turn.ttorrent.common.protocol.TrackerMessage;
import com.turn.ttorrent.common.protocol.TrackerMessage.AnnounceRequestMessage;
import com.turn.ttorrent.common.protocol.TrackerMessage.ConnectionResponseMessage;
import com.turn.ttorrent.common.protocol.TrackerMessage.ErrorMessage;
import com.turn.ttorrent.common.protocol.TrackerMessage.MessageValidationException;
import com.turn.ttorrent.common.protocol.udp.UdpAnnounceRequestMessage;
import com.turn.ttorrent.common.protocol.udp.UdpConnectRequestMessage;
import com.turn.ttorrent.common.protocol.udp.UdpConnectResponseMessage;
import com.turn.ttorrent.common.protocol.udp.UdpTrackerMessage;
import lombok.extern.slf4j.Slf4j;

/**
 * Announcer for UDP trackers.
 *
 * <p>
 * The UDP tracker protocol requires a two-step announce request/response exchange where the peer is
 * first required to establish a "connection" with the tracker by sending a connection request
 * message and retreiving a connection ID from the tracker to use in the following announce request
 * messages (valid for 2 minutes).
 * </p>
 *
 * <p>
 * It also contains a backing-off retry mechanism (on a 15*2^n seconds scheme), in which if the
 * announce request times-out for more than the connection ID validity period, another connection
 * request/response exchange must be made before attempting to retransmit the announce request.
 * </p>
 *
 * @author mpetazzoni
 */
@Slf4j
public class UdpTrackerClient extends TrackerClient {

  /**
   * Back-off timeout uses 15 * 2 ^ n formula.
   */
  private static final int UDP_BASE_TIMEOUT_SECONDS = 15;

  /**
   * We don't try more than 8 times (3840 seconds, as per the formula defined for the backing-off
   * timeout.
   *
   * @see #UDP_BASE_TIMEOUT_SECONDS
   */
  private static final int UDP_MAX_TRIES = 8;

  /**
   * For STOPPED announce event, we don't want to be bothered with waiting that long. We'll try once
   * and bail-out early.
   */
  private static final int UDP_MAX_TRIES_ON_STOPPED = 1;

  /**
   * Maximum UDP packet size expected, in bytes.<BR>
   * The biggest packet in the exchange is the announce response, which in 20 bytes + 6 bytes per
   * peer. Common numWant is 50, so 20 + 6 * 50 = 320. With headroom, we'll ask for 512 bytes.
   */
  private static final int UDP_PACKET_LENGTH = 512;

  private final InetSocketAddress address;

  private final Random random;

  private DatagramSocket socket;

  private Date connectionExpiration;

  private long connectionId;

  private int transactionId;

  private boolean stop;

  private enum State {
    CONNECT_REQUEST,
    ANNOUNCE_REQUEST;
  }

  protected UdpTrackerClient(final SharedTorrent torrent, final Peer peer, final URI tracker)
          throws UnknownHostException {
    super(torrent, peer, tracker);

    /**
     * The UDP announce request protocol only supports IPv4
     *
     * @see http://bittorrent.org/beps/bep_0015.html#ipv6
     */
    if (!(InetAddress.getByName(peer.getIp()) instanceof Inet4Address)) {
      throw new UnsupportedAddressTypeException();
    }

    this.address = new InetSocketAddress(tracker.getHost(), tracker.getPort());

    this.socket = null;
    this.random = new Random();
    this.connectionExpiration = null;
    this.stop = false;
  }

  @Override
  public void announce(final AnnounceRequestMessage.RequestEvent event,
                       final boolean inhibitEvents) throws AnnounceException {
    LOG.info("Announcing{} to tracker with {}U/{}D/{}L bytes...",
             this.formatAnnounceEvent(event),
             this.torrent.getUploaded(),
             this.torrent.getDownloaded(),
             this.torrent.getLeft());

    State state = State.CONNECT_REQUEST;
    final int maxAttempts = AnnounceRequestMessage.RequestEvent.STOPPED.equals(event)
                            ? UDP_MAX_TRIES_ON_STOPPED
                            : UDP_MAX_TRIES;
    int attempts = -1;

    try {
      this.socket = new DatagramSocket();
      this.socket.connect(this.address);

      while (++attempts <= maxAttempts) {
        // Transaction ID is randomized for each exchange.
        this.transactionId = this.random.nextInt();
        // Immediately decide if we can send the announce request directly or not.
        // For this, we need a valid, non-expired connection ID.
        if (this.connectionExpiration != null) {
          if (new Date().before(this.connectionExpiration)) {
            state = State.ANNOUNCE_REQUEST;
          } else {
            LOG.debug("Announce connection ID expired, reconnecting with tracker...");
          }
        }

        switch (state) {
          case CONNECT_REQUEST:
            this.send(UdpConnectRequestMessage.craft(this.transactionId).getData());
            try {
              this.handleTrackerConnectResponse(
                      UdpTrackerMessage.UdpTrackerResponseMessage.parse(this.recv(attempts)));
              attempts = -1;
            } catch (final SocketTimeoutException ste) {
              // Silently ignore the timeout and retry with a longer timeout,
              // unless announce stop was requested in which case we need to exit right away.
              if (this.stop) {
                return;
              }
            }
            break;

          case ANNOUNCE_REQUEST:
            this.send(this.buildAnnounceRequest(event).getData());
            try {
              this.handleTrackerAnnounceResponse(UdpTrackerMessage.UdpTrackerResponseMessage
                      .parse(this.recv(attempts)), inhibitEvents);
              // If we got here, we succesfully completed this
              // announce exchange and can simply return to exit the loop.
              return;
            } catch (final SocketTimeoutException ste) {
              // Silently ignore the timeout and retry with a longer timeout,
              // unless announce stop was requested in which case we need to exit right away.
              if (this.stop) {
                return;
              }
            }
            break;
          default:
            throw new IllegalStateException("Invalid announce state!");
        }
      }
      // When the maximum number of attempts was reached,
      // the announce really timed-out. We'll try again in the next announce loop.
      throw new AnnounceException("Timeout while announcing"
                                  + this.formatAnnounceEvent(event) + " to tracker!");
    } catch (final IOException ioe) {
      throw new AnnounceException(String.format("Error while announcing %s to tracker: %s",
                                                this.formatAnnounceEvent(event), ioe.getMessage()),
                                  ioe);
    } catch (final MessageValidationException mve) {

      throw new AnnounceException(String.format("Tracker message violates expected protocol (%s)",
                                                mve.getMessage()), mve);
    }
  }

  /**
   * Handles the tracker announce response message.
   *
   * <p>
   * Verifies the transaction ID of the message before passing it over to any registered
   * {@link AnnounceResponseListener}.
   * </p>
   *
   * @param message The message received from the tracker in response to the announce request.
   *
   * @throws AnnounceException Unable to announce
   */
  @Override
  protected void handleTrackerAnnounceResponse(final TrackerMessage message,
                                               final boolean inhibitEvents)
          throws AnnounceException {
    this.validateTrackerResponse(message);
    super.handleTrackerAnnounceResponse(message, inhibitEvents);
  }

  /**
   * Close this announce connection.
   */
  @Override
  protected void close() {
    this.stop = true;

    // Close the socket to force blocking operations to return.
    if (this.socket != null && !this.socket.isClosed()) {
      this.socket.close();
    }
  }

  private UdpAnnounceRequestMessage buildAnnounceRequest(
          final AnnounceRequestMessage.RequestEvent event) {
    return UdpAnnounceRequestMessage.craft(this.connectionId,
                                           this.transactionId,
                                           this.torrent.getInfoHash(),
                                           this.peer.getPeerId().array(),
                                           this.torrent.getDownloaded(),
                                           this.torrent.getUploaded(),
                                           this.torrent.getLeft(),
                                           event,
                                           this.peer.getAddress(),
                                           0,
                                           TrackerMessage.AnnounceRequestMessage.DEFAULT_NUM_WANT,
                                           this.peer.getPort());
  }

  /**
   * Validates an incoming tracker message.
   *
   * <p>
   * Verifies that the message is not an error message (throws an exception with the error message
   * if it is) and that the transaction ID matches the current one.
   * </p>
   *
   * @param message The incoming tracker message.
   */
  private void validateTrackerResponse(final TrackerMessage message) throws AnnounceException {
    if (message instanceof ErrorMessage) {
      throw new AnnounceException(((ErrorMessage) message).getReason());
    }

    if (message instanceof UdpTrackerMessage
        && ((UdpTrackerMessage) message).getTransactionId() != this.transactionId) {
      throw new AnnounceException("Invalid transaction ID!");
    }
  }

  /**
   * Handles the tracker connect response message.
   *
   * @param message The message received from the tracker in response to the connection request.
   */
  private void handleTrackerConnectResponse(final TrackerMessage message) throws AnnounceException {
    this.validateTrackerResponse(message);

    if (!(message instanceof ConnectionResponseMessage)) {
      throw new AnnounceException("Unexpected tracker message type "
                                  + message.getType().name() + "!");
    }

    final UdpConnectResponseMessage connectResponse = (UdpConnectResponseMessage) message;

    this.connectionId = connectResponse.getConnectionId();
    final Calendar now = Calendar.getInstance();
    now.add(Calendar.MINUTE, 1);
    this.connectionExpiration = now.getTime();
  }

  /**
   * Send a UDP packet to the tracker.
   *
   * @param data The {@link ByteBuffer} to send in a datagram packet to the tracker.
   */
  private void send(final ByteBuffer data) {
    try {
      this.socket.send(new DatagramPacket(data.array(),
                                          data.capacity(),
                                          this.address));
    } catch (final IOException ioe) {
      LOG.warn("Error sending datagram packet to tracker at {}: {}.",
               this.address, ioe.getMessage());
    }
  }

  /**
   * Receive a UDP packet from the tracker.
   *
   * @param attempt The attempt number, used to calculate the timeout for the receive operation.
   *
   * @return Returns a {@link ByteBuffer} containing the packet data.
   */
  private ByteBuffer recv(final int attempt) throws IOException,
                                                    SocketException,
                                                    SocketTimeoutException {
    final int timeout = UDP_BASE_TIMEOUT_SECONDS * (int) Math.pow(2, attempt);
    LOG.trace("Setting receive timeout to {}s for attempt {}...",
              timeout, attempt);
    this.socket.setSoTimeout(timeout * 1000);

    try {
      final DatagramPacket p = new DatagramPacket(new byte[UDP_PACKET_LENGTH], UDP_PACKET_LENGTH);
      this.socket.receive(p);
      return ByteBuffer.wrap(p.getData(), 0, p.getLength());
    } catch (final SocketTimeoutException ste) {
      throw ste;
    }
  }
}
