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
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;

import com.turn.ttorrent.bcodec.BeEncoder;
import com.turn.ttorrent.bcodec.BeValue;
import com.turn.ttorrent.common.protocol.TrackerMessage.AnnounceRequestMessage;
import com.turn.ttorrent.common.protocol.TrackerMessage.ErrorMessage;
import com.turn.ttorrent.common.protocol.TrackerMessage.MessageValidationException;
import com.turn.ttorrent.common.protocol.http.HttpAnnounceRequestMessage;
import com.turn.ttorrent.common.protocol.http.HttpAnnounceResponseMessage;
import com.turn.ttorrent.common.protocol.http.HttpTrackerErrorMessage;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.simpleframework.http.Request;
import org.simpleframework.http.Response;
import org.simpleframework.http.Status;
import org.simpleframework.http.core.Container;

/**
 * Tracker service to serve the tracker's announce requests.
 *
 * <p>
 * It only serves announce requests on /announce, and only serves torrents the {@link Tracker} it
 * serves knows about.
 * </p>
 *
 * <p>
 * The list of torrents {@link #torrents} is a map of torrent hashes to their corresponding Torrent
 * objects, and is maintained by the {@link Tracker} this service is part of. The TrackerService
 * only has a reference to this map, and does not modify it.
 * </p>
 *
 * @author mpetazzoni
 * @see <a href="http://wiki.theory.org/BitTorrentSpecification">BitTorrent protocol
 * specification</a>
 */
@Slf4j
public class TrackerService implements Container {

  /**
   * Default server name and version announced by the tracker.
   */
  public static final String DEFAULT_VERSION_STRING = "BitTorrent Tracker (ttorrent)";

  /**
   * The list of announce request URL fields that need to be interpreted as numeric and thus
   * converted as such in the request message parsing.
   */
  private static final String[] NUMERIC_REQUEST_FIELDS = new String[]{
    "port", "uploaded", "downloaded", "left", "compact", "no_peer_id", "numwant"
  };

  protected final String version;

  @Getter
  protected ConcurrentMap<String, TrackedTorrent> torrents;

  /**
   * Create a new TrackerService serving the given torrents.
   *
   * @param torrents The torrents this TrackerService should serve requests for.
   */
  TrackerService(final ConcurrentMap<String, TrackedTorrent> torrents) {
    this.version = DEFAULT_VERSION_STRING;
    this.torrents = torrents;
  }

  /**
   * Handle the incoming request on the tracker service.
   *
   * <p>
   * This makes sure the request is made to the tracker's announce URL, and delegates handling of
   * the request to the <em>process()</em> method after preparing the response object.
   * </p>
   *
   * @param request  The incoming HTTP request.
   * @param response The response object.
   */
  @Override
  public void handle(final Request request, final Response response) {
    // Reject non-announce requests
    if (!Tracker.ANNOUNCE_URL.equals(request.getPath().toString())) {
      response.setCode(404);
      response.setText("Not Found");
      return;
    }

    OutputStream body = null;
    try {
      body = response.getOutputStream();
      this.process(request, response, body);
      body.flush();
    } catch (final IOException ioe) {
      LOG.warn("Error while writing response: {}!", ioe.getMessage());
    } finally {
      IOUtils.closeQuietly(body);
    }
  }

  /**
   * Process the announce request.
   *
   * <p>
   * This method attemps to read and parse the incoming announce request into an announce request
   * message, then creates the appropriate announce response message and sends it back to the
   * client.
   * </p>
   *
   * @param request  The incoming announce request.
   * @param response The response object.
   * @param body     The validated response body output stream.
   *
   * @throws IOException Unable to parse query
   */
  protected void process(final Request request,
                         final Response response,
                         final OutputStream body) throws IOException {
    // Prepare the response headers.
    response.set("Content-Type", "text/plain");
    response.set("Server", this.version);
    response.setDate("Date", System.currentTimeMillis());

    /*
     * Parse the query parameters into an announce request message.
     * We need to rely on our own query parsing function because SimpleHTTP's Query map will contain
     * UTF-8 decoded parameters, which doesn't work well for the byte-encoded strings we expect.
     */
    final HttpAnnounceRequestMessage announceRequest;
    try {
      announceRequest = this.parseQuery(request);
    } catch (final MessageValidationException mve) {
      this.serveError(response, body, Status.BAD_REQUEST, mve.getMessage());
      return;
    }

    final TrackedTorrent torrent = this.torrents.get(announceRequest.getHexInfoHash());
    if (torrent == null) { // The requested torrent must be announced by the tracker.
      LOG.warn("Requested torrent hash was: {}", announceRequest.getHexInfoHash());
      this.serveError(response, body, Status.BAD_REQUEST,
                      ErrorMessage.FailureReason.UNKNOWN_TORRENT);
      return;
    }

    AnnounceRequestMessage.RequestEvent event = announceRequest.getEvent();
    final String peerId = announceRequest.getHexPeerId();

    //When no event is specified, it's a periodic update while the client is operating.
    //If we don't have a peer for this announce, it means the tracker restarted while the client
    // was running. Consider this announce request as a 'started' event.
    if ((event == null || AnnounceRequestMessage.RequestEvent.NONE.equals(event))
        && torrent.getPeer(peerId) == null) {
      event = AnnounceRequestMessage.RequestEvent.STARTED;
    }

    // If an event other than 'started' is specified and we also haven't seen the peer on
    // this torrent before, something went wrong.
    // A previous 'started' announce request should have been made by the client that would have
    // had us register that peer on the torrent this request refers to.
    if (event != null && torrent.getPeer(peerId) == null
        && !AnnounceRequestMessage.RequestEvent.STARTED.equals(event)) {
      this.serveError(response, body, Status.BAD_REQUEST, ErrorMessage.FailureReason.INVALID_EVENT);
      return;
    }

    // Update the torrent according to the announce event
    final TrackedPeer peer;
    try {
      peer = torrent.update(event,
                            ByteBuffer.wrap(announceRequest.getPeerId()),
                            announceRequest.getHexPeerId(),
                            announceRequest.getIp(),
                            announceRequest.getPort(),
                            announceRequest.getUploaded(),
                            announceRequest.getDownloaded(),
                            announceRequest.getLeft());
    } catch (final IllegalArgumentException iae) {
      this.serveError(response, body, Status.BAD_REQUEST, ErrorMessage.FailureReason.INVALID_EVENT);
      return;
    }

    // Craft and output the answer
    final HttpAnnounceResponseMessage announceResponse;
    try {
      announceResponse = HttpAnnounceResponseMessage.craft(
              torrent.getAnnounceInterval(),
              TrackedTorrent.MIN_ANNOUNCE_INTERVAL_SECONDS,
              this.version,
              torrent.seeders(),
              torrent.leechers(),
              torrent.getSomePeers(peer));
      final WritableByteChannel channel = Channels.newChannel(body);
      channel.write(announceResponse.getData());
    } catch (final IOException e) {
      this.serveError(response, body, Status.INTERNAL_SERVER_ERROR, e.getMessage());
    }
  }

