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

import org.sonatype.nexus.blobstore.api.BlobId;

import org.joda.time.DateTime;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * An entry in the audit log, which records significant events in the file blob store.
 *
 * @since 3.0
 */
public class BlobAuditEntry
{
  private static enum Action
  {
    CREATE, DELETE_REQUEST, PURGE;
  }

  private BlobId blobId;

  private Action action;

  private String blobName;

  private String blobStoreName;

  private String principal;

  private DateTime dateTime;

  public BlobAuditEntry(final String blobStoreName, final BlobId blobId, final Action action, final String blobName,
                        final String principal,
                        final DateTime dateTime)
  {
    checkNotNull(blobStoreName);
    checkNotNull(blobId);
    checkNotNull(action);
    checkNotNull(blobName);
    checkNotNull(principal);
    checkNotNull(dateTime);

    this.blobStoreName = blobStoreName;
    this.blobId = blobId;
    this.action = action;
    this.blobName = blobName;
    this.principal = principal;
    this.dateTime = dateTime;
  }

  public String toString() {
    return dateTime.getMillis() + " : " + blobStoreName + "/" + blobId + " " + action + " by " + principal;
  }
}
