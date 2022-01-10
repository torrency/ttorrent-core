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

import java.io.EOFException;
import java.io.IOException;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.text.ParseException;
import java.util.BitSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.turn.ttorrent.client.SharedTorrent;
import com.turn.ttorrent.common.protocol.PeerMessage;
import com.turn.ttorrent.common.protocol.PeerMessage.Type;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;

/**
 * Incoming and outgoing peer communication system.
 *
 * <p>
 * The peer exchange is a wrapper around peer communication. It provides both incoming and outgoing
 * communication channels to a connected peer after a successful handshake.
 * </p>
 *
 * <p>
 * When a socket is bound to a sharing peer, a PeerExchange is automatically created to wrap this
 * socket into a more usable system for communication with the remote peer.
 * </p>
 *
 * <p>
 * For incoming messages, the peer exchange provides message parsing and calls the
 * <code>handleMessage()</code> method of the peer for each successfully parsed message.
 * </p>
 *
 * <p>
 * For outgoing message, the peer exchange offers a <code>send()</code> message that queues
 * messages, and takes care of automatically sending a keep-alive message to the remote peer every
 * two minutes when other message have been sent in that period of time, as recommended by the
 * BitTorrent protocol specification.
 * </p>
 *
 * @author mpetazzoni
 */
@Slf4j
public class PeerExchange {

  private static final int KEEP_ALIVE_IDLE_MINUTES = 2;

  private static final PeerMessage STOP = PeerMessage.KeepAliveMessage.craft();

  private final SharingPeer peer;

  private final SharedTorrent torrent;

  private final SocketChannel channel;

  private final Set<MessageListener> listeners;

  private IncomingThread in;

  private OutgoingThread out;

  private final BlockingQueue<PeerMessage> sendQueue;

  private volatile boolean stop;

  /**
   * Initialize and start a new peer exchange.
   *
   * @param peer    The remote peer to communicate with.
   * @param torrent The torrent we're exchanging on with the peer.
   * @param channel A channel on the connected socket to the peer.
   *
   * @throws SocketException Unable to exchange peer
   */
  public PeerExchange(final SharingPeer peer,
                      final SharedTorrent torrent,
                      final SocketChannel channel) throws SocketException {
    this.peer = peer;
    this.torrent = torrent;
    this.channel = channel;

    this.listeners = new HashSet<>();
    this.sendQueue = new LinkedBlockingQueue<>();

    if (!this.peer.hasPeerId()) {
      throw new IllegalStateException("Peer does not have a peer ID. "
                                      + "Was the handshake made properly?");
    }

    this.in = new IncomingThread();
    this.in.setName("bt-peer(" + this.peer.getShortHexPeerId() + ")-recv");

    this.out = new OutgoingThread();
    this.out.setName("bt-peer(" + this.peer.getShortHexPeerId() + ")-send");
    this.out.setDaemon(true);

    this.stop = false;

    LOG.debug("Started peer exchange with {} for {}.", this.peer, this.torrent);

    // If we have pieces, start by sending a BITFIELD message to the peer.
    final BitSet pieces = this.torrent.getCompletedPieces();
    if (pieces.cardinality() > 0) {
      this.send(PeerMessage.BitfieldMessage.craft(pieces, torrent.getPieceCount()));
    }
  }

  /**
   * Register a new message listener to receive messages.
   *
   * @param listener The message listener object.
   */
  public void register(final MessageListener listener) {
    this.listeners.add(listener);
  }

  /**
   * Tells if the peer exchange is active.
   *
   * @return if channel is connected
   */
  public boolean isConnected() {
    return this.channel.isConnected();
  }

  /**
   * Send a message to the connected peer.
   *
   * <p>
   * The message is queued in the outgoing message queue and will be processed as soon as possible.
   * </p>
   *
   * @param message The message object to send.
   */
  public void send(final PeerMessage message) {
    try {
      this.sendQueue.put(message);
    } catch (final InterruptedException ie) {
      // Ignore, our send queue will only block if it contains
      // MAX_INTEGER messages, in which case we're already in big
      // trouble, and we'd have to be interrupted, too.
    }
  }

