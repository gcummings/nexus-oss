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
package org.sonatype.nexus.blobstore.file;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Random;

import javax.inject.Inject;

import org.sonatype.nexus.blobstore.api.Blob;
import org.sonatype.nexus.blobstore.api.BlobMetrics;
import org.sonatype.nexus.blobstore.api.BlobStoreMetrics;
import org.sonatype.nexus.blobstore.file.guice.FileBlobStoreModule;
import org.sonatype.nexus.configuration.application.ApplicationDirectories;
import org.sonatype.nexus.configuration.application.ApplicationDirectoriesImpl;
import org.sonatype.sisu.litmus.testsupport.TestSupport;

import com.google.common.collect.ImmutableMap;
import com.google.common.io.ByteStreams;
import com.google.common.io.Files;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Provider;
import org.apache.commons.io.IOUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.sonatype.nexus.blobstore.api.BlobStore.AUDIT_INFO_HEADER;
import static org.sonatype.nexus.blobstore.api.BlobStore.BLOB_NAME_HEADER;


/**
 * @since 3.0
 */
public class FileBlobStoreIT
    extends TestSupport
{
  public static final int TEST_DATA_LENGTH = 10_000;

  public static final ImmutableMap<String, String> TEST_HEADERS = ImmutableMap
      .of(AUDIT_INFO_HEADER, "test", BLOB_NAME_HEADER, "test/randomData.bin");

  private Injector injector;

  @Inject
  private FileBlobStore blobStore;

  @Inject
  private BlobMetadataStore metadataStore;

  @Before
  public void init() throws Exception {
    injector = Guice
        .createInjector(new FileBlobStoreModule(), new TestApplicationDirectoriesProvider());

    injector.injectMembers(this);

    metadataStore.start();
  }

  @After
  public void shutdown() throws Exception {
    if (metadataStore != null) {
      metadataStore.stop();
    }
  }

  @Test
  public void basicSmokeTest() throws Exception {

    final byte[] content = new byte[TEST_DATA_LENGTH];
    new Random().nextBytes(content);

    final Blob blob = blobStore.create(new ByteArrayInputStream(content), TEST_HEADERS);

    final byte[] output = IOUtils.toByteArray(blob.getInputStream());
    assertThat("data must survive", content, is(equalTo(output)));

    final BlobMetrics metrics = blob.getMetrics();
    assertThat("size must be calculated correctly", metrics.getContentSize(), is(equalTo((long) TEST_DATA_LENGTH)));

    final boolean deleted = blobStore.delete(blob.getId());
    assertThat(deleted, is(equalTo(true)));

    final Blob deletedBlob = blobStore.get(blob.getId());
    assertThat(deletedBlob, is(nullValue()));


    final BlobStoreMetrics storeMetrics = blobStore.getMetrics();
    assertThat(storeMetrics.getBlobCount(), is(equalTo(1L)));
    assertThat(storeMetrics.getTotalSize(), is(equalTo((long) TEST_DATA_LENGTH)));
    assertThat(storeMetrics.getAvailableSpace(), is(greaterThan(0L)));
  }

  @Test
  public void hardDeletePreventsGetDespiteOpenStreams() throws Exception {
    final byte[] content = new byte[TEST_DATA_LENGTH];
    new Random().nextBytes(content);

    final Blob blob = blobStore.create(new ByteArrayInputStream(content), TEST_HEADERS);

    final InputStream inputStream = blob.getInputStream();

    // Read half the data
    inputStream.read(new byte[content.length / 2]);

    // force delete
    blobStore.deleteHard(blob.getId());

    final Blob newBlob = blobStore.get(blob.getId());
    assertThat(newBlob, is(nullValue()));

    // read the rest of the bytes and see what happens
    final byte[] remainingBytes = ByteStreams.toByteArray(inputStream);
    System.err.println(remainingBytes);
    inputStream.close();
  }

  /**
   * A simple Guice module that provides a stub ApplicationDirectories implementation that points to temp directories.
   */
  class TestApplicationDirectoriesProvider
      extends AbstractModule
  {
    @Override
    protected void configure() {
      bind(ApplicationDirectories.class).toProvider(new Provider<ApplicationDirectories>()
      {
        @Override
        public ApplicationDirectories get() {
          return new ApplicationDirectoriesImpl(null, Files.createTempDir(), Files.createTempDir());
        }
      });

      bind(FilePathPolicy.class).toProvider(new Provider<FilePathPolicy>()
      {
        @Override
        public FilePathPolicy get() {
          return new HashingSubdirFileLocationPolicy(Files.createTempDir().toPath());
        }
      });
    }
  }

}
