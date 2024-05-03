package com.samourai.whirlpool.client.mix.dialog;

import com.samourai.stomp.client.IStompClient;
import com.samourai.stomp.client.IStompTransportListener;
import com.samourai.stomp.client.StompTransport;
import com.samourai.wallet.util.AsyncUtil;
import com.samourai.wallet.util.MessageErrorListener;
import com.samourai.wallet.util.RandomUtil;
import com.samourai.whirlpool.client.exception.NotifiableException;
import com.samourai.whirlpool.client.utils.ClientUtils;
import com.samourai.whirlpool.client.whirlpool.WhirlpoolClientConfig;
import com.samourai.whirlpool.protocol.WhirlpoolEndpoint;
import com.samourai.whirlpool.protocol.WhirlpoolProtocol;
import com.samourai.whirlpool.protocol.websocket.MixMessage;
import com.samourai.whirlpool.protocol.websocket.messages.RegisterInputRequest;
import com.samourai.whirlpool.protocol.websocket.messages.SubscribePoolResponse;
import io.reactivex.Completable;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MixSession {
  // non-static logger to prefix it with stomp sessionId
  private Logger log;

  private MixDialogListener listener;
  private WhirlpoolProtocol whirlpoolProtocol;
  private WhirlpoolClientConfig config;
  private String poolId;
  private StompTransport transport;
  private String logPrefix;
  private boolean done;

  // connect data
  private Long connectBeginTime;

  // session data
  private MixDialog dialog;
  private SubscribePoolResponse subscribePoolResponse;

  public MixSession(
      MixDialogListener listener,
      WhirlpoolProtocol whirlpoolProtocol,
      WhirlpoolClientConfig config,
      String poolId,
      String logPrefix) {
    this.log = LoggerFactory.getLogger(MixSession.class + "[" + logPrefix + "]");
    this.listener = listener;
    this.whirlpoolProtocol = whirlpoolProtocol;
    this.config = config;
    this.poolId = poolId;
    this.transport = null;
    this.logPrefix = logPrefix;
    resetDialog();
  }

  private void resetDialog() {
    if (this.dialog != null) {
      this.dialog.stop();
    }
    this.dialog = new MixDialog(listener, this, config, logPrefix);
    listener.onResetMix();
  }

  public synchronized void connect() {
    if (done) {
      if (log.isDebugEnabled()) {
        log.debug("connect() aborted: done");
      }
      return;
    }
    if (connectBeginTime == null) {
      connectBeginTime = System.currentTimeMillis();
    }

    String wsUrl = config.getServerApi().getWsUrlConnect();
    if (log.isDebugEnabled()) {
      log.debug("connecting to server: " + wsUrl);
    }

    // connect with a new transport
    Map<String, String> connectHeaders = new LinkedHashMap<>();
    IStompClient stompClient = config.getStompClientService().newStompClient();
    transport = new StompTransport(stompClient, computeTransportListener(), logPrefix);
    transport.connect(wsUrl, connectHeaders);
  }

  private void subscribe() {
    // reset session
    subscribePoolResponse = null;

    // subscribe to private queue
    final String privateQueue =
        whirlpoolProtocol.WS_PREFIX_USER_PRIVATE + whirlpoolProtocol.WS_PREFIX_USER_REPLY;
    transport.subscribe(
        computeSubscribeStompHeaders(privateQueue),
        new MessageErrorListener<Object, String>() {
          @Override
          public void onMessage(Object payload) {
            if (done) {
              if (log.isTraceEnabled()) {
                log.trace("onMessage: done");
              }
              return;
            }
            boolean isSubscribePoolResponse =
                SubscribePoolResponse.class.isAssignableFrom(payload.getClass());
            if (subscribePoolResponse == null) {
              if (isSubscribePoolResponse) {
                // 1) input not registered yet => should be a SubscribePoolResponse
                subscribePoolResponse = (SubscribePoolResponse) payload;

                // REGISTER_INPUT
                try {
                  registerInput(subscribePoolResponse);
                } catch (Exception e) {
                  log.error("Unable to register input", e);
                  Exception notifiableException = NotifiableException.computeNotifiableException(e);
                  listener.exitOnProtocolError(notifiableException.getMessage());
                }
              } else {
                String notifiableError =
                    "not a SubscribePoolResponse: " + ClientUtils.toJsonString(payload);
                log.error("--> " + privateQueue + ": " + notifiableError);
                listener.exitOnProtocolError(notifiableError);
              }
            } else {
              if (!isSubscribePoolResponse) {
                // 2) input already registered => should be a MixMessage
                MixMessage mixMessage = checkMixMessage(payload);
                if (mixMessage != null) {
                  dialog.onPrivateReceived(mixMessage);
                } else {
                  String notifiableError = "not a MixMessage: " + ClientUtils.toJsonString(payload);
                  log.error("--> " + privateQueue + ": " + notifiableError);
                  listener.exitOnProtocolError(notifiableError);
                }
              } else {
                // ignore duplicate SubscribePoolResponse
                log.warn(
                    "Ignoring duplicate SubscribePoolResponse: "
                        + ClientUtils.toJsonString(payload));
              }
            }
          }

          @Override
          public void onError(String errorMessage) {
            String notifiableException = "subscribe error: " + errorMessage;
            log.error("--> " + privateQueue + ": " + notifiableException);
            listener.exitOnProtocolError(errorMessage); // subscribe error
          }
        },
        serverProtocolVersion -> {
          // server version mismatch
          listener.exitOnProtocolVersionMismatch(serverProtocolVersion);
        });

    // will automatically receive mixStatus in response of subscription
    if (log.isDebugEnabled()) {
      log.debug("subscribed to server");
    }
  }

  private void registerInput(SubscribePoolResponse subscribePoolResponse) throws Exception {
    RegisterInputRequest registerInputRequest = listener.registerInput(subscribePoolResponse);
    transport.send(WhirlpoolEndpoint.WS_REGISTER_INPUT, registerInputRequest);
  }

  private MixMessage checkMixMessage(Object payload) {
    // should be MixMessage
    Class payloadClass = payload.getClass();
    if (!MixMessage.class.isAssignableFrom(payloadClass)) {
      String notifiableError =
          "unexpected message from server: " + ClientUtils.toJsonString(payloadClass);
      log.error("Protocol error: " + notifiableError);
      listener.exitOnProtocolError(notifiableError);
      return null;
    }

    MixMessage mixMessage = (MixMessage) payload;

    // reset dialog on new mixId
    if (mixMessage.mixId != null
        && dialog.getMixId() != null
        && !dialog
            .getMixId()
            .equals(mixMessage.mixId)) { // mixMessage.mixId is null for ErrorResponse
      if (log.isDebugEnabled()) {
        log.debug("new mixId detected: " + mixMessage.mixId);
      }
      resetDialog();
    }

    return (MixMessage) payload;
  }

  public synchronized void disconnect() {
    if (log.isDebugEnabled()) {
      log.debug("Disconnecting...");
    }
    done = true;
    connectBeginTime = null;
    if (transport != null) {
      transport.disconnect();
    }
    if (log.isDebugEnabled()) {
      log.debug("Disconnected.");
    }
  }

  public void send(String destination, Object message) {
    if (transport != null) {
      transport.send(destination, message);
    } else {
      log.warn("send: ignoring (transport = null)");
    }
  }

  //

  private Map<String, String> computeSubscribeStompHeaders(String destination) {
    Map<String, String> stompHeaders = new HashMap<String, String>();
    stompHeaders.put(WhirlpoolProtocol.HEADER_POOL_ID, poolId);
    if (destination != null) {
      stompHeaders.put(StompTransport.HEADER_DESTINATION, destination);
    }
    return stompHeaders;
  }

  private IStompTransportListener computeTransportListener() {
    return new IStompTransportListener() {

      @Override
      public synchronized void onTransportConnected() {
        if (log.isDebugEnabled()) {
          long elapsedTime = (System.currentTimeMillis() - connectBeginTime) / 1000;
          log.debug("Connected in " + elapsedTime + "s");
        }
        connectBeginTime = null;

        // will get SubscribePoolResponse and start dialog
        subscribe();

        listener.onConnected();
      }

      @Override
      public synchronized void onTransportDisconnected(Throwable exception) {
        // transport cannot be used
        transport = null;

        if (done) {
          if (log.isTraceEnabled()) {
            log.trace("onTransportDisconnected: done");
          }
          return;
        }

        if (log.isDebugEnabled()) {
          log.debug("onTransportDisconnected", exception);
        }
        int reconnectDelay = 0;
        if (connectBeginTime != null) {
          // we were trying connect
          long elapsedTime = (System.currentTimeMillis() - connectBeginTime) / 1000;

          // change Tor circuit
          config.getTorClientService().changeIdentity();

          // wait delay before retrying
          int randomDelaySeconds = RandomUtil.getInstance().random(5, 120);
          reconnectDelay = randomDelaySeconds * 1000;
          log.info(
              " ! connexion failed since "
                  + elapsedTime
                  + "s, retrying in "
                  + reconnectDelay
                  + "s");
          listener.onConnectionFailWillRetry(reconnectDelay);
        } else {
          // we just got disconnected
          log.error(" ! connexion lost, reconnecting for a new mix...");
          resetDialog();
          listener.onConnectionLostWillRetry();
        }

        if (done) {
          if (log.isDebugEnabled()) {
            log.debug("onTransportDisconnected: done");
          }
          return;
        }

        if (reconnectDelay > 0) {
          waitAndReconnectAsync(reconnectDelay).subscribe();
        } else {
          // reconnect now
          connect();
        }
      }
    };
  }

  protected Completable waitAndReconnectAsync(final int reconnectDelay) {
    // reconnect after delay
    return AsyncUtil.getInstance()
        .runIOAsyncCompletable(
            () -> {
              synchronized (this) {
                try {
                  wait(reconnectDelay);
                } catch (InterruptedException e) {
                }
              }
              connect();
            });
  }

  //
  protected StompTransport __getTransport() {
    return transport;
  }
}
