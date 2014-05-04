package org.sonatype.nexus.apachehttpclient;

import java.io.IOException;

import org.sonatype.nexus.apachehttpclient.Page.PageContext;
import org.sonatype.nexus.proxy.repository.ProxyRepository;

import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.protocol.HttpContext;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * A context of page requests made on behalf of a Repository.
 */
public class RepositoryPageContext
    extends PageContext
{
  private final ProxyRepository proxyRepository;

  public RepositoryPageContext(final HttpClient httpClient, final ProxyRepository proxyRepository) {
    super(httpClient);
    this.proxyRepository = checkNotNull(proxyRepository);
  }

  protected ProxyRepository getProxyRepository() {
    return proxyRepository;
  }

  /**
   * Equips context with repository.
   */
  @Override
  public HttpContext createHttpContext(final HttpUriRequest httpRequest)
      throws IOException
  {
    final HttpContext httpContext = super.createHttpContext(httpRequest);
    httpContext.setAttribute(Hc4Provider.HTTP_CTX_KEY_REPOSITORY, getProxyRepository());
    return httpContext;
  }
}
