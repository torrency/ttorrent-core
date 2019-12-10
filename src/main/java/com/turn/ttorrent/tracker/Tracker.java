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

package com.turn.ttorrent.tracker;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collection;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.turn.ttorrent.common.Torrent;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.simpleframework.transport.connect.Connection;
import org.simpleframework.transport.connect.SocketConnection;

/**
 * BitTorrent tracker.
 *
 * <p>
 * The tracker usually listens on port 6969 (the standard BitTorrent tracker port). Torrents must be
 * registered directly to this tracker with the {@link
 * #announce(TrackedTorrent torrent)} method.
 * </p>
 *
 * @author mpetazzoni
 */
@Slf4j
public class Tracker {

  /**
   * Request path handled by the tracker announce request handler.
   */
  public static final String ANNOUNCE_URL = "/announce";

  /**
   * Default tracker listening port (BitTorrent's default is 6969).
   */
  public static final int DEFAULT_TRACKER_PORT = 6969;

  /**
   * Default server name and version announced by the tracker.
   */
  public static final String DEFAULT_VERSION_STRING = "BitTorrent Tracker (ttorrent)";

  private Connection connection;

  private final InetSocketAddress address;

  /**
   * The in-memory repository of torrents tracked.
   */
  private final ConcurrentMap<String, TrackedTorrent> torrents;

  private Thread tracker;

  private Thread collector;

  private boolean stop;

  /**
   * Create a new BitTorrent tracker listening at the given address on the default port.
   *
   * @param address The address to bind to.
   *
   * @throws IOException Throws an <em>IOException</em> if the tracker cannot be initialized.
   *
   */
  public Tracker(final InetAddress address) throws IOException {
    this(new InetSocketAddress(address, DEFAULT_TRACKER_PORT));
  }

  /**
   * Create a new BitTorrent tracker listening at the given address.
   *
   * @param address The address to bind to.
   *
   * @throws IOException Throws an <em>IOException</em> if the tracker cannot be initialized.
   *
   */
  public Tracker(final InetSocketAddress address) throws IOException {
    this(address, new ConcurrentHashMap<>());
  }

  /**
   * Create a new BitTorrent tracker listening at the given address.
   *
   * @param address  The address to bind to.
   * @param torrents The torrent list
   *
   * @throws IOException Throws an <em>IOException</em> if the tracker cannot be initialized.
   *
   */
  public Tracker(final InetSocketAddress address,
                 final ConcurrentMap<String, TrackedTorrent> torrents) throws IOException {
    this.address = address;
    this.torrents = torrents;
    this.connection = new SocketConnection(new TrackerService(this.torrents));
  }

  /**
   * Create a new BitTorrent tracker listening at the given address.<BR>
   * Able to use customize TrackerService.
   *
   * @param address The address to bind to.
   * @param service Customize service
   *
   * @throws IOException Throws an <em>IOException</em> if the tracker cannot be initialized.
   *
   */
  public Tracker(final InetSocketAddress address,
                 final TrackerService service) throws IOException {
    this.address = address;
    this.torrents = service.getTorrents();
    this.connection = new SocketConnection(service);
  }

  /**
   * Create a new BitTorrent tracker listening at the given address.<BR>
   * Able to use customize TrackerService.
   *
   * @param address The address to bind to.
   * @param service Customize service
   *
   * @throws IOException Throws an <em>IOException</em> if the tracker cannot be initialized.
   *
   */
  public Tracker(final InetAddress address,
                 final TrackerService service) throws IOException {
    this.address = new InetSocketAddress(address, DEFAULT_TRACKER_PORT);
    this.torrents = service.getTorrents();
    this.connection = new SocketConnection(service);
  }

  /**
   * Returns the full announce URL served by this tracker.
   *
   * <p>
   * This has the form http://host:port/announce.
   * </p>
   *
   * @return announce URL
   */
  public URL getAnnounceUrl() {
    try {
      return new URL("http",
                     this.address.getAddress().getCanonicalHostName(),
                     this.address.getPort(),
                     Tracker.ANNOUNCE_URL);
    } catch (final MalformedURLException mue) {
      LOG.error("Could not build tracker URL: {}!", mue, mue);
    }

    return null;
  }

  /**
   * Start the tracker thread.<BR>
   * By specifying the tracker service, user can customize tracker handler.
   */
  public void start() {
    if (this.tracker == null || !this.tracker.isAlive()) {
      this.tracker = new TrackerThread();
      this.tracker.setName("tracker:" + this.address.getPort());
      this.tracker.start();
    }

    if (this.collector == null || !this.collector.isAlive()) {
      this.collector = new PeerCollectorThread();
      this.collector.setName("peer-collector:" + this.address.getPort());
      this.collector.start();
    }
  }

