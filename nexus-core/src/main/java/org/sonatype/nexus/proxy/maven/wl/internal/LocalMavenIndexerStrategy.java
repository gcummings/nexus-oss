/*
 * Sonatype Nexus (TM) Open Source Version
 * Copyright (c) 2007-2012 Sonatype, Inc.
 * All rights reserved. Includes the third-party code listed at http://links.sonatype.com/products/nexus/oss/attributions.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 *
 * Sonatype Nexus (TM) Professional Version is available from Sonatype, Inc. "Sonatype" and "Sonatype Nexus" are trademarks
 * of Sonatype, Inc. Apache Maven is a trademark of the Apache Software Foundation. M2eclipse is a trademark of the
 * Eclipse Foundation. All other trademarks are the property of their respective owners.
 */
package org.sonatype.nexus.proxy.maven.wl.internal;

import static com.google.common.base.Preconditions.checkNotNull;

import java.io.IOException;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.proxy.maven.MavenHostedRepository;
import org.sonatype.nexus.proxy.maven.wl.WLConfig;
import org.sonatype.nexus.proxy.maven.wl.discovery.LocalStrategy;
import org.sonatype.nexus.proxy.maven.wl.discovery.Strategy;
import org.sonatype.nexus.proxy.maven.wl.discovery.StrategyFailedException;
import org.sonatype.nexus.proxy.maven.wl.discovery.StrategyResult;

/**
 * Local {@link Strategy} that uses Maven Indexer data.
 * 
 * @author cstamas
 */
@Named( LocalMavenIndexerStrategy.ID )
@Singleton
public class LocalMavenIndexerStrategy
    extends AbstractStrategy<MavenHostedRepository>
    implements LocalStrategy
{
    protected static final String ID = "maven-indexer";

    private final WLConfig config;

    @Inject
    public LocalMavenIndexerStrategy( final WLConfig config )
    {
        super( ID, 100 );
        this.config = checkNotNull( config );
    }

    @Override
    public StrategyResult discover( MavenHostedRepository mavenRepository )
        throws StrategyFailedException, IOException
    {
        throw new StrategyFailedException( "Not implemented!" );
    }
}
