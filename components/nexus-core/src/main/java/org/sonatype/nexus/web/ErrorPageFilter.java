/*
 * Sonatype Nexus (TM) Open Source Version
 * Copyright (c) 2007-2013 Sonatype, Inc.
 * All rights reserved. Includes the third-party code listed at http://links.sonatype.com/products/nexus/oss/attributions.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 *
 * Sonatype Nexus (TM) Professional Version is available from Sonatype, Inc. "Sonatype" and "Sonatype Nexus" are trademarks
 * of Sonatype, Inc. Apache Maven is a trademark of the Apache Software Foundation. M2eclipse is a trademark of the
 * Eclipse Foundation. All other trademarks are the property of their respective owners.
 */

package org.sonatype.nexus.web;

import java.io.IOException;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.sonatype.sisu.goodies.common.ComponentSupport;

import static com.google.common.base.Preconditions.checkNotNull;
import static javax.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;

/**
 * Servlet filter to add error page rendering.
 *
 * @since 2.8.0
 */
@Named
@Singleton
public class ErrorPageFilter
    extends ComponentSupport
    implements Filter
{
  private final Renderer renderer;

  @Inject
  public ErrorPageFilter(final Renderer renderer) {
    this.renderer = checkNotNull(renderer);
  }

  @Override
  public void init(final FilterConfig config) throws ServletException {
    // ignore
  }

  @Override
  public void destroy() {
    // ignore
  }

  @Override
  public void doFilter(final ServletRequest req, final ServletResponse resp, final FilterChain chain)
      throws IOException, ServletException
  {
    final HttpServletRequest request = (HttpServletRequest) req;
    final HttpServletResponse response = (HttpServletResponse) resp;
    try {
      chain.doFilter(req, response);
    }
    catch (ErrorStatusServletException e) {
      // send for direct rendering, everything is prepared
      renderer.renderErrorPage(
          request,
          response,
          e.getResponseCode(),
          e.getReasonPhrase(),
          e.getErrorDescription(),
          e.getRootCause()
      );
    }
    catch (IOException e) {
      // IOException handling, do not leak information nor render error page
      response.setStatus(SC_INTERNAL_SERVER_ERROR);
    }
    catch (Exception e) {
      // runtime and servlet exceptions will end here (thrown probably by some non-nexus filter or servlet)
      log.warn("Unexpected exception", e);
      renderer.renderErrorPage(request, response, SC_INTERNAL_SERVER_ERROR, null /*default*/, e.getMessage(), e);
    }
  }
}