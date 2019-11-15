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
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.text.ParseException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.turn.ttorrent.client.peer.SharingPeer;
import com.turn.ttorrent.common.Torrent;
import com.turn.ttorrent.common.Utils;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;

/**
 * Incoming peer connections service.
 *
 * <p>
 * Every BitTorrent client, BitTorrent being a peer-to-peer protocol, listens on a port for incoming
 * connections from other peers sharing the same torrent.
 * </p>
 *
 * <p>
 * This ConnectionHandler implements this service and starts a listening socket in the first
 * available port in the default BitTorrent client port range 6881-6889. When a peer connects to it,
 * it expects the BitTorrent handshake message, parses it and replies with our own handshake.
 * </p>
 *
 * <p>
 * Outgoing connections to other peers are also made through this service, which handles the
 * handshake procedure with the remote peer. Regardless of the direction of the connection, once
 * this handshake is successful, all {@link IncomingConnectionListener}s are notified and passed the
 * connected socket and the remote peer ID.
 * </p>
 *
 * <p>
 * This class does nothing more. All further peer-to-peer communication happens in the
 * <code>PeerExchange</code> class.
 * </p>
 *
 * @author mpetazzoni
 * @see <a href="http://wiki.theory.org/BitTorrentSpecification#Handshake">BitTorrent handshake
 * specification</a>
 */
@Slf4j
public class ConnectionHandler implements Runnable {

  public static final int PORT_RANGE_START = 49152;

  public static final int PORT_RANGE_END = 65534;

  private static final int OUTBOUND_CONNECTIONS_POOL_SIZE = 20;

  private static final int OUTBOUND_CONNECTIONS_THREAD_KEEP_ALIVE_SECS = 10;

  private static final int CLIENT_KEEP_ALIVE_MINUTES = 3;

  private SharedTorrent torrent;

  private String id;

  private ServerSocketChannel channel;

  private InetSocketAddress address;

  private Set<IncomingConnectionListener> listeners;

  private ExecutorService executor;

  private Thread thread;

  private boolean stop;

  /**
   * Create and start a new listening service for out torrent, reporting with our peer ID on the
   * given address.
   *
   * <p>
   * This binds to the first available port in the client port range PORT_RANGE_START to
   * PORT_RANGE_END.
   * </p>
   *
   * @param torrent The torrent shared by this client.
   * @param id      This client's peer ID.
   * @param address The address to bind to.
   *
   * @throws IOException When the service can't be started because no port in the defined range is
   *                     available or usable.
   */
  ConnectionHandler(final SharedTorrent torrent, final String id, final InetAddress address)
          throws IOException {
    this.torrent = torrent;
    this.id = id;

    // Bind to the first available port in the range
    // [PORT_RANGE_START; PORT_RANGE_END].
    for (int port = ConnectionHandler.PORT_RANGE_START;
         port <= ConnectionHandler.PORT_RANGE_END;
         port++) {
      final InetSocketAddress tryAddress = new InetSocketAddress(address, port);

      try {
        this.channel = ServerSocketChannel.open();
        this.channel.socket().bind(tryAddress);
        this.channel.configureBlocking(false);
        this.address = tryAddress;
        break;
      } catch (final IOException ioe) {
        // Ignore, try next port
        LOG.warn("Could not bind to {}, trying next port...", tryAddress);
      }
    }

    if (this.channel == null || !this.channel.socket().isBound()) {
      throw new IOException("No available port for the BitTorrent client!");
    }

    LOG.info("Listening for incoming connections on {}.", this.address);

    this.listeners = new HashSet<>();
    this.executor = null;
    this.thread = null;
  }

  /**
   * Return the full socket address this service is bound to.
   *
   * @return server address
   */
  public InetSocketAddress getSocketAddress() {
    return this.address;
  }

  /**
   * Register a new incoming connection listener.
   *
   * @param listener The listener who wants to receive connection notifications.
   */
  public void register(final IncomingConnectionListener listener) {
    this.listeners.add(listener);
  }

  /**
   * Start accepting new connections in a background thread.
   */
  public void start() {
    if (this.channel == null) {
      throw new IllegalStateException("Connection handler cannot be recycled!");
    }

    this.stop = false;

    if (this.executor == null || this.executor.isShutdown()) {
      this.executor = new ThreadPoolExecutor(
              OUTBOUND_CONNECTIONS_POOL_SIZE,
              OUTBOUND_CONNECTIONS_POOL_SIZE,
              OUTBOUND_CONNECTIONS_THREAD_KEEP_ALIVE_SECS,
              TimeUnit.SECONDS,
              new LinkedBlockingQueue<Runnable>(),
              new ConnectorThreadFactory());
    }

    if (this.thread == null || !this.thread.isAlive()) {
      this.thread = new Thread(this);
      this.thread.setName("bt-serve");
      this.thread.start();
    }
  }

