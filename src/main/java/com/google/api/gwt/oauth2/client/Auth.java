/*
 * Copyright (C) 2011 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.api.gwt.oauth2.client;

import com.google.gwt.core.client.Callback;
import com.google.gwt.core.client.JavaScriptObject;
import com.google.gwt.core.client.JsArrayString;
import com.google.gwt.core.client.Scheduler;
import com.google.gwt.core.client.Scheduler.ScheduledCommand;

/**
 * Provides methods to manage authentication flow.
 *
 * @author jasonhall@google.com (Jason Hall)
 */
public abstract class Auth {

  /** Instance of the {@link Auth} to use in a GWT application. */
  public static Auth get() {
    return AuthImpl.INSTANCE;
  }

  final TokenStore tokenStore;
  private final Clock clock;
  private final UrlCodex urlCodex;
  final Scheduler scheduler;
  String oauthWindowUrl;

  int height = 600;
  int width = 800;

  Auth(TokenStore tokenStore, Clock clock, UrlCodex urlCodex, Scheduler scheduler,
      String oauthWindowUrl) {
    this.tokenStore = tokenStore;
    this.clock = clock;
    this.urlCodex = urlCodex;
    this.scheduler = scheduler;
    this.oauthWindowUrl = oauthWindowUrl;
  }

  private AuthRequest lastRequest;
  private Callback<String, Throwable> lastCallback;

  private static final double TEN_MINUTES = 10 * 60 * 1000;

  /**
   * Request an access token from an OAuth 2.0 provider.
   *
   * <p>
   * If it can be determined that the user has already granted access, and the
   * token has not yet expired, and that the token will not expire soon, the
   * existing token will be passed to the callback.
   * </p>
   *
   * <p>
   * Otherwise, a popup window will be displayed which may prompt the user to
   * grant access. If the user has already granted access the popup will
   * immediately close and the token will be passed to the callback. If access
   * hasn't been granted, the user will be prompted, and when they grant, the
   * token will be passed to the callback.
   * </p>
   *
   * @param req Request for authentication.
   * @param callback Callback to pass the token to when access has been granted.
   */
  public void login(AuthRequest req, final Callback<String, Throwable> callback) {
    lastRequest = req;
    lastCallback = callback;

    String authUrl = req.toUrl(urlCodex) + "&redirect_uri=" + urlCodex.encode(oauthWindowUrl);

    // Try to look up the token we have stored.
    final TokenInfo info = getToken(req);
    if (info == null || info.expires == null || expiringSoon(info)) {
      // Token wasn't found, or doesn't have an expiration, or is expired or
      // expiring soon. Requesting access will refresh the token.
      doLogin(authUrl, callback);
    } else {
      // Token was found and is good, immediately execute the callback with the
      // access token.

      scheduler.scheduleDeferred(new ScheduledCommand() {
         public void execute() {
          callback.onSuccess(info.accessToken);
        }
      });
    }
  }

  /**
   * Returns whether or not the token will be expiring within the next ten
   * minutes.
   */
  boolean expiringSoon(TokenInfo info) {
    // TODO(jasonhall): Consider varying the definition of "soon" based on the
    // original expires_in value (e.g., "soon" = 1/10th of the total time before
    // it's expired).
    return Double.valueOf(info.expires) < (clock.now() + TEN_MINUTES);
  }

  /**
   * Get the OAuth 2.0 token for which this application may not have already
   * been granted access, by displaying a popup to the user.
   */
  abstract void doLogin(String authUrl, Callback<String, Throwable> callback);

  /**
   * Set the oauth window URL to use to authenticate.
   */
  public Auth setOAuthWindowUrl(String url) {
    this.oauthWindowUrl = url;
    return this;
  }

  /** Sets the height of the OAuth 2.0 popup dialog, in pixels. The default is 600px. */
  public Auth setWindowHeight(int height) {
    this.height = height;
    return this;
  }

  /* Sets the width of the OAuth 2.0 popup dialog, in pixels. The default is 800px. */
  public Auth setWindowWidth(int width) {
    this.width = width;
    return this;
  }

  /**
   * Called by the {@code doLogin()} method which is registered as a global
   * variable on the page.
   */
  // This method is called via a global method defined in AuthImpl.register()
  @SuppressWarnings("unused")
  void finish(String hash) {
    TokenInfo info = new TokenInfo();
    String error = null;
    String errorDesc = "";
    String errorUri = "";

    // Iterate over keys and values in the string hash value to find relevant
    // information like the access token or an error message. The string will be
    // in the form of: #key1=val1&key2=val2&key3=val3 (etc.)
    int idx = 1;
    while (idx < hash.length() - 1) {
      // Grab the next key (between start and '=')
      int nextEq = hash.indexOf('=', idx);
      if (nextEq < 0) {
        break;
      }
      String key = hash.substring(idx, nextEq);

      // Grab the next value (between '=' and '&')
      int nextAmp = hash.indexOf('&', nextEq);
      nextAmp = nextAmp < 0 ? hash.length() : nextAmp;
      String val = hash.substring(nextEq + 1, nextAmp);

      // Start looking from here from now on.
      idx = nextAmp + 1;

      // Store relevant values to be used later.
      if (key.equals("access_token")) {
        info.accessToken = val;
      } else if (key.equals("expires_in")) {
        // expires_in is seconds, convert to milliseconds and add to now
        Double expiresIn = Double.valueOf(val) * 1000;
        info.expires = String.valueOf(clock.now() + expiresIn);
      } else if (key.equals("error")) {
        error = val;
      } else if (key.equals("error_description")) {
        errorDesc = " (" + val + ")";
      } else if (key.equals("error_uri")) {
        errorUri = "; see: " + val;
      }
    }

    if (error != null) {
      lastCallback.onFailure(
          new RuntimeException("Error from provider: " + error + errorDesc + errorUri));
    } else if (info.accessToken == null) {
      lastCallback.onFailure(new RuntimeException("Could not find access_token in hash " + hash));
    } else {
      setToken(lastRequest, info);
      lastCallback.onSuccess(info.accessToken);
    }
  }

