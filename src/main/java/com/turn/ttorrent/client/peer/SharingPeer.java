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

package com.turn.ttorrent.client.peer;

import java.io.IOException;
import java.io.Serializable;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import com.turn.ttorrent.client.Piece;
import com.turn.ttorrent.client.SharedTorrent;
import com.turn.ttorrent.common.Peer;
import com.turn.ttorrent.common.protocol.PeerMessage;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * A peer exchanging on a torrent with the BitTorrent client.
 *
 * <p>
 * A SharingPeer extends the base Peer class with all the data and logic needed by the BitTorrent
 * client to interact with a peer exchanging on the same torrent.
 * </p>
 *
 * <p>
 * Peers are defined by their peer ID, IP address and port number, just like base peers. Peers we
 * exchange with also contain four crucial attributes:
 * </p>
 *
 * <ul>
 * <li><code>choking</code>, which means we are choking this peer and we're not willing to send him
 * anything for now;</li>
 * <li><code>interesting</code>, which means we are interested in a piece this peer has;</li>
 * <li><code>choked</code>, if this peer is choking and won't send us anything right now;</li>
 * <li><code>interested</code>, if this peer is interested in something we have.</li>
 * </ul>
 *
 * <p>
 * Peers start choked and uninterested.
 * </p>
 *
 * @author mpetazzoni
 */
@Slf4j
public class SharingPeer extends Peer implements MessageListener {

  private static final int MAX_PIPELINED_REQUESTS = 5;

  @Getter
  private boolean choking;

  @Getter
  private boolean interesting;

  @Getter
  private boolean choked;

  @Getter
  private boolean interested;

  private SharedTorrent torrent;

  private BitSet availablePieces;

  /**
   * The currently requested piece, if any.
   */
  @Getter
  private Piece requestedPiece;

  private int lastRequestedOffset;

  private BlockingQueue<PeerMessage.RequestMessage> requests;

  private volatile boolean downloading;

  private PeerExchange exchange;

  @Getter
  private Rate download;

  @Getter
  private Rate upload;

  private final Set<PeerActivityListener> listeners;

  private final Object requestsLock;

  private final Object exchangeLock;

  /**
   * Create a new sharing peer on a given torrent.
   *
   * @param ip      The peer's IP address.
   * @param port    The peer's port.
   * @param peerId  The byte-encoded peer ID.
   * @param torrent The torrent this peer exchanges with us on.
   */
  public SharingPeer(final String ip,
                     final int port,
                     final ByteBuffer peerId,
                     final SharedTorrent torrent) {
    super(ip, port, peerId);

    this.torrent = torrent;
    this.listeners = new HashSet<>();
    this.availablePieces = new BitSet(this.torrent.getPieceCount());

    this.requestsLock = new Object();
    this.exchangeLock = new Object();

    this.reset();
    this.requestedPiece = null;
  }

  /**
   * Register a new peer activity listener.
   *
   * @param listener The activity listener that wants to receive events from this peer's activity.
   */
  public void register(final PeerActivityListener listener) {
    this.listeners.add(listener);
  }

  /**
   * Reset the peer state.
   *
   * <p>
   * Initially, peers are considered choked, choking, and neither interested nor interesting.
   * </p>
   */
  public synchronized void reset() {
    this.choking = true;
    this.interesting = false;
    this.choked = true;
    this.interested = false;

    this.exchange = null;

    this.requests = null;
    this.lastRequestedOffset = 0;
    this.downloading = false;
  }

  /**
   * Choke this peer.
   *
   * <p>
   * We don't want to upload to this peer anymore, so mark that we're choking from this peer.
   * </p>
   */
  public void choke() {
    if (!this.choking) {
      LOG.trace("Choking {}", this);
      this.send(PeerMessage.ChokeMessage.craft());
      this.choking = true;
    }
  }

  /**
   * Unchoke this peer.
   *
   * <p>
   * Mark that we are no longer choking from this peer and can resume uploading to it.
   * </p>
   */
  public void unchoke() {
    if (this.choking) {
      LOG.trace("Unchoking {}", this);
      this.send(PeerMessage.UnchokeMessage.craft());
      this.choking = false;
    }
  }

