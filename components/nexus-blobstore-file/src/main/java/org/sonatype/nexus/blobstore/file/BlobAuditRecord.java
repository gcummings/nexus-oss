package org.sonatype.nexus.blobstore.file;

import org.sonatype.nexus.blobstore.api.BlobId;

import com.google.common.base.Preconditions;
import org.joda.time.DateTime;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * @since 3.0
 */
public class BlobAuditRecord
{
  private BlobId blobId;

  private Action action;

  private String blobName;

  private String principal;

  private DateTime dateTime;

  public BlobAuditRecord(final BlobId blobId, final Action action, final String blobName, final String principal,
                         final DateTime dateTime)
  {
    checkNotNull(blobId);
    checkNotNull(action);
    checkNotNull(blobName);
    checkNotNull(principal);
    checkNotNull(dateTime);

    this.blobId = blobId;
    this.action = action;
    this.blobName = blobName;
    this.principal = principal;
    this.dateTime = dateTime;
  }

  private static enum Action
  {
    CREATE, DELETE_REQUEST, PURGE;
  }
}