  /**
   * Start the peer exchange.
   *
   * <p>
   * Starts both incoming and outgoing thread.
   * </p>
   */
  public void start() {
    this.in.start();
    this.out.start();
  }

  /**
   * Stop the peer exchange.
   *
   * <p>
   * Closes the socket channel and stops both incoming and outgoing threads.
   * </p>
   */
  public void stop() {
    this.stop = true;

    try {
      // Wake-up and shutdown out-going thread immediately
      this.sendQueue.put(STOP);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    if (this.channel.isConnected()) {
      IOUtils.closeQuietly(this.channel);
    }

    LOG.debug("Peer exchange with {} closed.", this.peer);
  }

  /**
   * Abstract Thread subclass that allows conditional rate limiting for <code>PIECE</code> messages.
   *
   * <p>
   * To impose rate limits, we only want to throttle when processing PIECE messages. All other peer
   * messages should be exchanged as quickly as possible.
   * </p>
   *
   * @author ptgoetz
   */
  private abstract class RateLimitThread extends Thread {

    protected final Rate rate = new Rate();

    protected long sleep = 1000;

    /**
     * Dynamically determines an amount of time to sleep, based on the average read/write
     * throughput.
     *
     * <p>
     * The algorithm is functional, but could certainly be improved upon. One obvious drawback is
     * that with large changes in <code>maxRate</code>, it will take a while for the sleep time to
     * adjust and the throttled rate to "smooth out."
     * </p>
     *
     * <p>
     * Ideally, it would calculate the optimal sleep time necessary to hit a desired throughput
     * rather than continuously adjust toward a goal.
     * </p>
     *
     * @param maxRate     the target rate in kB/second.
     * @param messageSize the size, in bytes, of the last message read/written.
     * @param message     the last <code>PeerMessage</code> read/written.
     */
    protected void rateLimit(final double maxRate,
                             final long messageSize,
                             final PeerMessage message) {
      if (message.getType() != Type.PIECE || maxRate <= 0) {
        return;
      }

      try {
        this.rate.add(messageSize);

        // Continuously adjust the sleep time to try to hit our target
        // rate limit.
        if (this.rate.get() > (maxRate * 1024)) {
          Thread.sleep(this.sleep);
          this.sleep += 50;
        } else {
          this.sleep = this.sleep > 50
                       ? this.sleep - 50
                       : 0;
        }
      } catch (final InterruptedException e) {
        // Not critical, eat it.
      }
    }
  }

  /**
   * Incoming messages thread.
   *
   * <p>
   * The incoming messages thread reads from the socket's input stream and waits for incoming
   * messages. When a message is fully retrieve, it is parsed and passed to the peer's
   * <code>handleMessage()</code> method that will act based on the message type.
   * </p>
   *
   * @author mpetazzoni
   */
  private class IncomingThread extends RateLimitThread {

    /**
     * Read data from the incoming channel of the socket using a {@link
     * Selector}.
     *
     * @param selector The socket selector into which the peer socket has been inserted.
     * @param buffer   A {@link ByteBuffer} to put the read data into.
     *
     * @return The number of bytes read.
     */
    private long read(final Selector selector, final ByteBuffer buffer) throws IOException {
      if (selector.select() == 0 || !buffer.hasRemaining()) {
        return 0;
      }

      long size = 0;
      final Iterator it = selector.selectedKeys().iterator();
      while (it.hasNext()) {
        final SelectionKey key = (SelectionKey) it.next();
        if (key.isValid() && key.isReadable()) {
          final int read = ((SocketChannel) key.channel()).read(buffer);
          if (read < 0) {
            throw new IOException("Unexpected end-of-stream while reading");
          }
          size += read;
        }
        it.remove();
      }

      return size;
    }

    private void handleIoe(final IOException ioe) {
      LOG.debug("Could not read message from {}: {}",
                PeerExchange.this.peer,
                ioe.getMessage() != null
                ? ioe.getMessage()
                : ioe.getClass().getName());
      PeerExchange.this.peer.unbind(true);
    }

    @Override
    public void run() {
      final ByteBuffer buffer = ByteBuffer.allocateDirect(1 * 1024 * 1024);
      Selector selector = null;

      try {
        selector = Selector.open();
        PeerExchange.this.channel.register(selector, SelectionKey.OP_READ);

        while (!PeerExchange.this.stop) {
          buffer.rewind();
          buffer.limit(PeerMessage.MESSAGE_LENGTH_FIELD_SIZE);

          // Keep reading bytes until the length field has been read
          // entirely.
          while (!PeerExchange.this.stop && buffer.hasRemaining()) {
            this.read(selector, buffer);
          }

          // Reset the buffer limit to the expected message size.
          final int pstrlen = buffer.getInt(0);
          buffer.limit(PeerMessage.MESSAGE_LENGTH_FIELD_SIZE + pstrlen);

          long size = 0;
          while (!PeerExchange.this.stop && buffer.hasRemaining()) {
            size += this.read(selector, buffer);
          }

          buffer.rewind();

          if (PeerExchange.this.stop) {
            // The buffer may contain the type from the last message
            // if we were stopped before reading the payload and cause
            // BufferUnderflowException in parsing.
            break;
          }

          try {
            final PeerMessage message = PeerMessage.parse(buffer, PeerExchange.this.torrent);
            LOG.trace("Received {} from {}", message, PeerExchange.this.peer);

            // Wait if needed to reach configured download rate.
            this.rateLimit(PeerExchange.this.torrent.getMaxDownloadRate(), size, message);

            for (MessageListener listener : PeerExchange.this.listeners) {
              listener.handleMessage(message);
            }
          } catch (final ParseException pe) {
            LOG.warn("{}", pe.getMessage());
          }
        }
      } catch (final IOException ioe) {
        this.handleIoe(ioe);
      } finally {
        try {
          if (selector != null) {
            selector.close();
          }
        } catch (final IOException ioe) {
          this.handleIoe(ioe);
        }
      }
    }
  }

  /**
   * Outgoing messages thread.
   *
   * <p>
   * The outgoing messages thread waits for messages to appear in the send queue and processes them,
   * in order, as soon as they arrive.
   * </p>
   *
   * <p>
   * If no message is available for KEEP_ALIVE_IDLE_MINUTES minutes, it will automatically send a
   * keep-alive message to the remote peer to keep the connection active.
   * </p>
   *
   * @author mpetazzoni
   */
  private class OutgoingThread extends RateLimitThread {

    @Override
    public void run() {
      try {
        // Loop until told to stop. When stop was requested, loop until
        // the queue is served.
        while (!PeerExchange.this.stop || !PeerExchange.this.sendQueue.isEmpty()) {
          try {
            // Wait for two minutes for a message to send
            PeerMessage message = PeerExchange.this.sendQueue
                    .poll(PeerExchange.KEEP_ALIVE_IDLE_MINUTES, TimeUnit.MINUTES);

            if (message == STOP) {
              return;
            }

            if (message == null) {
              message = PeerMessage.KeepAliveMessage.craft();
            }

            LOG.trace("Sending {} to {}", message, PeerExchange.this.peer);

            final ByteBuffer data = message.getData();
            long size = 0;
            while (!PeerExchange.this.stop && data.hasRemaining()) {
              final int written = PeerExchange.this.channel.write(data);
              size += written;
              if (written < 0) {
                throw new EOFException("Reached end of stream while writing");
              }
            }

            // Wait if needed to reach configured upload rate.
            this.rateLimit(PeerExchange.this.torrent.getMaxUploadRate(),
                           size, message);
          } catch (final InterruptedException ie) {
            // Ignore and potentially terminate
          }
        }
      } catch (final IOException ioe) {
        LOG.debug("Could not send message to {}: {}",
                  PeerExchange.this.peer,
                  ioe.getMessage() != null
                  ? ioe.getMessage()
                  : ioe.getClass().getName());
        PeerExchange.this.peer.unbind(true);
      }
    }
  }
}