  /**
   * Parse the query parameters using our defined BYTE_ENCODING.
   *
   * <p>
   * Because we're expecting byte-encoded strings as query parameters, we can't rely on SimpleHTTP's
   * QueryParser which uses the wrong encoding for the job and returns us unparsable byte data. We
   * thus have to implement our own little parsing method that uses BYTE_ENCODING to decode
   * parameters from the URI.
   * </p>
   *
   * <p>
   * <b>Note:</b> array parameters are not supported. If a key is present multiple times in the URI,
   * the latest value prevails. We don't really need to implement this functionality as this never
   * happens in the Tracker HTTP protocol.
   * </p>
   *
   * @param request The request's full URI, including query parameters.
   *
   * @return The {@link AnnounceRequestMessage} representing the client's announce request.
   */
  private HttpAnnounceRequestMessage parseQuery(final Request request)
          throws IOException, MessageValidationException {
    final Map<String, BeValue> params = new HashMap<>();

    try {
      final String uri = request.getAddress().toString();
      for (String pair : uri.split("[?]")[1].split("&")) {
        final String[] keyval = pair.split("[=]", 2);
        this.recordParam(params, keyval[0], keyval.length == 1 ? null : keyval[1]);
      }
    } catch (final ArrayIndexOutOfBoundsException e) {
      params.clear();
    }

    // Make sure we have the peer IP, fallbacking on the request's source
    // address if the peer didn't provide it.
    if (params.get("ip") == null) {
      params.put("ip", new BeValue(request.getClientAddress().getAddress().getHostAddress(),
                                   TrackedTorrent.BYTE_ENCODING));
    }

    return HttpAnnounceRequestMessage.parse(BeEncoder.bencode(params));
  }

  private void recordParam(final Map<String, BeValue> params,
                           final String key,
                           String value) {
    try {
      value = URLDecoder.decode(value, TrackedTorrent.BYTE_ENCODING);

      for (String f : NUMERIC_REQUEST_FIELDS) {
        if (f.equals(key)) {
          params.put(key, new BeValue(Long.valueOf(value)));
          return;
        }
      }

      params.put(key, new BeValue(value, TrackedTorrent.BYTE_ENCODING));
    } catch (final UnsupportedEncodingException uee) {
      // Ignore, act like parameter was not there
      LOG.error("", uee);
    }
  }

  /**
   * Write a {@link HttpTrackerErrorMessage} to the response with the given HTTP status code.
   *
   * @param response The HTTP response object.
   * @param body     The response output stream to write to.
   * @param status   The HTTP status code to return.
   * @param error    The error reported by the tracker.
   */
  private void serveError(final Response response,
                          final OutputStream body,
                          final Status status,
                          final HttpTrackerErrorMessage error) throws IOException {
    response.setCode(status.getCode());
    response.setText(status.getDescription());
    LOG.warn("Could not process announce request ({}) !", error.getReason());

    final WritableByteChannel channel = Channels.newChannel(body);
    channel.write(error.getData());
  }

  /**
   * Write an error message to the response with the given HTTP status code.
   *
   * @param response The HTTP response object.
   * @param body     The response output stream to write to.
   * @param status   The HTTP status code to return.
   * @param error    The error message reported by the tracker.
   */
  private void serveError(final Response response,
                          final OutputStream body,
                          final Status status,
                          final String error) throws IOException {
    try {
      this.serveError(response, body, status, HttpTrackerErrorMessage.craft(error));
    } catch (final MessageValidationException mve) {
      LOG.warn("Could not craft tracker error message!", mve);
    }
  }

  /**
   * Write a tracker failure reason code to the response with the given HTTP status code.
   *
   * @param response The HTTP response object.
   * @param body     The response output stream to write to.
   * @param status   The HTTP status code to return.
   * @param reason   The failure reason reported by the tracker.
   */
  private void serveError(final Response response,
                          final OutputStream body,
                          final Status status,
                          final ErrorMessage.FailureReason reason) throws IOException {
    this.serveError(response, body, status, reason.getMessage());
  }
}
