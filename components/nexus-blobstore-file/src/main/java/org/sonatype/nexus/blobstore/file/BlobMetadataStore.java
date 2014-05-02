package org.sonatype.nexus.blobstore.file;

import javax.annotation.Nullable;

import org.sonatype.nexus.blobstore.api.BlobId;
import org.sonatype.nexus.blobstore.api.BlobMetrics;
import org.sonatype.nexus.blobstore.api.BlobStoreMetrics;

/**
 * @since 3.0
 */
public interface BlobMetadataStore
{
  void add(BlobMetadata metadata);

  @Nullable
  BlobMetadata get(BlobId blobId);

  void update(BlobMetadata metadata);

  void delete(BlobId blobId);

  BlobStoreMetrics getBlobStoreMetrics();
}
