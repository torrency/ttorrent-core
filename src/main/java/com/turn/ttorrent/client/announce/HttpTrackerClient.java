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
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;

import com.turn.ttorrent.client.SharedTorrent;
import com.turn.ttorrent.common.Peer;
import com.turn.ttorrent.common.protocol.TrackerMessage.AnnounceRequestMessage;
import com.turn.ttorrent.common.protocol.TrackerMessage.MessageValidationException;
import com.turn.ttorrent.common.protocol.http.HttpAnnounceRequestMessage;
import com.turn.ttorrent.common.protocol.http.HttpTrackerMessage;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.output.ByteArrayOutputStream;

/**
 * Announcer for HTTP trackers.
 *
 * @author mpetazzoni
 * @see
 * <a href="http://wiki.theory.org/BitTorrentSpecification#Tracker_Request_Parameters">BitTorrent
 * tracker request specification</a>
 */
@Slf4j
public class HttpTrackerClient extends TrackerClient {

  /**
   * Create a new HTTP announcer for the given torrent.
   *
   * @param torrent The torrent we're announcing about.
   * @param peer    Our own peer specification.
   * @param tracker tracker URI
   */
  protected HttpTrackerClient(final SharedTorrent torrent, final Peer peer, final URI tracker) {
    super(torrent, peer, tracker);
  }

  /**
   * Build, send and process a tracker announce request.
   *
   * <p>
   * This function first builds an announce request for the specified event with all the required
   * parameters. Then, the request is made to the tracker and the response analyzed.
   * </p>
   *
   * <p>
   * All registered {@link AnnounceResponseListener} objects are then fired with the decoded
   * payload.
   * </p>
   *
   * @param event         The announce event type (can be AnnounceEvent.NONE for periodic updates).
   * @param inhibitEvents Prevent event listeners from being notified.
   *
   * @throws AnnounceException Unable to announce
   */
  @Override
  public void announce(final AnnounceRequestMessage.RequestEvent event,
                       final boolean inhibitEvents) throws AnnounceException {
    LOG.info("Announcing{} to tracker with {}U/{}D/{}L bytes...",
             this.formatAnnounceEvent(event),
             this.torrent.getUploaded(),
             this.torrent.getDownloaded(),
             this.torrent.getLeft());

    URL target = null;
    try {
      final HttpAnnounceRequestMessage request = this.buildAnnounceRequest(event);
      target = request.buildAnnounceUrl(this.tracker.toURL());
    } catch (final MalformedURLException mue) {
      throw new AnnounceException(String.format("Invalid announce URL (%s)", mue.getMessage()),
                                  mue);
    } catch (final MessageValidationException mve) {

      throw new AnnounceException(String
              .format("Announce request creation violated expected protocol (%s)",
                      mve.getMessage()),
                                  mve);
    } catch (final IOException ioe) {

      throw new AnnounceException(String.format("Error building announce request (%s)",
                                                ioe.getMessage()),
                                  ioe);
    }

    HttpURLConnection connection = null;
    InputStream in = null;
    try {
      LOG.info("Announce with [{}]", target);
      connection = (HttpURLConnection) target.openConnection();
      in = connection.getInputStream();
    } catch (final IOException ioe) {
      if (connection != null) {
        in = connection.getErrorStream();
      }
    }

    // At this point if the input stream is null it means we have neither a
    // response body nor an error stream from the server. No point in going any further.
    if (in == null) {
      throw new AnnounceException("No response or unreachable tracker!");
    }

    try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
      baos.write(in);

      // Parse and handle the response
      final HttpTrackerMessage message = HttpTrackerMessage
              .parse(ByteBuffer.wrap(baos.toByteArray()));
      this.handleTrackerAnnounceResponse(message, inhibitEvents);
    } catch (final IOException ioe) {
      throw new AnnounceException("Error reading tracker response!", ioe);
    } catch (final MessageValidationException mve) {
      throw new AnnounceException(String.format("Tracker message violates expected protocol (%s)",
                                                mve.getMessage()),
                                  mve);
    } finally {
      // Make sure we close everything down at the end to avoid resource leaks.
      try {
        in.close();
      } catch (final IOException ioe) {
        LOG.warn("Problem ensuring error stream closed!", ioe);
      }

      // This means trying to close the error stream as well.
      final InputStream err = connection.getErrorStream();
      if (err != null) {
        try {
          err.close();
        } catch (final IOException ioe) {
          LOG.warn("Problem ensuring error stream closed!", ioe);
        }
      }
    }
  }

  /**
   * Build the announce request tracker message.
   *
   * @param event The announce event (can be <tt>NONE</tt> or <em>null</em>)
   *
   * @return Returns an instance of a {@link HttpAnnounceRequestMessage} that can be used to
   *         generate the fully qualified announce URL, with parameters, to make the announce
   *         request.
   *
   * @throws UnsupportedEncodingException Specified encoding not supported
   * @throws IOException                  Unable to communicate
   * @throws MessageValidationException   Unable to validate message
   */
  private HttpAnnounceRequestMessage buildAnnounceRequest(
          final AnnounceRequestMessage.RequestEvent event)
          throws UnsupportedEncodingException, IOException, MessageValidationException {
    // Build announce request message
    return HttpAnnounceRequestMessage.craft(
            this.torrent.getInfoHash(),
            this.peer.getPeerId().array(),
            this.peer.getPort(),
            this.torrent.getUploaded(),
            this.torrent.getDownloaded(),
            this.torrent.getLeft(),
            true, false, event,
            this.peer.getIp(),
            AnnounceRequestMessage.DEFAULT_NUM_WANT);
  }
}