  /**
   * Stop accepting connections.
   *
   * <p>
   * <b>Note:</b> the underlying socket remains open and bound.
   * </p>
   */
  public void stop() {
    this.stop = true;

    if (this.thread != null && this.thread.isAlive()) {
      try {
        this.thread.join();
      } catch (final InterruptedException ie) {
        Thread.currentThread().interrupt();
      }
    }

    if (this.executor != null && !this.executor.isShutdown()) {
      this.executor.shutdownNow();
    }

    this.executor = null;
    this.thread = null;
  }

  /**
   * Close this connection handler to release the port it is bound to.
   *
   * @throws IOException If the channel could not be closed.
   */
  public void close() throws IOException {
    if (this.channel != null) {
      this.channel.close();
      this.channel = null;
    }
  }

  /**
   * The main service loop.
   *
   * <p>
   * The service waits for new connections for 250ms, then waits 100ms so it can be interrupted.
   * </p>
   */
  @Override
  public void run() {
    while (!this.stop) {
      try {
        final SocketChannel client = this.channel.accept();
        if (client != null) {
          this.accept(client);
        }
      } catch (final SocketTimeoutException ste) {
        // Ignore and go back to sleep
        LOG.debug("Ignore and go back to sleep", ste);
      } catch (final IOException ioe) {
        LOG.warn("Unrecoverable error in connection handler", ioe);
        this.stop();
      }

      try {
        Thread.sleep(100);
      } catch (final InterruptedException ie) {
        Thread.currentThread().interrupt();
      }
    }
  }

  /**
   * Return a human-readable representation of a connected socket channel.
   *
   * @param channel The socket channel to represent.
   *
   * @return A textual representation (<em>host:port</em>) of the given socket.
   */
  private String socketRepr(final SocketChannel channel) {
    final Socket s = channel.socket();
    return String.format("%s:%d%s",
                         s.getInetAddress().getHostName(),
                         s.getPort(),
                         channel.isConnected() ? "+" : "-");
  }

  /**
   * Accept the next incoming connection.
   *
   * <p>
   * When a new peer connects to this service, wait for it to send its handshake. We then parse and
   * check that the handshake advertises the torrent hash we expect, then reply with our own
   * handshake.
   * </p>
   *
   * <p>
   * If everything goes according to plan, notify the <code>IncomingConnectionListener</code>s with
   * the connected socket and the parsed peer ID.
   * </p>
   *
   * @param client The accepted client's socket channel.
   */
  private void accept(final SocketChannel client) throws IOException, SocketTimeoutException {
    try {
      LOG.debug("New incoming connection, waiting for handshake...");
      final Handshake hs = this.validateHandshake(client, null);
      final int sent = this.sendHandshake(client);
      LOG.trace("Replied to {} with handshake ({} bytes).", this.socketRepr(client), sent);

      // Go to non-blocking mode for peer interaction
      client.configureBlocking(false);
      client.socket().setSoTimeout(CLIENT_KEEP_ALIVE_MINUTES * 60 * 1000);
      this.fireNewPeerConnection(client, hs.getPeerId());
    } catch (final ParseException pe) {
      LOG.info("Invalid handshake from {}: {}", this.socketRepr(client), pe.getMessage());
      IOUtils.closeQuietly(client);
    } catch (final IOException ioe) {
      LOG.warn("An error occured while reading an incoming " + "handshake: {}", ioe.getMessage());
      if (client.isConnected()) {
        IOUtils.closeQuietly(client);
      }
    }
  }

  /**
   * Tells whether the connection handler is running and can be used to handle new peer connections.
   *
   * @return true if executor is running is serving correctly
   */
  public boolean isAlive() {
    return this.executor != null
           && !this.executor.isShutdown()
           && !this.executor.isTerminated();
  }

  /**
   * Connect to the given peer and perform the BitTorrent handshake.
   *
   * <p>
   * Submits an asynchronous connection task to the outbound connections executor to connect to the
   * given peer.
   * </p>
   *
   * @param peer The peer to connect to.
   */
  public void connect(final SharingPeer peer) {
    if (!this.isAlive()) {
      throw new IllegalStateException(
              "Connection handler is not accepting new peers at this time!");
    }

    this.executor.submit(new ConnectorTask(this, peer));
  }

