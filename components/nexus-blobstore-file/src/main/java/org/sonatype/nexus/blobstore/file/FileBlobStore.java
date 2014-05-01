package org.sonatype.nexus.blobstore.file;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Map;


import javax.annotation.Nullable;

import org.sonatype.nexus.blobstore.api.Blob;
import org.sonatype.nexus.blobstore.api.BlobId;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreException;
import org.sonatype.nexus.blobstore.api.BlobStoreListener;
import org.sonatype.nexus.blobstore.api.BlobStoreMetrics;
import org.sonatype.nexus.blobstore.id.BlobIdFactory;

import com.google.common.base.Preconditions;

import io.kazuki.v0.store.keyvalue.KeyValueStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * @since 3.0
 */
public class FileBlobStore
    implements BlobStore
{
  private static final Logger logger = LoggerFactory.getLogger(FileBlobStore.class);

  private BlobStoreListener listener;

  private BlobIdFactory blobIdFactory;

  private Path dataDirectory;

  private FilePathPolicy paths;

  private FileOperations fileOperations;

  private HeaderFileFormat headerFormat;


  private KeyValueStore metadataStore;


  @Override
  public Blob create(final InputStream blobData, final Map<String, String> headers) {
    Preconditions.checkNotNull(blobData);
    Preconditions.checkNotNull(headers);

    final BlobId blobId = blobIdFactory.createBlobId();

    // TODO: validate the headers

    try {

      // TODO: This isn't atomic, meaning that content might be stored without headers.
      // Maybe write out a 'remove' order that gets deleted at the end of this method?
      // When the blob store starts, any outstanding remove orders trigger file deletions.

      logger.debug("Writing blob {} to {}", blobId, paths.forContent(blobId));

      // write the headers to disk
      fileOperations.create(paths.forHeader(blobId), toInputStream(headers));
      // write the content to disk
      System.err.println("Writing blob " + paths.forContent(blobId));
      fileOperations.create(paths.forHeader(blobId), blobData);

      final FileBlob blob = new FileBlob(blobId, paths.forContent(blobId), paths.forHeader(blobId));
      if (listener != null) {
        listener.blobCreated(blob, "Blob " + blobId + " written to " + paths.forContent(blobId));
      }

      return blob;
    }
    catch (IOException e) {
      throw new BlobStoreException(e);
    }
  }

  @Nullable
  @Override
  public Blob get(final BlobId blobId) {
    return null;
  }

  @Override
  public boolean delete(final BlobId blobId) {
    return false;
  }

  @Override
  public boolean deleteHard(final BlobId blobId) {
    return false;
  }

  @Override
  public String getName() {
    return null;
  }

  @Override
  public BlobStoreMetrics getMetrics() {
    return null;
  }

  @Override
  public void setBlobStoreListener(@Nullable final BlobStoreListener listener) {
   this.listener=listener;
  }

  @Nullable
  @Override
  public void getBlobStoreListener() {
return listener;
  }
}
