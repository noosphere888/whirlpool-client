package com.samourai.stomp.client;

import com.samourai.wallet.util.MessageErrorListener;
import com.samourai.wallet.util.MessageListener;
import com.samourai.whirlpool.client.utils.ClientUtils;
import com.samourai.whirlpool.protocol.WhirlpoolProtocol;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** STOMP communication. */
public class StompTransport {
  // non-static logger to prefix it with stomp sessionId
  private Logger log;
  public static final String HEADER_DESTINATION = "destination";

  private IStompClient stompClient;
  private IStompTransportListener listener;

  private boolean done;

  public StompTransport(
      IStompClient stompClient, IStompTransportListener listener, String logPrefix) {
    this.log = LoggerFactory.getLogger(StompTransport.class + "[" + logPrefix + "]");
    this.stompClient = stompClient;
    this.listener = listener;
    this.done = false;
  }

  public synchronized void connect(String wsUrl, Map<String, String> connectHeaders) {
    if (done) {
      if (log.isDebugEnabled()) {
        log.debug("connect() aborted: done");
      }
      return;
    }
    stompClient.connect(
        wsUrl,
        connectHeaders,
        new MessageErrorListener<Void, Throwable>() {
          // onConnect
          @Override
          public void onMessage(Void foo) {
            if (!done) {
              listener.onTransportConnected();
            } /* else {
                if (log.isDebugEnabled()) {
                  log.debug("onMessage: message ignored (done=true)");
                }
              }*/
          }

          // onDisconnect
          @Override
          public void onError(Throwable exception) {
            if (!done) {
              disconnect();
              listener.onTransportDisconnected(exception);
            } /* else {
                if (log.isDebugEnabled()) {
                  log.debug("onError: message ignored (done=true)");
                }
              }*/
          }
        });
  }

  public void subscribe(
      Map<String, String> subscribeHeaders,
      final MessageErrorListener<Object, String> listener,
      final MessageListener<String> serverVersionMismatchListener) {
    if (log.isDebugEnabled()) {
      log.debug("subscribe:" + subscribeHeaders.get(HEADER_DESTINATION));
    }
    final Map<String, String> completeHeaders = completeHeaders(subscribeHeaders);

    MessageErrorListener<IStompMessage, String> onMessageOnErrorListener =
        new MessageErrorListener<IStompMessage, String>() {
          @Override
          public void onMessage(IStompMessage stompMessage) {
            Object payload = stompMessage.getPayload();
            if (!done) {
              if (log.isDebugEnabled()) {
                log.debug(
                    "--> ("
                        + completeHeaders.get(HEADER_DESTINATION)
                        + ") "
                        + ClientUtils.toJsonString(payload));
              }

              // check protocol version
              String protocolVersion =
                  stompMessage.getStompHeader(WhirlpoolProtocol.HEADER_PROTOCOL_VERSION);
              if (protocolVersion == null
                  || !WhirlpoolProtocol.PROTOCOL_VERSION.equals(protocolVersion)) {
                serverVersionMismatchListener.onMessage(
                    protocolVersion != null ? protocolVersion : "unknown");
                return;
              }
              listener.onMessage(payload);
            } else {
              log.warn(
                  "frame ignored (done) ("
                      + completeHeaders.get(HEADER_DESTINATION)
                      + "): "
                      + ClientUtils.toJsonString(payload));
            }
          }

          @Override
          public void onError(String error) {
            listener.onError(error);
          }
        };

    if (done) {
      return;
    }
    stompClient.subscribe(subscribeHeaders, onMessageOnErrorListener);
  }

  public synchronized void disconnect() {
    if (log.isDebugEnabled()) {
      log.debug("disconnect");
    }
    this.done = true;
    stompClient.disconnect();
  }

  // STOMP communication

  public void send(String destination, Object message) {
    Map<String, String> stompHeaders = new HashMap<String, String>();
    stompHeaders.put(HEADER_DESTINATION, destination);
    stompHeaders = completeHeaders(stompHeaders);
    if (log.isDebugEnabled()) {
      log.debug("send: " + destination + ":" + ClientUtils.toJsonString(message));
    }

    if (done) {
      return;
    }
    stompClient.send(stompHeaders, message);
  }

  private Map<String, String> completeHeaders(Map<String, String> stompHeaders) {
    stompHeaders.put(WhirlpoolProtocol.HEADER_PROTOCOL_VERSION, WhirlpoolProtocol.PROTOCOL_VERSION);
    return stompHeaders;
  }
}