  public void interesting() {
    if (!this.interesting) {
      LOG.trace("Telling {} we're interested.", this);
      this.send(PeerMessage.InterestedMessage.craft());
      this.interesting = true;
    }
  }

  public void notInteresting() {
    if (this.interesting) {
      LOG.trace("Telling {} we're no longer interested.", this);
      this.send(PeerMessage.NotInterestedMessage.craft());
      this.interesting = false;
    }
  }

  /**
   * Returns the available pieces from this peer.
   *
   * @return A clone of the available pieces bit field from this peer.
   */
  public BitSet getAvailablePieces() {
    synchronized (this.availablePieces) {
      return (BitSet) this.availablePieces.clone();
    }
  }

  /**
   * Tells whether this peer is a seed.
   *
   * @return Returns <em>true</em> if the peer has all of the torrent's pieces available.
   */
  public synchronized boolean isSeed() {
    return this.torrent.getPieceCount() > 0
           && this.getAvailablePieces().cardinality() == this.torrent.getPieceCount();
  }

  /**
   * Bind a connected socket to this peer.
   *
   * <p>
   * This will create a new peer exchange with this peer using the given socket, and register the
   * peer as a message listener.
   * </p>
   *
   * @param channel The connected socket channel for this peer.
   *
   * @throws SocketException Unable to bind to socket
   */
  public synchronized void bind(final SocketChannel channel) throws SocketException {
    this.unbind(true);

    this.exchange = new PeerExchange(this, this.torrent, channel);
    this.exchange.register(this);
    this.exchange.start();

    this.download = new Rate();
    this.download.reset();

    this.upload = new Rate();
    this.upload.reset();
  }

  /**
   * Tells whether this peer as an active connection through a peer exchange.
   *
   * @return if peer is connected
   */
  public boolean isConnected() {
    synchronized (this.exchangeLock) {
      return this.exchange != null && this.exchange.isConnected();
    }
  }

  /**
   * Unbind and disconnect this peer.
   *
   * <p>
   * This terminates the eventually present and/or connected peer exchange with the peer and fires
   * the peer disconnected event to any peer activity listeners registered on this peer.
   * </p>
   *
   * @param force Force unbind without sending cancel requests.
   */
  public void unbind(final boolean force) {
    if (!force) {
      // Cancel all outgoing requests, and send a NOT_INTERESTED message to
      // the peer.
      this.cancelPendingRequests();
      this.send(PeerMessage.NotInterestedMessage.craft());
    }

    synchronized (this.exchangeLock) {
      if (this.exchange != null) {
        this.exchange.stop();
        this.exchange = null;
      }
    }

    this.firePeerDisconnected();
    this.requestedPiece = null;
  }

  /**
   * Send a message to the peer.
   *
   * <p>
   * Delivery of the message can only happen if the peer is connected.
   * </p>
   *
   * @param message The message to send to the remote peer through our peer exchange.
   */
  public void send(final PeerMessage message) throws IllegalStateException {
    if (this.isConnected()) {
      this.exchange.send(message);
    } else {
      LOG.warn("Attempting to send a message to non-connected peer {}!", this);
    }
  }

  /**
   * Download the given piece from this peer.
   *
   * <p>
   * Starts a block request queue and pre-fill it with MAX_PIPELINED_REQUESTS block requests.
   * </p>
   *
   * <p>
   * Further requests will be added, one by one, every time a block is returned.
   * </p>
   *
   * @param piece The piece chosen to be downloaded from this peer.
   */
  public synchronized void downloadPiece(final Piece piece) throws IllegalStateException {
    if (this.isDownloading()) {
      final IllegalStateException up = new IllegalStateException(
              "Trying to download a piece while previous "
              + "download not completed!");
      LOG.warn("What's going on? {}", up.getMessage(), up);
      throw up; // ah ah.
    }

    this.requests = new LinkedBlockingQueue<>(SharingPeer.MAX_PIPELINED_REQUESTS);
    this.requestedPiece = piece;
    this.lastRequestedOffset = 0;
    this.requestNextBlocks();
  }

