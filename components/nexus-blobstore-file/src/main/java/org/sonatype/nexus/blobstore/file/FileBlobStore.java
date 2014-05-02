package org.sonatype.nexus.blobstore.file;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Map;

import javax.annotation.Nullable;

import org.sonatype.nexus.blobstore.api.Blob;
import org.sonatype.nexus.blobstore.api.BlobId;
import org.sonatype.nexus.blobstore.api.BlobMetrics;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreException;
import org.sonatype.nexus.blobstore.api.BlobStoreListener;
import org.sonatype.nexus.blobstore.api.BlobStoreMetrics;
import org.sonatype.nexus.blobstore.id.BlobIdFactory;

import com.google.common.base.Preconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

/**
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

  @Override
  public Blob create(final InputStream blobData, final Map<String, String> headers) {
    checkNotNull(blobData);
    checkNotNull(headers);

    final BlobId blobId = blobIdFactory.createBlobId();

    Preconditions.checkArgument(headers.containsKey(BLOB_NAME_HEADER));
    Preconditions.checkArgument(headers.containsKey(AUDIT_INFO_HEADER));

    try {
      // Create a record of the attempt to store the blob, so if it fails we know we need to clean up
      final BlobMetadata metadata = new BlobMetadata(blobId, headers);
      metadataStore.add(metadata);

      logger.debug("Writing blob {} to {}", blobId, paths.forContent(blobId));

      BlobMetrics metrics = storeBlob(blobId, blobData);

      final FileBlob blob = new FileBlob(blobId, headers, paths.forContent(blobId), metrics);
      if (listener != null) {
        listener.blobCreated(blob, "Blob " + blobId + " written to " + paths.forContent(blobId));
      }

      metadata.setMetrics(metrics);
      metadata.setBlobMarkedForDeletion(false);
      metadataStore.update(metadata);

      return blob;
    }
    catch (IOException e) {
      throw new BlobStoreException(e, getName(), blobId);
    }
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
    return metadataStore.getBlobStoreMetrics();
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

  private BlobMetrics storeBlob(final BlobId blobId, final InputStream blobData) throws IOException {
    BlobMetrics metrics = null;

    // write the content to disk
    fileOperations.create(paths.forHeader(blobId), blobData);
    return metrics;
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
