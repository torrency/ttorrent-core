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

package com.turn.ttorrent.client;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Observable;
import java.util.Random;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.turn.ttorrent.client.announce.Announce;
import com.turn.ttorrent.client.announce.AnnounceException;
import com.turn.ttorrent.client.announce.AnnounceResponseListener;
import com.turn.ttorrent.client.peer.PeerActivityListener;
import com.turn.ttorrent.client.peer.SharingPeer;
import com.turn.ttorrent.common.Peer;
import com.turn.ttorrent.common.Torrent;
import com.turn.ttorrent.common.protocol.PeerMessage;
import com.turn.ttorrent.common.protocol.TrackerMessage;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * A pure-java BitTorrent client.
 *
 * <p>
 * A BitTorrent client in its bare essence shares a given torrent. If the torrent is not complete
 * locally, it will continue to download it. If or after the torrent is complete, the client may
 * eventually continue to seed it for other clients.
 * </p>
 *
 * <p>
 * This BitTorrent client implementation is made to be simple to embed and simple to use. First,
 * initialize a ShareTorrent object from a torrent meta-info source (either a file or a byte array,
 * see com.turn.ttorrent.SharedTorrent for how to create a SharedTorrent object). Then, instantiate
 * your Client object with this SharedTorrent and call one of {@link #download} to simply download
 * the torrent, or {@link #share} to download and continue seeding for the given amount of time
 * after the download completes.
 * </p>
 *
 * @author mpetazzoni
 */
@Slf4j
public class Client extends Observable implements Runnable,
                                                  AnnounceResponseListener,
                                                  IncomingConnectionListener,
                                                  PeerActivityListener {

  /**
   * Peers unchoking frequency, in seconds. Current BitTorrent specification recommends 10 seconds
   * to avoid choking fibrilation.
   */
  private static final int UNCHOKING_FREQUENCY = 3;

  /**
   * Optimistic unchokes are done every 2 loop iterations, i.e. every 2*UNCHOKING_FREQUENCY seconds.
   */
  private static final int OPTIMISTIC_UNCHOKE_ITERATIONS = 3;

  private static final int RATE_COMPUTATION_ITERATIONS = 2;

  private static final int MAX_DOWNLOADERS_UNCHOKE = 4;

  public enum ClientState {
    WAITING,
    VALIDATING,
    SHARING,
    SEEDING,
    ERROR,
    DONE
  }

  private static final String BITTORRENT_ID_PREFIX = "-TO0042-";

  /**
   * The torrent this client is exchanging on.
   */
  @Getter
  private final SharedTorrent torrent;

  /**
   * The current state of this BitTorrent client.
   */
  @Getter
  private ClientState state;

  private final Peer self;

  private Thread thread;

  private boolean stop;

  private long seed;

  private final ConnectionHandler service;

  private final Announce announce;

  private final ConcurrentMap<String, SharingPeer> peers;

  private final ConcurrentMap<String, SharingPeer> connected;

  private final Random random;

  /**
   * Initialize the BitTorrent client.
   *
   * @param address The address to bind to.
   * @param torrent The torrent to download and share.
   *
   * @throws UnknownHostException Host not known
   * @throws IOException          unable to create connection handler
   */
  public Client(final InetAddress address, final SharedTorrent torrent)
          throws UnknownHostException, IOException {
    this.torrent = torrent;
    this.state = ClientState.WAITING;

    final String id = Client.BITTORRENT_ID_PREFIX + UUID.randomUUID().toString().split("-")[4];

    // Initialize the incoming connection handler and register ourselves to it.
    this.service = new ConnectionHandler(this.torrent, id, address);
    this.service.register(this);

    this.self = new Peer(this.service.getSocketAddress().getAddress().getHostAddress(),
                         this.service.getSocketAddress().getPort(),
                         ByteBuffer.wrap(id.getBytes(Torrent.BYTE_ENCODING)));

    // Initialize the announce request thread, and register ourselves to it as well.
    this.announce = new Announce(this.torrent, this.self);
    this.announce.register(this);

    LOG.info("BitTorrent client [{}] for {} started and " + "listening at {}:{}...",
             this.self.getShortHexPeerId(),
             this.torrent.getName(),
             this.self.getIp(),
             this.self.getPort());

    this.peers = new ConcurrentHashMap<>();
    this.connected = new ConcurrentHashMap<>();
    this.random = new Random(System.currentTimeMillis());
  }

  /**
   * Set the maximum download rate (in kB/second) for this torrent. A setting of &lt;= 0.0 disables
   * rate limiting.
   *
   * @param rate The maximum download rate
   */
  public void setMaxDownloadRate(final double rate) {
    this.torrent.setMaxDownloadRate(rate);
  }

  /**
   * Set the maximum upload rate (in kB/second) for this torrent. A setting of &lt;= 0.0 disables
   * rate limiting.
   *
   * @param rate The maximum upload rate
   */
  public void setMaxUploadRate(final double rate) {
    this.torrent.setMaxUploadRate(rate);
  }

  /**
   * Get this client's peer specification.
   *
   * @return self peer spec
   */
  public Peer getPeerSpec() {
    return this.self;
  }

  /**
   * Returns the set of known peers.
   *
   * @return peers in set
   */
  public Set<SharingPeer> getPeers() {
    return new HashSet<>(this.peers.values());
  }

  /**
   * Change this client's state and notify its observers.
   *
   * <p>
   * If the state has changed, this client's observers will be notified.
   * </p>
   *
   * @param state The new client state.
   */
  private synchronized void setState(final ClientState state) {
    if (this.state != state) {
      this.setChanged();
    }
    this.state = state;
    this.notifyObservers(this.state);
  }

  /**
   * Download the torrent without seeding after completion.
   */
  public void download() {
    this.share(0);
  }

  /**
   * Download and share this client's torrent until interrupted.
   */
  public void share() {
    this.share(-1);
  }

  /**
   * Download and share this client's torrent.
   *
   * @param seed Seed time in seconds after the download is complete. Pass <code>0</code> to
   *             immediately stop after downloading.
   */
  public synchronized void share(final int seed) {
    this.seed = seed;
    this.stop = false;

    if (this.thread == null || !this.thread.isAlive()) {
      this.thread = new Thread(this);
      this.thread.setName(String.format("bt-client(%s)", this.self.getShortHexPeerId()));
      this.thread.start();
    }
  }

  /**
   * Immediately but gracefully stop this client.
   */
  public void stop() {
    this.stop(true);
  }

  /**
   * Immediately but gracefully stop this client.
   *
   * @param wait Whether to wait for the client execution thread to complete or not. This allows for
   *             the client's state to be settled down in one of the DONE or ERROR states when this
   *             method returns.
   */
  public void stop(final boolean wait) {
    this.stop = true;

    if (this.thread != null && this.thread.isAlive()) {
      this.thread.interrupt();
      if (wait) {
        this.waitForCompletion();
      }
    }

    this.thread = null;
  }

  /**
   * Wait for downloading (and seeding, if requested) to complete.
   */
  public void waitForCompletion() {
    if (this.thread != null && this.thread.isAlive()) {
      try {
        this.thread.join();
      } catch (final InterruptedException ie) {
        LOG.error(ie.getMessage(), ie);
      }
    }
  }

  /**
   * Tells whether we are a seed for the torrent we're sharing.
   *
   * @return true if able to seed
   */
  public boolean isSeed() {
    return this.torrent.isComplete();
  }

  /**
   * Main client loop.
   *
   * <p>
   * The main client download loop is very simple: it starts the announce request thread, the
   * incoming connection handler service, and loops unchoking peers every UNCHOKING_FREQUENCY
   * seconds until told to stop. Every OPTIMISTIC_UNCHOKE_ITERATIONS, an optimistic unchoke will be
   * attempted to try out other peers.
   * </p>
   *
   * <p>
   * Once done, it stops the announce and connection services, and returns.
   * </p>
   */
  @Override
  public void run() {
    // First, analyze the torrent's local data.
    try {
      this.setState(ClientState.VALIDATING);
      this.torrent.init();
    } catch (final IOException ioe) {
      LOG.warn("Error while initializing torrent data: {}!", ioe.getMessage(), ioe);
    } catch (final InterruptedException ie) {
      LOG.warn("Client was interrupted during initialization. " + "Aborting right away.");
    } finally {
      if (!this.torrent.isInitialized()) {
        try {
          this.service.close();
        } catch (final IOException ioe) {
          LOG.warn("Error while releasing bound channel: {}!", ioe.getMessage(), ioe);
        }
        this.setState(ClientState.ERROR);
        this.torrent.close();
      }
    }
    if (this.torrent.isComplete()) { // Initial completion test
      this.seed();
    } else {
      this.setState(ClientState.SHARING);
    }

    // Detect early stop
    if (this.stop) {
      LOG.info("Download is complete and no seeding was requested.");
      this.finish();
      return;
    }
    this.announce.start();
    this.service.start();
    int optimisticIterations = 0;
    int rateComputationIterations = 0;

    while (!this.stop) {
      optimisticIterations = optimisticIterations == 0
                             ? Client.OPTIMISTIC_UNCHOKE_ITERATIONS
                             : optimisticIterations - 1;
      rateComputationIterations = rateComputationIterations == 0
                                  ? Client.RATE_COMPUTATION_ITERATIONS
                                  : rateComputationIterations - 1;

      try {
        this.unchokePeers(optimisticIterations == 0);
        this.info();
        if (rateComputationIterations == 0) {
          this.resetPeerRates();
        }
      } catch (final Exception e) {
        LOG.error("An exception occurred during the BitTorrent client main loop execution!", e);
      }
      try {
        Thread.sleep(Client.UNCHOKING_FREQUENCY * 1000);
      } catch (final InterruptedException ie) {
        LOG.trace("BitTorrent main loop interrupted.");
      }
    }

    LOG.debug("Stopping BitTorrent client connection service and announce threads...");

    this.service.stop();
    try {
      this.service.close();
    } catch (final IOException ioe) {
      LOG.warn("Error while releasing bound channel: {}!", ioe.getMessage(), ioe);
    }
    this.announce.stop();
    // Close all peer connections
    LOG.debug("Closing all remaining peer connections...");
    for (SharingPeer peer : this.connected.values()) {
      peer.unbind(true);
    }
    this.finish();
  }

  /**
   * Close torrent and set final client state before signing off.
   */
  private void finish() {
    this.torrent.close();

    // Determine final state
    if (this.torrent.isFinished()) {
      this.setState(ClientState.DONE);
    } else {
      this.setState(ClientState.ERROR);
    }

    LOG.info("BitTorrent client signing off.");
  }

  /**
   * Display information about the BitTorrent client state.
   *
   * <p>
   * This emits an information line in the log about this client's state. It includes the number of
   * choked peers, number of connected peers, number of known peers, information about the torrent
   * availability and completion and current transmission rates.
   * </p>
   */
  public synchronized void info() {
    float dl = 0;
    float ul = 0;
    for (SharingPeer peer : this.connected.values()) {
      dl += peer.getDownload().get();
      ul += peer.getUpload().get();
    }

    LOG.info("{} {}/{} pieces ({}%) [{}/{}] with {}/{} peers at {}/{} kB/s.", new Object[]{
      this.getState().name(),
      this.torrent.getCompletedPieces().cardinality(),
      this.torrent.getPieceCount(),
      String.format("%.2f", this.torrent.getCompletion()),
      this.torrent.getAvailablePieces().cardinality(),
      this.torrent.getRequestedPieces().cardinality(),
      this.connected.size(),
      this.peers.size(),
      String.format("%.2f", dl / 1024.0),
      String.format("%.2f", ul / 1024.0),});
    for (SharingPeer peer : this.connected.values()) {
      final Piece piece = peer.getRequestedPiece();
      LOG.debug("  | {} {}", peer,
                piece != null
                ? "(downloading " + piece + ")"
                : ""
      );
    }
  }

  /**
   * Reset peers download and upload rates.
   *
   * <p>
   * This method is called every RATE_COMPUTATION_ITERATIONS to reset the download and upload rates
   * of all peers. This contributes to making the download and upload rate computations rolling
   * averages every UNCHOKING_FREQUENCY * RATE_COMPUTATION_ITERATIONS seconds (usually 20 seconds).
   * </p>
   */
  private synchronized void resetPeerRates() {
    for (SharingPeer peer : this.connected.values()) {
      peer.getDownload().reset();
      peer.getUpload().reset();
    }
  }

  /**
   * Retrieve a SharingPeer object from the given peer specification.
   *
   * <p>
   * This function tries to retrieve an existing peer object based on the provided peer
   * specification or otherwise instantiates a new one and adds it to our peer repository.
   * </p>
   *
   * @param search The {@link Peer} specification.
   */
  private SharingPeer getOrCreatePeer(final Peer search) {
    SharingPeer peer;

    synchronized (this.peers) {
      LOG.trace("Searching for {}...", search);
      if (search.hasPeerId()) {
        peer = this.peers.get(search.getHexPeerId());
        if (peer != null) {
          LOG.trace("Found peer (by peer ID): {}.", peer);
          this.peers.put(peer.getHostId(), peer);
          this.peers.put(search.getHostId(), peer);
          return peer;
        }
      }

      peer = this.peers.get(search.getHostId());
      if (peer != null) {
        if (search.hasPeerId()) {
          LOG.trace("Recording peer ID {} for {}.", search.getHexPeerId(), peer);
          peer.setPeerId(search.getPeerId());
          this.peers.put(search.getHexPeerId(), peer);
        }

        LOG.debug("Found peer (by host ID): {}.", peer);
        return peer;
      }

      peer = new SharingPeer(search.getIp(), search.getPort(), search.getPeerId(), this.torrent);
      LOG.trace("Created new peer: {}.", peer);

      this.peers.put(peer.getHostId(), peer);
      if (peer.hasPeerId()) {
        this.peers.put(peer.getHexPeerId(), peer);
      }

      return peer;
    }
  }

  /**
   * Retrieve a peer comparator.
   *
   * <p>
   * Returns a peer comparator based on either the download rate or the upload rate of each peer
   * depending on our state. While sharing, we rely on the download rate we get from each peer. When
   * our download is complete and we're only seeding, we use the upload rate instead.
   * </p>
   *
   * @return A SharingPeer comparator that can be used to sort peers based on the download or upload
   *         rate we get from them.
   */
  private Comparator<SharingPeer> getPeerRateComparator() {
    if (ClientState.SHARING.equals(this.state)) {
      return new SharingPeer.DlRateComparator();
    } else if (ClientState.SEEDING.equals(this.state)) {
      return new SharingPeer.UlRateComparator();
    } else {
      throw new IllegalStateException("Client is neither sharing nor "
                                      + "seeding, we shouldn't be comparing peers at this point.");
    }
  }

  /**
   * Unchoke connected peers.
   *
   * <p>
   * This is one of the "clever" places of the BitTorrent client. Every
   * OPTIMISTIC_UNCHOKING_FREQUENCY seconds, we decide which peers should be unchocked and
   * authorized to grab pieces from us.
   * </p>
   *
   * <p>
   * Reciprocation (tit-for-tat) and upload capping is implemented here by carefully choosing which
   * peers we unchoke, and which peers we choke.
   * </p>
   *
   * <p>
   * The four peers with the best download rate and are interested in us get unchoked. This
   * maximizes our download rate as we'll be able to get data from there four "best" peers quickly,
   * while allowing these peers to download from us and thus reciprocate their generosity.
   * </p>
   *
   * <p>
   * Peers that have a better download rate than these four downloaders but are not interested get
   * unchoked too, we want to be able to download from them to get more data more quickly. If one
   * becomes interested, it takes a downloader's place as one of the four top downloaders (i.e. we
   * choke the downloader with the worst upload rate).
   * </p>
   *
   * @param optimistic Whether to perform an optimistic unchoke as well.
   */
  private synchronized void unchokePeers(final boolean optimistic) {
    // Build a set of all connected peers, we don't care about peers we're
    // not connected to.
    final TreeSet<SharingPeer> bound = new TreeSet<>(this.getPeerRateComparator());
    bound.addAll(this.connected.values());

    if (bound.isEmpty()) {
      LOG.trace("No connected peers, skipping unchoking.");
      return;
    } else {
      LOG.trace("Running unchokePeers() on {} connected peers.", bound.size());
    }

    int downloaders = 0;
    final Set<SharingPeer> choked = new HashSet<>();

    // We're interested in the top downloaders first, so use a descending
    // set.
    for (SharingPeer peer : bound.descendingSet()) {
      if (downloaders < Client.MAX_DOWNLOADERS_UNCHOKE) {
        // Unchoke up to MAX_DOWNLOADERS_UNCHOKE interested peers
        if (peer.isChoking()) {
          if (peer.isInterested()) {
            downloaders++;
          }

          peer.unchoke();
        }
      } else {
        // Choke everybody else
        choked.add(peer);
      }
    }

    // Actually choke all chosen peers (if any), except the eventual
    // optimistic unchoke.
    if (!choked.isEmpty()) {
      final SharingPeer randomPeer = choked.toArray(
              new SharingPeer[0])[this.random.nextInt(choked.size())];

      for (SharingPeer peer : choked) {
        if (optimistic && peer == randomPeer) {
          LOG.debug("Optimistic unchoke of {}.", peer);
          peer.unchoke();
          continue;
        }

        peer.choke();
      }
    }
  }

  /**
   * AnnounceResponseListener handler(s).
   */
  /**
   * Handle an announce response event.
   *
   * @param interval   The announce interval requested by the tracker.
   * @param complete   The number of seeders on this torrent.
   * @param incomplete The number of leechers on this torrent.
   */
  @Override
  public void handleAnnounceResponse(final int interval, final int complete, final int incomplete) {
    this.announce.setInterval(interval);
  }

  /**
   * Handle the discovery of new peers.
   *
   * @param peers The list of peers discovered (from the announce response or any other means like
   *              DHT/PEX, etc.).
   */
  @Override
  public void handleDiscoveredPeers(final List<Peer> peers) {
    if (peers == null || peers.isEmpty()) {
      // No peers returned by the tracker. Apparently we're alone on
      // this one for now.
      return;
    }

    LOG.info("Got {} peer(s) in tracker response.", peers.size());

    if (!this.service.isAlive()) {
      LOG.warn("Connection handler service is not available.");
      return;
    }

    for (Peer peer : peers) {
      // Attempt to connect to the peer if and only if:
      //   - We're not already connected or connecting to it;
      //   - We're not a seeder (we leave the responsibility
      //     of connecting to peers that need to download
      //     something).
      final SharingPeer match = this.getOrCreatePeer(peer);
      if (this.isSeed()) {
        continue;
      }

      synchronized (match) {
        if (!match.isConnected()) {
          this.service.connect(match);
        }
      }
    }
  }

  /**
   * IncomingConnectionListener handler(s). *******************************
   */
  /**
   * Handle a new peer connection.
   *
   * <p>
   * This handler is called once the connection has been successfully established and the handshake
   * exchange made. This generally simply means binding the peer to the socket, which will put in
   * place the communication thread and logic with this peer.
   * </p>
   *
   * @param channel The connected socket channel to the remote peer. Note that if the peer somehow
   *                rejected our handshake reply, this socket might very soon get closed, but this
   *                is handled down the road.
   * @param peerId  The byte-encoded peerId extracted from the peer's handshake, after validation.
   *
   * @see com.turn.ttorrent.client.peer.SharingPeer
   */
  @Override
  public void handleNewPeerConnection(final SocketChannel channel, final byte[] peerId) {
    final Peer search = new Peer(
            channel.socket().getInetAddress().getHostAddress(),
            channel.socket().getPort(),
            peerId != null ? ByteBuffer.wrap(peerId) : (ByteBuffer) null);

    LOG.info("Handling new peer connection with {}...", search);
    final SharingPeer peer = this.getOrCreatePeer(search);

    try {
      synchronized (peer) {
        if (peer.isConnected()) {
          LOG.info("Already connected with {}, closing link.", peer);
          channel.close();
          return;
        }

        peer.register(this);
        peer.bind(channel);
      }

      this.connected.put(peer.getHexPeerId(), peer);
      peer.register(this.torrent);
      LOG.debug("New peer connection with {} [{}/{}].", new Object[]{
        peer,
        this.connected.size(),
        this.peers.size()
      });
    } catch (final IOException e) {
      this.connected.remove(peer.getHexPeerId());
      LOG.warn("Could not handle new peer connection " + "with {}: {}", peer, e.getMessage());
    }
  }

  /**
   * Handle a failed peer connection.
   *
   * <p>
   * If an outbound connection failed (could not connect, invalid handshake, etc.), remove the peer
   * from our known peers.
   * </p>
   *
   * @param peer  The peer we were trying to connect with.
   * @param cause The exception encountered when connecting with the peer.
   */
  @Override
  public void handleFailedConnection(final SharingPeer peer, final Throwable cause) {
    LOG.warn("Could not connect to {}: {}.", peer, cause.getMessage());
    this.peers.remove(peer.getHostId());
    if (peer.hasPeerId()) {
      this.peers.remove(peer.getHexPeerId());
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void handlePeerChoked(final SharingPeer peer) {
    /*
     * Do nothing
     */
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void handlePeerReady(final SharingPeer peer) {
    /*
     * Do nothing
     */
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void handlePieceAvailability(final SharingPeer peer, final Piece piece) {
    /*
     * Do nothing
     */
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void handleBitfieldAvailability(final SharingPeer peer, final BitSet availablePieces) {
    /*
     * Do nothing
     */
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void handlePieceSent(final SharingPeer peer, final Piece piece) {
    /*
     * Do nothing
     */
  }

  /**
   * Piece download completion handler.
   *
   * <p>
   * When a piece is completed, and valid, we announce to all connected peers that we now have this
   * piece.
   * </p>
   *
   * <p>
   * We use this handler to identify when all of the pieces have been downloaded. When that's the
   * case, we can start the seeding period, if any.
   * </p>
   *
   * @param peer  The peer we got the piece from.
   * @param piece The piece in question.
   *
   * @throws IOException unable to finish piece
   */
  @Override
  public void handlePieceCompleted(final SharingPeer peer, final Piece piece) throws IOException {
    synchronized (this.torrent) {
      if (piece.isValid()) {
        // Make sure the piece is marked as completed in the torrent
        // Note: this is required because the order the
        // PeerActivityListeners are called is not defined, and we
        // might be called before the torrent's piece completion
        // handler is.
        this.torrent.markCompleted(piece);
        LOG.debug("Completed download of {} from {}. " + "We now have {}/{} pieces",
                  piece,
                  peer,
                  this.torrent.getCompletedPieces().cardinality(),
                  this.torrent.getPieceCount());

        // Send a HAVE message to all connected peers
        final PeerMessage have = PeerMessage.HaveMessage.craft(piece.getIndex());
        for (SharingPeer remote : this.connected.values()) {
          remote.send(have);
        }

        // Force notify after each piece is completed to propagate download
        // completion information (or new seeding state)
        this.setChanged();
        this.notifyObservers(this.state);
      } else {
        LOG.warn("Downloaded piece#{} from {} was not valid ;-(", piece.getIndex(), peer);
      }

      if (this.torrent.isComplete()) {
        LOG.info("Last piece validated and completed, finishing download...");

        // Cancel all remaining outstanding requests
        for (SharingPeer remote : this.connected.values()) {
          if (remote.isDownloading()) {
            final int requests = remote.cancelPendingRequests().size();
            LOG.info("Cancelled {} remaining pending requests on {}.", requests, remote);
          }
        }

        this.torrent.finish();

        try {
          this.announce.getCurrentTrackerClient()
                  .announce(TrackerMessage.AnnounceRequestMessage.RequestEvent.COMPLETED, true);
        } catch (final AnnounceException ae) {
          LOG.warn("Error announcing completion event to " + "tracker: {}", ae.getMessage());
        }

        LOG.info("Download is complete and finalized.");
        this.seed();
      }
    }
  }

  @Override
  public void handlePeerDisconnected(final SharingPeer peer) {
    if (this.connected.remove(peer.hasPeerId()
                              ? peer.getHexPeerId()
                              : peer.getHostId()) != null) {
      LOG.debug("Peer {} disconnected, [{}/{}].",
                peer,
                this.connected.size(),
                this.peers.size());
    }

    peer.reset();
  }

  @Override
  public void handleIoException(final SharingPeer peer, final IOException ioe) {
    LOG.warn("I/O error while exchanging data with {}, closing connection with it!",
             peer,
             ioe.getMessage());
    peer.unbind(true);
  }

  /**
   * Post download seeding. ***********************************************
   */
  /**
   * Start the seeding period, if any.
   *
   * <p>
   * This method is called when all the pieces of our torrent have been retrieved. This may happen
   * immediately after the client starts if the torrent was already fully download or we are the
   * initial seeder client.
   * </p>
   *
   * <p>
   * When the download is complete, the client switches to seeding mode for as long as requested in
   * the <code>share()</code> call, if seeding was requested. If not, the {@link ClientShutdown}
   * will execute immediately to stop the client's main loop.
   * </p>
   *
   * @see ClientShutdown
   */
  private synchronized void seed() {
    // Silently ignore if we're already seeding.
    if (ClientState.SEEDING.equals(this.getState())) {
      return;
    }

    LOG.info("Download of {} pieces completed.", this.torrent.getPieceCount());

    this.setState(ClientState.SEEDING);
    if (this.seed < 0) {
      LOG.info("Seeding indefinetely...");
      return;
    }

    // In case seeding for 0 seconds we still need to schedule the task in
    // order to call stop() from different thread to avoid deadlock
    LOG.info("Seeding for {} seconds...", this.seed);
    final Timer timer = new Timer();
    timer.schedule(new ClientShutdown(this, timer), this.seed * 1000);
  }

  /**
   * Timer task to stop seeding.
   *
   * <p>
   * This TimerTask will be called by a timer set after the download is complete to stop seeding
   * from this client after a certain amount of requested seed time (might be 0 for immediate
   * termination).
   * </p>
   *
   * <p>
   * This task simply contains a reference to this client instance and calls its <code>stop()</code>
   * method to interrupt the client's main loop.
   * </p>
   *
   * @author mpetazzoni
   */
  @AllArgsConstructor
  public static class ClientShutdown extends TimerTask {

    private final Client client;

    private final Timer timer;

    @Override
    public void run() {
      this.client.stop();
      if (this.timer != null) {
        this.timer.cancel();
      }
    }
  }
}