  public boolean isDownloading() {
    return this.downloading;
  }

  /**
   * Request some more blocks from this peer.
   *
   * <p>
   * Re-fill the pipeline to get download the next blocks from the peer.
   * </p>
   */
  private void requestNextBlocks() {
    synchronized (this.requestsLock) {
      if (this.requests == null || this.requestedPiece == null) {
        // If we've been taken out of a piece download context it means our
        // outgoing requests have been cancelled. Don't enqueue new
        // requests until a proper piece download context is
        // re-established.
        return;
      }

      while (this.requests.remainingCapacity() > 0
             && this.lastRequestedOffset < this.requestedPiece.size()) {
        final PeerMessage.RequestMessage request = PeerMessage.RequestMessage
                .craft(this.requestedPiece.getIndex(),
                       this.lastRequestedOffset,
                       Math.min((int) (this.requestedPiece.size() - this.lastRequestedOffset),
                                PeerMessage.RequestMessage.DEFAULT_REQUEST_SIZE));
        this.requests.add(request);
        this.send(request);
        this.lastRequestedOffset += request.getLength();
      }

      this.downloading = this.requests.size() > 0;
    }
  }

  /**
   * Remove the REQUEST message from the request pipeline matching this PIECE message.
   *
   * <p>
   * Upon reception of a piece block with a PIECE message, remove the corresponding request from the
   * pipeline to make room for the next block requests.
   * </p>
   *
   * @param message The PIECE message received.
   */
  private void removeBlockRequest(final PeerMessage.PieceMessage message) {
    synchronized (this.requestsLock) {
      if (this.requests == null) {
        return;
      }

      for (PeerMessage.RequestMessage request : this.requests) {
        if (request.getPiece() == message.getPiece()
            && request.getOffset() == message.getOffset()) {
          this.requests.remove(request);
          break;
        }
      }

      this.downloading = this.requests.size() > 0;
    }
  }

  /**
   * Cancel all pending requests.
   *
   * <p>
   * This queues CANCEL messages for all the requests in the queue, and returns the list of requests
   * that were in the queue.
   * </p>
   *
   * <p>
   * If no request queue existed, or if it was empty, an empty set of request messages is returned.
   * </p>
   *
   * @return set of cancelled pending requests
   */
  public Set<PeerMessage.RequestMessage> cancelPendingRequests() {
    synchronized (this.requestsLock) {
      final Set<PeerMessage.RequestMessage> requests = new HashSet<>();

      if (this.requests != null) {
        for (PeerMessage.RequestMessage request : this.requests) {
          this.send(PeerMessage.CancelMessage.craft(request.getPiece(),
                                                    request.getOffset(), request.getLength()));
          requests.add(request);
        }
      }

      this.requests = null;
      this.downloading = false;
      return requests;
    }
  }

  private void handleRequest(final PeerMessage msg) {
    final PeerMessage.RequestMessage request = (PeerMessage.RequestMessage) msg;
    final Piece rp = this.torrent.getPiece(request.getPiece());

    // If we are choking from this peer and it still sends us
    // requests, it is a violation of the BitTorrent protocol.
    // Similarly, if the peer requests a piece we don't have, it
    // is a violation of the BitTorrent protocol. In these
    // situation, terminate the connection.
    if (this.isChoking() || !rp.isValid()) {
      LOG.warn("Peer {} violated protocol, terminating exchange.", this);
      this.unbind(true);
      return;
    }

    if (request.getLength() > PeerMessage.RequestMessage.MAX_REQUEST_SIZE) {
      LOG.warn("Peer {} requested a block too big, " + "terminating exchange.", this);
      this.unbind(true);
      return;
    }

    // At this point we agree to send the requested piece block to
    // the remote peer, so let's queue a message with that block
    try {
      final ByteBuffer block = rp.read(request.getOffset(), request.getLength());
      this.send(PeerMessage.PieceMessage.craft(request.getPiece(),
                                               request.getOffset(), block));
      this.upload.add(block.capacity());

      if (request.getOffset() + request.getLength() == rp.size()) {
        this.firePieceSent(rp);
      }
    } catch (final IOException ioe) {
      this.fireIoException(new IOException("Error while sending piece block request!", ioe));
    }
  }