  /**
   * Validate an expected handshake on a connection.
   *
   * <p>
   * Reads an expected handshake message from the given connected socket, parses it and validates
   * that the torrent hash_info corresponds to the torrent we're sharing, and that the peerId
   * matches the peer ID we expect to see coming from the remote peer.
   * </p>
   *
   * @param channel The connected socket channel to the remote peer.
   * @param peerId  The peer ID we expect in the handshake. If <em>null</em>, any peer ID is
   *                accepted (this is the case for incoming connections).
   *
   * @return The validated handshake message object.
   */
  private Handshake validateHandshake(final SocketChannel channel, final byte[] peerId)
          throws IOException, ParseException {
    final ByteBuffer len = ByteBuffer.allocate(1);
    final ByteBuffer data;

    // Read the handshake from the wire
    LOG.trace("Reading handshake size (1 byte) from {}...", this.socketRepr(channel));
    if (channel.read(len) < len.capacity()) {
      throw new IOException("Handshake size read underrrun");
    }

    len.rewind();
    final int pstrlen = len.get();

    data = ByteBuffer.allocate(Handshake.BASE_HANDSHAKE_LENGTH + pstrlen);
    data.put((byte) pstrlen);
    final int expected = data.remaining();
    final int read = channel.read(data);
    if (read < expected) {
      throw new IOException("Handshake data read underrun ("
                            + read + " < " + expected + " bytes)");
    }

    // Parse and check the handshake
    data.rewind();
    final Handshake hs = Handshake.parse(data);
    if (!Arrays.equals(hs.getInfoHash(), this.torrent.getInfoHash())) {
      throw new ParseException("Handshake for unknow torrent "
                               + Utils.bytesToHex(hs.getInfoHash())
                               + " from " + this.socketRepr(channel) + ".", pstrlen + 9);
    }

    if (peerId != null && !Arrays.equals(hs.getPeerId(), peerId)) {
      throw new ParseException("Announced peer ID "
                               + Utils.bytesToHex(hs.getPeerId())
                               + " did not match expected peer ID "
                               + Utils.bytesToHex(peerId) + ".", pstrlen + 29);
    }

    return hs;
  }

  /**
   * Send our handshake message to the socket.
   *
   * @param channel The socket channel to the remote peer.
   */
  private int sendHandshake(final SocketChannel channel) throws IOException {
    return channel.write(Handshake.craft(this.torrent.getInfoHash(),
                                         this.id.getBytes(Torrent.BYTE_ENCODING)).getData());
  }

  /**
   * Trigger the new peer connection event on all registered listeners.
   *
   * @param channel The socket channel to the newly connected peer.
   * @param peerId  The peer ID of the connected peer.
   */
  private void fireNewPeerConnection(final SocketChannel channel, final byte[] peerId) {
    for (IncomingConnectionListener listener : this.listeners) {
      listener.handleNewPeerConnection(channel, peerId);
    }
  }

  private void fireFailedConnection(final SharingPeer peer, final Throwable cause) {
    for (IncomingConnectionListener listener : this.listeners) {
      listener.handleFailedConnection(peer, cause);
    }
  }

  /**
   * A simple thread factory that returns appropriately named threads for outbound connector
   * threads.
   *
   * @author mpetazzoni
   */
  private static class ConnectorThreadFactory implements ThreadFactory {

    private int number = 0;

    @Override
    public Thread newThread(final Runnable r) {
      final Thread t = new Thread(r);
      t.setName(String.format("bt-connect-%d", ++this.number));
      return t;
    }
  }

  /**
   * An outbound connection task.
   *
   * <p>
   * These tasks are fed to the thread executor in charge of processing outbound connection
   * requests. It attempts to connect to the given peer and proceeds with the BitTorrent handshake.
   * If the handshake is successful, the new peer connection event is fired to all incoming
   * connection listeners. Otherwise, the failed connection event is fired.
   * </p>
   *
   * @author mpetazzoni
   */
  @AllArgsConstructor
  private static class ConnectorTask implements Runnable {

    private final ConnectionHandler handler;

    private final SharingPeer peer;

    @Override
    public void run() {
      final InetSocketAddress address = new InetSocketAddress(this.peer.getIp(),
                                                              this.peer.getPort());
      SocketChannel channel = null;

      try {
        LOG.info("Connecting to {}...", this.peer);
        channel = SocketChannel.open(address);
        while (!channel.isConnected()) {
          Thread.sleep(10);
        }

        LOG.debug("Connected. Sending handshake to {}...", this.peer);
        channel.configureBlocking(true);
        final int sent = this.handler.sendHandshake(channel);
        LOG.debug("Sent handshake ({} bytes), waiting for response...", sent);
        final Handshake hs = this.handler.validateHandshake(channel,
                                                            this.peer.hasPeerId()
                                                            ? this.peer.getPeerId().array()
                                                            : null);
        LOG.info("Handshaked with {}, peer ID is {}.",
                 this.peer,
                 Utils.bytesToHex(hs.getPeerId()));

        // Go to non-blocking mode for peer interaction
        channel.configureBlocking(false);
        this.handler.fireNewPeerConnection(channel, hs.getPeerId());
      } catch (IOException | InterruptedException | ParseException e) {
        if (channel != null && channel.isConnected()) {
          IOUtils.closeQuietly(channel);
        }
        this.handler.fireFailedConnection(this.peer, e);
      }
    }
  }
}
