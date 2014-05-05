/*
 * Sonatype Nexus (TM) Open Source Version
 * Copyright (c) 2007-2014 Sonatype, Inc.
 * All rights reserved. Includes the third-party code listed at http://links.sonatype.com/products/nexus/oss/attributions.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 *
 * Sonatype Nexus (TM) Professional Version is available from Sonatype, Inc. "Sonatype" and "Sonatype Nexus" are trademarks
 * of Sonatype, Inc. Apache Maven is a trademark of the Apache Software Foundation. M2eclipse is a trademark of the
 * Eclipse Foundation. All other trademarks are the property of their respective owners.
 */

package org.sonatype.nexus.apachehttpclient;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.sonatype.nexus.proxy.events.NexusStoppedEvent;
import org.sonatype.nexus.proxy.repository.ClientSSLRemoteAuthenticationSettings;
import org.sonatype.nexus.proxy.repository.NtlmRemoteAuthenticationSettings;
import org.sonatype.nexus.proxy.repository.RemoteAuthenticationSettings;
import org.sonatype.nexus.proxy.repository.RemoteProxySettings;
import org.sonatype.nexus.proxy.repository.UsernamePasswordRemoteAuthenticationSettings;
import org.sonatype.nexus.proxy.storage.remote.RemoteStorageContext;
import org.sonatype.nexus.proxy.utils.UserAgentBuilder;
import org.sonatype.nexus.util.SystemPropertiesHelper;
import org.sonatype.sisu.goodies.common.ComponentSupport;
import org.sonatype.sisu.goodies.eventbus.EventBus;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.eventbus.Subscribe;
import com.google.common.primitives.Ints;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.Credentials;
import org.apache.http.auth.NTCredentials;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.AuthSchemes;
import org.apache.http.client.config.CookieSpecs;
import org.apache.http.client.protocol.ResponseContentEncoding;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.HttpClientConnectionManager;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.impl.NoConnectionReuseStrategy;
import org.apache.http.impl.client.StandardHttpRequestRetryHandler;
import org.apache.http.impl.conn.DefaultSchemePortResolver;
import org.apache.http.impl.conn.PoolingClientConnectionManager;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Default implementation of {@link Hc4Provider}.
 *
 * @since 2.2
 */
