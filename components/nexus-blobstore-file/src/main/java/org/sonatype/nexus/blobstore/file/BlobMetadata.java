package org.sonatype.nexus.blobstore.file;

import java.util.Map;

import org.sonatype.nexus.blobstore.api.BlobId;
import org.sonatype.nexus.blobstore.api.BlobMetrics;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * @since 3.0
 */
public class BlobMetadata
{
  private BlobId blobId;

  private boolean blobMarkedForDeletion;

  private Map<String, String> headers;

  private BlobMetrics metrics;

  public BlobMetadata(final BlobId blobId, final Map<String, String> headers) {
    checkNotNull(blobId);
    checkNotNull(headers);

    this.blobId = blobId;
    this.headers = headers;
  }


  public boolean isBlobMarkedForDeletion() {
    return blobMarkedForDeletion;
  }


  public void setBlobMarkedForDeletion(final boolean blobMarkedForDeletion) {
    this.blobMarkedForDeletion = blobMarkedForDeletion;
  }

  public Map<String, String> getHeaders() {
    return headers;
  }

  public void setMetrics(final BlobMetrics metrics) {
    this.metrics = metrics;
  }

  public BlobMetrics getMetrics() {
    return metrics;
  }
}