  /**
   * Stop the tracker.
   *
   * <p>
   * This effectively closes the listening HTTP connection to terminate the service, and interrupts
   * the peer collector thread as well.
   * </p>
   */
  public void stop() {
    this.stop = true;

    try {
      this.connection.close();
      LOG.info("BitTorrent tracker closed.");
    } catch (final IOException ioe) {
      LOG.error("Could not stop the tracker: {}!", ioe.getMessage());
    }

    if (this.collector != null && this.collector.isAlive()) {
      this.collector.interrupt();
      LOG.info("Peer collection terminated.");
    }
  }

  /**
   * Returns the list of tracker's torrents.
   *
   * @return tracked torrents
   */
  public Collection<TrackedTorrent> getTrackedTorrents() {
    return this.torrents.values();
  }

  /**
   * Announce a new torrent on this tracker.
   *
   * <p>
   * The fact that torrents must be announced here first makes this tracker a closed BitTorrent
   * tracker: it will only accept clients for torrents it knows about, and this list of torrents is
   * managed by the program instrumenting this Tracker class.
   * </p>
   *
   * @param torrent The Torrent object to start tracking.
   *
   * @return The torrent object for this torrent on this tracker. This may be different from the
   *         supplied Torrent object if the tracker already contained a torrent with the same hash.
   */
  public synchronized TrackedTorrent announce(final TrackedTorrent torrent) {
    final TrackedTorrent existing = this.torrents.get(torrent.getHexInfoHash());

    if (existing != null) {
      LOG.warn("Tracker already announced torrent for '{}' with hash {}.",
               existing.getName(),
               existing.getHexInfoHash());
      return existing;
    }

    this.torrents.put(torrent.getHexInfoHash(), torrent);
    LOG.info("Registered new torrent for '{}' with hash {}.",
             torrent.getName(),
             torrent.getHexInfoHash());
    return torrent;
  }

  /**
   * Stop announcing the given torrent.
   *
   * @param torrent The Torrent object to stop tracking.
   */
  public synchronized void remove(final Torrent torrent) {
    if (torrent == null) {
      return;
    }

    this.torrents.remove(torrent.getHexInfoHash());
  }

  /**
   * Stop announcing the given torrent after a delay.
   *
   * @param torrent The Torrent object to stop tracking.
   * @param delay   The delay, in milliseconds, before removing the torrent.
   */
  public synchronized void remove(final Torrent torrent, final long delay) {
    if (torrent == null) {
      return;
    }

    new Timer().schedule(new TorrentRemoveTimer(this, torrent), delay);
  }

  /**
   * Timer task for removing a torrent from a tracker.
   *
   * <p>
   * This task can be used to stop announcing a torrent after a certain delay through a Timer.
   * </p>
   */
  @Value
  private static class TorrentRemoveTimer extends TimerTask {

    private Tracker tracker;

    private Torrent torrent;

    @Override
    public void run() {
      this.tracker.remove(this.torrent);
    }
  }

  /**
   * The main tracker thread.
   *
   * <p>
   * The core of the BitTorrent tracker run by the controller is the SimpleFramework HTTP service
   * listening on the configured address. It can be stopped with the <em>stop()</em> method, which
   * closes the listening socket.
   * </p>
   */
  private class TrackerThread extends Thread {

    @Override
    public void run() {
      LOG.info("Starting BitTorrent tracker on {}...",
               Tracker.this.getAnnounceUrl());

      try {
        Tracker.this.connection.connect(Tracker.this.address);
      } catch (final IOException ioe) {
        LOG.error("Could not start the tracker: {}!", ioe.getMessage());
        Tracker.this.stop();
      }
    }
  }

  /**
   * The unfresh peer collector thread.
   *
   * <p>
   * Every PEER_COLLECTION_FREQUENCY_SECONDS, this thread will collect unfresh peers from all
   * announced torrents.
   * </p>
   */
  private class PeerCollectorThread extends Thread {

    private static final int PEER_COLLECTION_FREQUENCY_SECONDS = 15;

    @Override
    public void run() {
      LOG.info("Starting tracker peer collection for tracker at {}...",
               Tracker.this.getAnnounceUrl());

      while (!Tracker.this.stop) {
        for (TrackedTorrent torrent : Tracker.this.torrents.values()) {
          torrent.collectUnfreshPeers();
        }

        try {
          Thread.sleep(PeerCollectorThread.PEER_COLLECTION_FREQUENCY_SECONDS * 1000);
        } catch (final InterruptedException ie) {
          // Ignore
        }
      }
    }
  }
}