  private void handlePiece(final PeerMessage msg) {
    // Record the incoming piece block.

    // Should we keep track of the requested pieces and act when we
    // get a piece we didn't ask for, or should we just stay
    // greedy?
    final PeerMessage.PieceMessage piece = (PeerMessage.PieceMessage) msg;
    final Piece p = this.torrent.getPiece(piece.getPiece());

    // Remove the corresponding request from the request queue to
    // make room for next block requests.
    this.removeBlockRequest(piece);
    this.download.add(piece.getBlock().capacity());

    try {
      synchronized (p) {
        if (p.isValid()) {
          this.requestedPiece = null;
          this.cancelPendingRequests();
          this.firePeerReady();
          LOG.debug("Discarding block for already completed " + p);
          return;
        }

        p.record(piece.getBlock(), piece.getOffset());

        // If the block offset equals the piece size and the block
        // length is 0, it means the piece has been entirely
        // downloaded. In this case, we have nothing to save, but
        // we should validate the piece.
        if (piece.getOffset() + piece.getBlock().capacity()
            == p.size()) {
          p.validate();
          this.firePieceCompleted(p);
          this.requestedPiece = null;
          this.firePeerReady();
        } else {
          this.requestNextBlocks();
        }
      }
    } catch (final IOException ioe) {
      this.fireIoException(new IOException(
              "Error while storing received piece block!", ioe));
    }
  }

  /**
   * Handle an incoming message from this peer.
   *
   * @param msg The incoming, parsed message.
   */
  @Override
  public synchronized void handleMessage(final PeerMessage msg) {
    switch (msg.getType()) {
      case KEEP_ALIVE:
        // Nothing to do, we're keeping the connection open anyways.
        break;
      case CHOKE:
        this.choked = true;
        this.firePeerChoked();
        this.cancelPendingRequests();
        break;
      case UNCHOKE:
        this.choked = false;
        LOG.trace("Peer {} is now accepting requests.", this);
        this.firePeerReady();
        break;
      case INTERESTED:
        this.interested = true;
        break;
      case NOT_INTERESTED:
        this.interested = false;
        break;
      case HAVE:
        // Record this peer has the given piece
        final PeerMessage.HaveMessage have = (PeerMessage.HaveMessage) msg;
        final Piece havePiece = this.torrent.getPiece(have.getPieceIndex());

        synchronized (this.availablePieces) {
          this.availablePieces.set(havePiece.getIndex());
          LOG.trace("Peer {} now has {} [{}/{}].", this,
                    havePiece,
                    this.availablePieces.cardinality(),
                    this.torrent.getPieceCount());
        }

        this.firePieceAvailabity(havePiece);
        break;
      case BITFIELD:
        // Augment the hasPiece bit field from this BITFIELD message
        final PeerMessage.BitfieldMessage bitfield = (PeerMessage.BitfieldMessage) msg;

        synchronized (this.availablePieces) {
          this.availablePieces.or(bitfield.getBitfield());
          LOG.trace("Recorded bitfield from {} with {} " + "pieces(s) [{}/{}].",
                    this,
                    bitfield.getBitfield().cardinality(),
                    this.availablePieces.cardinality(),
                    this.torrent.getPieceCount());
        }

        this.fireBitfieldAvailabity();
        break;
      case REQUEST:
        this.handleRequest(msg);
        break;
      case PIECE:
        this.handlePiece(msg);
        break;
      case CANCEL:
        // No need to support
        break;
      default:
        break;
    }
  }

  /**
   * Fire the peer choked event to all registered listeners.
   *
   * <p>
   * The event contains the peer that chocked.
   * </p>
   */
  private void firePeerChoked() {
    for (PeerActivityListener listener : this.listeners) {
      listener.handlePeerChoked(this);
    }
  }