@Singleton
@Named
public class Hc4ProviderImpl
    extends ComponentSupport
    implements Hc4Provider
{
  /**
   * Key for customizing default (and max) keep alive duration when remote server does not state anything, or states
   * some unreal high value. Value is milliseconds.
   */
  private static final String KEEP_ALIVE_MAX_DURATION_KEY = "nexus.apacheHttpClient4x.keepAliveMaxDuration";

  /**
   * Default keep alive max duration: 30 seconds.
   */
  private static final long KEEP_ALIVE_MAX_DURATION_DEFAULT = TimeUnit.SECONDS.toMillis(30);

  /**
   * Key for customizing connection pool maximum size. Value should be integer equal to 0 or greater. Pool size of 0
   * will actually prevent use of pool. Any positive number means the actual size of the pool to be created. This is
   * a
   * hard limit, connection pool will never contain more than this count of open sockets.
   */
  private static final String CONNECTION_POOL_MAX_SIZE_KEY = "nexus.apacheHttpClient4x.connectionPoolMaxSize";

  /**
   * Default pool max size: 200.
   */
  private static final int CONNECTION_POOL_MAX_SIZE_DEFAULT = 200;

  /**
   * Key for customizing connection pool size per route (usually per-repository, but not quite in case of Mirrors).
   * Value should be integer equal to 0 or greater. Pool size of 0 will actually prevent use of pool. Any positive
   * number means the actual size of the pool to be created.
   */
  private static final String CONNECTION_POOL_SIZE_KEY = "nexus.apacheHttpClient4x.connectionPoolSize";

  /**
   * Default pool size: 20.
   */
  private static final int CONNECTION_POOL_SIZE_DEFAULT = 20;

  /**
   * Key for customizing connection pool idle time. In other words, how long open connections (sockets) are kept in
   * pool idle (unused) before they get evicted and closed. Value is milliseconds.
   */
  private static final String CONNECTION_POOL_IDLE_TIME_KEY = "nexus.apacheHttpClient4x.connectionPoolIdleTime";

  /**
   * Default pool idle time: 30 seconds.
   */
  private static final long CONNECTION_POOL_IDLE_TIME_DEFAULT = TimeUnit.SECONDS.toMillis(30);

  /**
   * Key for customizing connection pool timeout. In other words, how long should a HTTP request execution be blocked
   * when pool is depleted, for a connection. Value is milliseconds.
   */
  private static final String CONNECTION_POOL_TIMEOUT_KEY = "nexus.apacheHttpClient4x.connectionPoolTimeout";

  /**
   * Default pool timeout: 30 seconds.
   */
  private static final long CONNECTION_POOL_TIMEOUT_DEFAULT = TimeUnit.SECONDS.toMillis(30);

  // ==

  private final Provider<RemoteStorageContext> globalRemoteStorageContextProvider;

  /**
   * UA builder component.
   */
  private final UserAgentBuilder userAgentBuilder;

  /**
   * The low level core event bus.
   */
  private final EventBus eventBus;

  /**
   * Shared client connection manager.
   */
  private final ManagedClientConnectionManager sharedConnectionManager;

  /**
   * Thread evicting idle open connections from {@link #sharedConnectionManager}.
   */
  private final EvictingThread evictingThread;

  /**
   * Used to install created {@link PoolingClientConnectionManager} into jmx.
   */
  private final PoolingClientConnectionManagerMBeanInstaller jmxInstaller;

  @Inject
  public Hc4ProviderImpl(final @Named("global") Provider<RemoteStorageContext> globalRemoteStorageContextProvider,
                         final UserAgentBuilder userAgentBuilder,
                         final EventBus eventBus,
                         final PoolingClientConnectionManagerMBeanInstaller jmxInstaller,
                         final List<SSLContextSelector> selectors)
  {
    this.globalRemoteStorageContextProvider = checkNotNull(globalRemoteStorageContextProvider);
    this.userAgentBuilder = checkNotNull(userAgentBuilder);
    this.jmxInstaller = checkNotNull(jmxInstaller);
    this.sharedConnectionManager = createClientConnectionManager(selectors);
    this.evictingThread = new EvictingThread(sharedConnectionManager, getConnectionPoolIdleTime());
    this.evictingThread.start();
    this.eventBus = checkNotNull(eventBus);
    this.eventBus.register(this);
    this.jmxInstaller.register(sharedConnectionManager);

    log.info(
        "Started (connectionPoolMaxSize {}, connectionPoolSize {}, connectionPoolIdleTime {} ms, connectionPoolTimeout {} ms, keepAliveMaxDuration {} ms)",
        getConnectionPoolMaxSize(),
        getConnectionPoolSize(),
        getConnectionPoolIdleTime(),
        getConnectionPoolTimeout(),
        getKeepAliveMaxDuration()
    );
  }

  // configuration

  /**
   * Returns the pool max size.
   */
  protected int getConnectionPoolMaxSize() {
    return SystemPropertiesHelper.getInteger(CONNECTION_POOL_MAX_SIZE_KEY, CONNECTION_POOL_MAX_SIZE_DEFAULT);
  }

  /**
   * Returns the pool size per route.
   */
  protected int getConnectionPoolSize() {
    return SystemPropertiesHelper.getInteger(CONNECTION_POOL_SIZE_KEY, CONNECTION_POOL_SIZE_DEFAULT);
  }

  /**
   * Returns the connection pool idle (idle as unused but pooled) time in milliseconds.
   */
  protected long getConnectionPoolIdleTime() {
    return SystemPropertiesHelper.getLong(CONNECTION_POOL_IDLE_TIME_KEY, CONNECTION_POOL_IDLE_TIME_DEFAULT);
  }

  /**
   * Returns the pool timeout in milliseconds.
   */
  protected long getConnectionPoolTimeout() {
    return SystemPropertiesHelper.getLong(CONNECTION_POOL_TIMEOUT_KEY, CONNECTION_POOL_TIMEOUT_DEFAULT);
  }

  // ==

  /**
   * Performs a clean shutdown on this component, it kills the evicting thread and shuts down the shared connection
   * manager. Multiple invocation of this method is safe, it will not do anything.
   */
  public synchronized void shutdown() {
    evictingThread.interrupt();
    jmxInstaller.unregister();
    sharedConnectionManager._shutdown();
    eventBus.unregister(this);
    log.info("Stopped");
  }

  @Subscribe
  public void onEvent(final NexusStoppedEvent evt) {
    shutdown();
  }

  // ==

  /**
   * Safety net to prevent thread leaks (in non-production environment, mainly for ITs or UTs).
   */
  @Override
  protected void finalize() throws Throwable {
    try {
      shutdown();
    }
    finally {
      super.finalize();
    }
  }

  // == Hc4Provider API

  @Override
  public HttpClient createHttpClient() {
    // connection manager will cap the max count of connections, but with this below
    // we get rid of pooling. Pooling is used in Proxy repositories only, as all other
    // components using the "shared" httpClient should not produce hiw rate of requests
    // anyway, as they usually happen per user interactions (GPG gets keys are staging repo is closed, if not cached
    // yet, LVO gets info when UI's main window is loaded into user's browser, etc
    // ==
    // NEXUS-6220: This story above is mainly true and is basically "resource optimization", as
    // this method is used by various "side services" (typically LVO etc), where single request is made
    // with huge pauses in between. Still, connection reuse is needed in some rare cases,
    // like when you have NTLM proxy in between Nexus and the Internet. So, let ask HC4 provider,
    // does it "think" we still need connection reuse or not.
    return createHttpClient(reuseConnectionsNeeded(globalRemoteStorageContextProvider.get()));
  }

  @Override
  public HttpClient createHttpClient(final boolean reuseConnections) {
    final Builder builder = prepareHttpClient(globalRemoteStorageContextProvider.get());
    if (!reuseConnections) {
      builder.getHttpClientBuilder().setConnectionReuseStrategy(new NoConnectionReuseStrategy());
    }
    return builder.build();
  }

  @Override
  public HttpClient createHttpClient(final RemoteStorageContext context) {
    return prepareHttpClient(context).build();
  }

  @Override
  public Builder prepareHttpClient(final RemoteStorageContext context) {
    return prepareHttpClient(context, sharedConnectionManager);
  }

  // ==

  protected void applyConfig(final Builder builder, final RemoteStorageContext context) {
    builder.getSocketConfigBuilder().setSoTimeout(getSoTimeout(context));

    builder.getConnectionConfigBuilder().setBufferSize(8 * 1024);

    builder.getRequestConfigBuilder().setCookieSpec(CookieSpecs.IGNORE_COOKIES);
    builder.getRequestConfigBuilder().setExpectContinueEnabled(false);
    builder.getRequestConfigBuilder().setStaleConnectionCheckEnabled(false);
    builder.getRequestConfigBuilder().setConnectTimeout(getConnectionTimeout(context));
    builder.getRequestConfigBuilder().setSocketTimeout(getSoTimeout(context));

    builder.getHttpClientBuilder().setUserAgent(userAgentBuilder.formatUserAgentString(context));

    builder.getRequestConfigBuilder().setConnectionRequestTimeout(Ints.checkedCast(getConnectionPoolTimeout()));
  }

  protected ManagedClientConnectionManager createClientConnectionManager(final List<SSLContextSelector> selectors)
      throws IllegalStateException
  {
    final Registry<ConnectionSocketFactory> registry = RegistryBuilder.<ConnectionSocketFactory>create()
        .register("http", PlainConnectionSocketFactory.getSocketFactory())
        .register("https", new NexusSSLConnectionSocketFactory(
            (javax.net.ssl.SSLSocketFactory) javax.net.ssl.SSLSocketFactory.getDefault(),
            SSLConnectionSocketFactory.BROWSER_COMPATIBLE_HOSTNAME_VERIFIER, selectors)).build();

    final ManagedClientConnectionManager connManager = new ManagedClientConnectionManager(registry);
    final int maxConnectionCount = getConnectionPoolMaxSize();
    final int perRouteConnectionCount = Math.min(getConnectionPoolSize(), maxConnectionCount);

    connManager.setMaxTotal(maxConnectionCount);
    connManager.setDefaultMaxPerRoute(perRouteConnectionCount);

    return connManager;
  }

  private class ManagedClientConnectionManager
      extends PoolingHttpClientConnectionManager
  {
    public ManagedClientConnectionManager(final Registry<ConnectionSocketFactory> schemeRegistry) {
      super(schemeRegistry);
    }

    @Override
    public void shutdown() {
      // do nothing in order to avoid unwanted shutdown of shared connection manager
    }

    private void _shutdown() {
      super.shutdown();
    }
  }

  //
  // TODO: Extracted From Hc4ProviderBase, cleanup and organize
  //

  // ==

  private Builder prepareHttpClient(final RemoteStorageContext context,
                                    final HttpClientConnectionManager httpClientConnectionManager)
  {
    final Builder builder = new Builder();
    builder.getHttpClientBuilder().setConnectionManager(httpClientConnectionManager);
    builder.getHttpClientBuilder().addInterceptorFirst(new ResponseContentEncoding());
    applyConfig(builder, context);
    applyAuthenticationConfig(builder, context.getRemoteAuthenticationSettings(), null);
    applyProxyConfig(builder, context.getRemoteProxySettings());
    // obey the given retries count and apply it to client.
    final int retries =
        context.getRemoteConnectionSettings() != null
            ? context.getRemoteConnectionSettings().getRetrievalRetryCount()
            : 0;
    builder.getHttpClientBuilder().setRetryHandler(new StandardHttpRequestRetryHandler(retries, false));
    builder.getHttpClientBuilder()
        .setKeepAliveStrategy(new NexusConnectionKeepAliveStrategy(getKeepAliveMaxDuration()));
    return builder;
  }

  /**
   * Returns the maximum Keep-Alive duration in milliseconds.
   */
  private long getKeepAliveMaxDuration() {
    return SystemPropertiesHelper.getLong(KEEP_ALIVE_MAX_DURATION_KEY, KEEP_ALIVE_MAX_DURATION_DEFAULT);
  }

  /**
   * Returns the connection timeout in milliseconds. The timeout until connection is established.
   */
  private int getConnectionTimeout(final RemoteStorageContext context) {
    if (context.getRemoteConnectionSettings() != null) {
      return context.getRemoteConnectionSettings().getConnectionTimeout();
    }
    else {
      // see DefaultRemoteConnectionSetting
      return 1000;
    }
  }

  /**
   * Returns the SO_SOCKET timeout in milliseconds. The timeout for waiting for data on established connection.
   */
  private int getSoTimeout(final RemoteStorageContext context) {
    // this parameter is actually set from #getConnectionTimeout
    return getConnectionTimeout(context);
  }

  // ==

  /**
   * Returns {@code true} if passed in {@link RemoteStorageContext} contains some configuration element that
   * does require connection reuse (typically remote NTLM authentication or proxy with NTLM authentication set).
   *
   * @param context the remote storage context to test for need of reused connections.
   * @return {@code true} if connection reuse is required according to remote storage context.
   * @since 2.7.2
   */
  @VisibleForTesting
  boolean reuseConnectionsNeeded(final RemoteStorageContext context) {
    // return true if any of the auth is NTLM based, as NTLM must have keep-alive to work
    if (context != null) {
      if (context.getRemoteAuthenticationSettings() instanceof NtlmRemoteAuthenticationSettings) {
        return true;
      }
      if (context.getRemoteProxySettings() != null) {
        if (context.getRemoteProxySettings().getHttpProxySettings() != null &&
            context.getRemoteProxySettings().getHttpProxySettings()
                .getProxyAuthentication() instanceof NtlmRemoteAuthenticationSettings) {
          return true;
        }
        if (context.getRemoteProxySettings().getHttpsProxySettings() != null &&
            context.getRemoteProxySettings().getHttpsProxySettings()
                .getProxyAuthentication() instanceof NtlmRemoteAuthenticationSettings) {
          return true;
        }
      }
    }
    return false;
  }

  @VisibleForTesting
  void applyAuthenticationConfig(final Builder builder,
                                 final RemoteAuthenticationSettings ras,
                                 final HttpHost proxyHost)
  {
    if (ras != null) {
      String authScope = "target";
      if (proxyHost != null) {
        authScope = proxyHost.toHostString() + " proxy";
      }

      final List<String> authorisationPreference = Lists.newArrayListWithExpectedSize(3);
      authorisationPreference.add(AuthSchemes.DIGEST);
      authorisationPreference.add(AuthSchemes.BASIC);
      Credentials credentials = null;
      if (ras instanceof ClientSSLRemoteAuthenticationSettings) {
        throw new IllegalArgumentException("SSL client authentication not yet supported!");
      }
      else if (ras instanceof NtlmRemoteAuthenticationSettings) {
        final NtlmRemoteAuthenticationSettings nras = (NtlmRemoteAuthenticationSettings) ras;
        // Using NTLM auth, adding it as first in policies
        authorisationPreference.add(0, AuthSchemes.NTLM);
        log.debug("{} authentication setup for NTLM domain '{}'", authScope, nras.getNtlmDomain());
        credentials = new NTCredentials(
            nras.getUsername(), nras.getPassword(), nras.getNtlmHost(), nras.getNtlmDomain()
        );
      }
      else if (ras instanceof UsernamePasswordRemoteAuthenticationSettings) {
        final UsernamePasswordRemoteAuthenticationSettings uras =
            (UsernamePasswordRemoteAuthenticationSettings) ras;
        log.debug("{} authentication setup for remote storage with username '{}'", authScope,
            uras.getUsername());
        credentials = new UsernamePasswordCredentials(uras.getUsername(), uras.getPassword());
      }

      if (credentials != null) {
        if (proxyHost != null) {
          builder.setCredentials(new AuthScope(proxyHost), credentials);
          builder.getRequestConfigBuilder().setProxyPreferredAuthSchemes(authorisationPreference);
        }
        else {
          builder.setCredentials(AuthScope.ANY, credentials);
          builder.getRequestConfigBuilder().setTargetPreferredAuthSchemes(authorisationPreference);
        }
      }
    }
  }

  /**
   * @since 2.6
   */
  @VisibleForTesting
  void applyProxyConfig(final Builder builder, final RemoteProxySettings remoteProxySettings) {
    if (remoteProxySettings != null
        && remoteProxySettings.getHttpProxySettings() != null
        && remoteProxySettings.getHttpProxySettings().isEnabled()) {
      final Map<String, HttpHost> proxies = Maps.newHashMap();

      final HttpHost httpProxy = new HttpHost(
          remoteProxySettings.getHttpProxySettings().getHostname(),
          remoteProxySettings.getHttpProxySettings().getPort()
      );
      applyAuthenticationConfig(
          builder, remoteProxySettings.getHttpProxySettings().getProxyAuthentication(), httpProxy
      );

      log.debug(
          "http proxy setup with host '{}'", remoteProxySettings.getHttpProxySettings().getHostname()
      );
      proxies.put("http", httpProxy);
      proxies.put("https", httpProxy);

      if (remoteProxySettings.getHttpsProxySettings() != null
          && remoteProxySettings.getHttpsProxySettings().isEnabled()) {
        final HttpHost httpsProxy = new HttpHost(
            remoteProxySettings.getHttpsProxySettings().getHostname(),
            remoteProxySettings.getHttpsProxySettings().getPort()
        );
        applyAuthenticationConfig(
            builder, remoteProxySettings.getHttpsProxySettings().getProxyAuthentication(), httpsProxy
        );
        log.debug(
            "https proxy setup with host '{}'", remoteProxySettings.getHttpsProxySettings().getHostname()
        );
        proxies.put("https", httpsProxy);
      }

      final Set<Pattern> nonProxyHostPatterns = Sets.newHashSet();
      if (remoteProxySettings.getNonProxyHosts() != null && !remoteProxySettings.getNonProxyHosts().isEmpty()) {
        for (String nonProxyHostRegex : remoteProxySettings.getNonProxyHosts()) {
          try {
            nonProxyHostPatterns.add(Pattern.compile(nonProxyHostRegex, Pattern.CASE_INSENSITIVE));
          }
          catch (PatternSyntaxException e) {
            log.warn("Invalid non proxy host regex: {}", nonProxyHostRegex, e);
          }
        }
      }

      builder.getHttpClientBuilder().setRoutePlanner(
          new NexusHttpRoutePlanner(
              proxies, nonProxyHostPatterns, DefaultSchemePortResolver.INSTANCE
          )
      );
    }
  }
}
