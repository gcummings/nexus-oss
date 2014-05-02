package org.sonatype.nexus.blobstore.file;

/**
 * @since 3.0
 */
public interface BlobAuditStore
{
  void add(BlobAuditRecord auditRecord);
}
