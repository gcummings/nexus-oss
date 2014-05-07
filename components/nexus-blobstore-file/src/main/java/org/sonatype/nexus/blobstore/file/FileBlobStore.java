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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.util.Map;

import javax.annotation.Nullable;
import javax.inject.Inject;

import org.sonatype.nexus.blobstore.api.Blob;
import org.sonatype.nexus.blobstore.api.BlobId;
import org.sonatype.nexus.blobstore.api.BlobMetrics;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreException;
import org.sonatype.nexus.blobstore.api.BlobStoreListener;
import org.sonatype.nexus.blobstore.api.BlobStoreMetrics;
import org.sonatype.nexus.blobstore.id.BlobIdFactory;

import com.google.common.base.Preconditions;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * A {@link BlobStore} that stores its content on the file system, and metadata in a {@link BlobMetadataStore}.
 *
 * @since 3.0
 */
public class FileBlobStore
    implements BlobStore
{
  private static final Logger logger = LoggerFactory.getLogger(FileBlobStore.class);

  private String name;

  private BlobStoreListener listener;

  private BlobIdFactory blobIdFactory;

  private FilePathPolicy paths;

  private FileOperations fileOperations;

  private BlobMetadataStore metadataStore;

  @Inject
  public FileBlobStore(final String name, final BlobIdFactory blobIdFactory, final FilePathPolicy paths,
                       final FileOperations fileOperations, final BlobMetadataStore metadataStore)
  {
    this.name = checkNotNull(name);
    this.blobIdFactory = checkNotNull(blobIdFactory);
    this.paths = checkNotNull(paths);
    this.fileOperations = checkNotNull(fileOperations);
    this.metadataStore = checkNotNull(metadataStore);
  }

  @Override
  public Blob create(final InputStream blobData, final Map<String, String> headers) {
    checkNotNull(blobData);
    checkNotNull(headers);

    final BlobId blobId = blobIdFactory.createBlobId();

    Preconditions.checkArgument(headers.containsKey(BLOB_NAME_HEADER));
    Preconditions.checkArgument(headers.containsKey(AUDIT_INFO_HEADER));

    try {
      // If the storing of bytes fails, we record a reminder to clean up afterwards
      final BlobMetadata metadata = new BlobMetadata(blobId, headers);
      metadata.setBlobMarkedForDeletion(true);
      metadataStore.add(metadata);

      logger.debug("Writing blob {} to {}", blobId, paths.forContent(blobId));

      final StreamMetrics streamMetrics = storeBlob(blobId, blobData);

      BlobMetrics metrics = createBlobMetrics(streamMetrics);

      final FileBlob blob = new FileBlob(blobId, headers, paths.forContent(blobId), metrics);
      if (listener != null) {
        listener.blobCreated(blob, "Blob " + blobId + " written to " + paths.forContent(blobId));
      }

      metadata.setMetrics(metrics);
      // Storing the content went fine, so we can now unmark this for deletion
      metadata.setBlobMarkedForDeletion(false);
      metadataStore.update(metadata);

      return blob;
    }
    catch (IOException e) {
      throw new BlobStoreException(e, getName(), blobId);
    }
  }

  private BlobMetrics createBlobMetrics(final StreamMetrics streamMetrics) {
    checkNotNull(streamMetrics);
    final DateTime creationTime = new DateTime();

    return new BlobMetrics(creationTime, streamMetrics.getSHA1(), streamMetrics.getSize());
  }

  @Nullable
  @Override
  public Blob get(final BlobId blobId) {
    checkNotNull(blobId);

    BlobMetadata metadata = metadataStore.get(blobId);
    if (metadata == null) {
      logger.debug("Attempt to access non-existent blob {}", blobId);
      return null;
    }

    if (metadata.isBlobMarkedForDeletion()) {
      logger.debug("Attempt to access blob scheduled for deletion {}", blobId);
      return null;
    }

    if (!fileOperations.exists(paths.forContent(blobId))) {
      logger.error("Blob content for blob {} not found at expected location {}", blobId, paths.forContent(blobId));
      throw new BlobStoreException("Blob content unexpectedly missing.", getName(), blobId);
    }

    final FileBlob blob = new FileBlob(blobId, metadata.getHeaders(), paths.forContent(blobId), metadata.getMetrics());

    logger.debug("Accessing blob {}", blobId);
    if (listener != null) {
      listener.blobAccessed(blob, null);
    }
    return blob;
  }

  @Override
  public boolean delete(final BlobId blobId) {
    checkNotNull(blobId);

    BlobMetadata metadata = metadataStore.get(blobId);
    if (metadata == null) {
      logger.debug("Attempt to mark-for-delete non-existent blob {}", blobId);
      return false;
    }
    else if (metadata.isBlobMarkedForDeletion()) {
      logger.debug("Attempt to mark-for-delete blob already marked for deletion {}", blobId);
      return false;
    }

    metadata.setBlobMarkedForDeletion(true);
    // TODO: Handle concurrent modification of metadata
    metadataStore.update(metadata);
    return true;
  }

  @Override
  public boolean deleteHard(final BlobId blobId) {
    checkNotNull(blobId);

    BlobMetadata metadata = metadataStore.get(blobId);
    if (metadata == null) {
      logger.debug("Attempt to deleteHard non-existent blob {}", blobId);
      return false;
    }

    try {
      final boolean blobDeleted = fileOperations.delete(paths.forContent(blobId));

      if (!blobDeleted) {
        logger.error("Deleting blob {} : content file was missing.", blobId);
      }

      logger.debug("Deleting-hard blob {}", blobId);

      if (listener != null) {
        listener.blobDeleted(blobId, "Path:" + paths.forContent(blobId).toAbsolutePath());
      }

      metadataStore.delete(blobId);

      return blobDeleted;
    }
    catch (IOException e) {
      throw new BlobStoreException(e, getName(), blobId);
    }
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public BlobStoreMetrics getMetrics() {

    final MetadataMetrics metadataMetrics = metadataStore.getMetadataMetrics();

    return new BlobStoreMetrics()
    {
      @Override
      public long getBlobCount() {
        return metadataMetrics.getBlobCount();
      }

      @Override
      public long getTotalSize() {
        return metadataMetrics.getTotalSize();
      }

      @Override
      public long getAvailableSpace() {
        try {
          final FileStore fileStore = Files.getFileStore(paths.getRoot());
          return fileStore.getUsableSpace();
        }
        catch (IOException e) {
          throw new BlobStoreException(e, getName(), null);
        }
      }
    };
  }

  @Override
  public void setBlobStoreListener(@Nullable final BlobStoreListener listener) {
    this.listener = listener;
  }

  @Nullable
  @Override
  public BlobStoreListener getBlobStoreListener() {
    return listener;
  }

  private StreamMetrics storeBlob(final BlobId blobId, final InputStream blobData) throws IOException {
    try {
      // write the content to disk
      return fileOperations.create(paths.forContent(blobId), blobData);
    }
    catch (NoSuchAlgorithmException e) {
      throw new BlobStoreException(e, getName(), blobId);
    }
  }

  private void checkExists(final Path path, BlobId blobId) throws IOException {
    if (!fileOperations.exists(path)) {
      // I'm not completely happy with this, since it means that blob store clients can get a blob, be satisfied
      // that it exists, and then discover that it doesn't, mid-operation
      throw new BlobStoreException("Blob has been deleted.", getName(), blobId);
    }
  }


  class FileBlob
      implements Blob
  {
    private BlobId blobId;

    private Map<String, String> headers;

    private Path contentPath;

    private BlobMetrics metrics;

    FileBlob(final BlobId blobId, final Map<String, String> headers, final Path contentPath,
             final BlobMetrics metrics)
    {
      checkNotNull(blobId);
      checkNotNull(headers);
      checkNotNull(contentPath);
      checkNotNull(metrics);

      this.blobId = blobId;
      this.headers = headers;
      this.contentPath = contentPath;
      this.metrics = metrics;
    }

    @Override
    public BlobId getId() {
      return blobId;
    }

    @Override
    public Map<String, String> getHeaders() {
      return headers;
    }

    @Override
    public InputStream getInputStream() {
      try {
        checkExists(contentPath, blobId);
        return fileOperations.openInputStream(contentPath);
      }
      catch (IOException e) {
        throw new BlobStoreException(e, getName(), blobId);
      }
    }

    @Override
    public BlobMetrics getMetrics() {
      return metrics;
    }
  }

}