  /** Test-compatible abstraction for getting the current time. */
  static interface Clock {
    // Using double to avoid longs in GWT, which are slow.
    double now();
  }

  /** Test-compatible URL encoder/decoder. */
  static interface UrlCodex {
    /**
     * URL-encode a string. This is abstract so that the Auth class can be
     * tested.
     */
    String encode(String url);

    /**
     * URL-decode a string. This is abstract so that the Auth class can be
     * tested.
     */
    String decode(String url);
  }

  TokenInfo getToken(AuthRequest req) {
    String tokenStr = tokenStore.get(req.asString());
    return tokenStr != null ? TokenInfo.fromString(tokenStr) : null;
  }

  void setToken(AuthRequest req, TokenInfo info) {
    tokenStore.set(req.asString(), info.asString());
  }

  /**
   * Clears all tokens stored by this class.
   *
   * <p>
   * This will result in subsequent calls to
   * {@link #login(AuthRequest, Callback)} displaying a popup to the user. If
   * the user has already granted access, that popup will immediately close.
   * </p>
   */
  public void clearAllTokens() {
    tokenStore.clear();
  }

  /** Encapsulates information an access token and when it will expire. */
  static class TokenInfo {
    String accessToken;
    String expires;

    String asString() {
      return accessToken + "-----" + (expires == null ? "" : expires);
    }

    static TokenInfo fromString(String val) {
      String[] parts = val.split("-----");
      TokenInfo info = new TokenInfo();
      info.accessToken = parts[0];
      info.expires = parts.length > 1 ? parts[1] : null;
      return info;
    }
  }

  /*
   * @param req The authentication request of which to request the expiration
   *        status.
   * @return The number of milliseconds until the token expires, or negative
   *         infinity if no token was found.
   */
  public double expiresIn(AuthRequest req) {
    String val = tokenStore.get(req.asString());
    return val == null ? Double.NEGATIVE_INFINITY :
        Double.valueOf(TokenInfo.fromString(val).expires) - clock.now();
  }

  /**
   * Exports a function to the page's global scope that can be called from regular JavaScript.
   *
   * <p>Usage (in JavaScript):</p>
   * <code>
   * oauth2.login({
   *   "authUrl": "..." // the auth URL to use
   *   "clientId": "..." // the client ID for this app
   *   "scopes": ["...", "..."], // (optional) the scopes to request access to
   *   "scopeDelimiter": "..." // (optional) the scope delimiter to use
   * }, function(token) { // (optional) called on success, with the token
   * }, function(error) { // (optional) called on error, with the error message
   * });
   * </code>
   */
  public static native void export() /*-{
    if (!$wnd.oauth2) {
      $wnd.oauth2 = {};
    }
    $wnd.oauth2.login = $entry(function(req, success, failure) {
      @com.google.api.gwt.oauth2.client.Auth::nativeLogin(*)(req, success, failure);
    });

    $wnd.oauth2.expiresIn = $entry(function(req) {
      return @com.google.api.gwt.oauth2.client.Auth::nativeExpiresIn(*)(req);
    });

    $wnd.oauth2.clearAllTokens = $entry(function() {
      @com.google.api.gwt.oauth2.client.Auth::nativeClearAllTokens()();
    });
  }-*/;

  private static void nativeLogin(AuthRequestJso req, JsFunction success, JsFunction failure) {
    AuthImpl.INSTANCE.login(fromJso(req), CallbackWrapper.create(success, failure));
  }

  private static double nativeExpiresIn(AuthRequestJso req) {
    return AuthImpl.INSTANCE.expiresIn(fromJso(req));
  }

  private static void nativeClearAllTokens() {
    AuthImpl.INSTANCE.clearAllTokens();
  }

  private static AuthRequest fromJso(AuthRequestJso jso) {
    return new AuthRequest(jso.getAuthUrl(), jso.getClientId())
        .withScopes(jso.getScopes())
        .withScopeDelimiter(jso.getScopeDelimiter());
  }

  private static final class AuthRequestJso extends JavaScriptObject {
    protected AuthRequestJso() {
    }

    private native String getAuthUrl() /*-{
      return this.authUrl;
    }-*/;

    private native String getClientId() /*-{
      return this.clientId;
    }-*/;

    private native JsArrayString getScopesNative() /*-{
      return this.scopes || [];
    }-*/;

    private String[] getScopes() {
      JsArrayString jsa = getScopesNative();
      String[] arr = new String[jsa.length()];
      for (int i = 0; i < jsa.length(); i++) {
        arr[i] = jsa.get(i);
      }
      return arr;
    }

    private native String getScopeDelimiter() /*-{
      return this.scopeDelimiter || " ";
    }-*/;
  }

  private static final class JsFunction extends JavaScriptObject {
    protected JsFunction() {
    }

    private native void execute(String input) /*-{
      this(input);
    }-*/;
  }

  private static final class CallbackWrapper implements Callback<String, Throwable> {
    private final JsFunction success;
    private final JsFunction failure;

    private CallbackWrapper(JsFunction success, JsFunction failure) {
      this.success = success;
      this.failure = failure;
    }

    private static CallbackWrapper create(JsFunction success, JsFunction failure) {
      return new CallbackWrapper(success, failure);
    }
    public void onSuccess(String result) {
      if (success != null) {
        success.execute(result);
      }
    }
    public void onFailure(Throwable reason) {
      if (failure != null) {
        failure.execute(reason.getMessage());
      }
    }
  }
}