  /**
   * Fire the peer ready event to all registered listeners.
   *
   * <p>
   * The event contains the peer that unchoked or became ready.
   * </p>
   */
  private void firePeerReady() {
    for (PeerActivityListener listener : this.listeners) {
      listener.handlePeerReady(this);
    }
  }

  /**
   * Fire the piece availability event to all registered listeners.
   *
   * <p>
   * The event contains the peer (this), and the piece that became available.
   * </p>
   */
  private void firePieceAvailabity(final Piece piece) {
    for (PeerActivityListener listener : this.listeners) {
      listener.handlePieceAvailability(this, piece);
    }
  }

  /**
   * Fire the bit field availability event to all registered listeners.<BR>
   * The event contains the peer (this), and the bit field of available pieces from this peer.
   */
  private void fireBitfieldAvailabity() {
    for (PeerActivityListener listener : this.listeners) {
      listener.handleBitfieldAvailability(this,
                                          this.getAvailablePieces());
    }
  }

  /**
   * Fire the piece sent event to all registered listeners.
   *
   * <p>
   * The event contains the peer (this), and the piece number that was sent to the peer.
   * </p>
   *
   * @param piece The completed piece.
   */
  private void firePieceSent(final Piece piece) {
    for (PeerActivityListener listener : this.listeners) {
      listener.handlePieceSent(this, piece);
    }
  }

  /**
   * Fire the piece completion event to all registered listeners.
   *
   * <p>
   * The event contains the peer (this), and the piece number that was completed.
   * </p>
   *
   * @param piece The completed piece.
   */
  private void firePieceCompleted(final Piece piece) throws IOException {
    for (PeerActivityListener listener : this.listeners) {
      listener.handlePieceCompleted(this, piece);
    }
  }

  /**
   * Fire the peer disconnected event to all registered listeners.
   *
   * <p>
   * The event contains the peer that disconnected (this).
   * </p>
   */
  private void firePeerDisconnected() {
    for (PeerActivityListener listener : this.listeners) {
      listener.handlePeerDisconnected(this);
    }
  }

  /**
   * Fire the IOException event to all registered listeners.
   *
   * <p>
   * The event contains the peer that triggered the problem, and the exception object.
   * </p>
   */
  private void fireIoException(final IOException ioe) {
    for (PeerActivityListener listener : this.listeners) {
      listener.handleIoException(this, ioe);
    }
  }

  /**
   * Download rate comparator.
   *
   * <p>
   * Compares sharing peers based on their current download rate.
   * </p>
   *
   * @author mpetazzoni
   * @see Rate#RATE_COMPARATOR
   */
  public static class DlRateComparator implements Comparator<SharingPeer>, Serializable {

    private static final long serialVersionUID = 96307229964730L;

    @Override
    public int compare(final SharingPeer a, final SharingPeer b) {
      return Rate.RATE_COMPARATOR.compare(a.getDownload(), b.getDownload());
    }
  }

  /**
   * Upload rate comparator.
   *
   * <p>
   * Compares sharing peers based on their current upload rate.
   * </p>
   *
   * @author mpetazzoni
   * @see Rate#RATE_COMPARATOR
   */
  public static class UlRateComparator implements Comparator<SharingPeer>, Serializable {

    private static final long serialVersionUID = 38794949747717L;

    @Override
    public int compare(final SharingPeer a, final SharingPeer b) {
      return Rate.RATE_COMPARATOR.compare(a.getUpload(), b.getUpload());
    }
  }

  @Override
  public String toString() {
    return new StringBuilder(super.toString())
            .append(" [")
            .append(this.choked ? "C" : "c")
            .append(this.interested ? "I" : "i")
            .append("|")
            .append(this.choking ? "C" : "c")
            .append(this.interesting ? "I" : "i")
            .append("|")
            .append(this.availablePieces.cardinality())
            .append("]")
            .toString();
  }
}
