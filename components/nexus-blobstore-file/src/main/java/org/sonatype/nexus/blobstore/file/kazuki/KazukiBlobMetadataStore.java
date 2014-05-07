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
package org.sonatype.nexus.blobstore.file.kazuki;

import java.util.Arrays;
import java.util.Map;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;

import org.sonatype.nexus.blobstore.api.BlobId;
import org.sonatype.nexus.blobstore.api.BlobMetrics;
import org.sonatype.nexus.blobstore.api.BlobStoreException;
import org.sonatype.nexus.blobstore.file.BlobMetadata;
import org.sonatype.nexus.blobstore.file.BlobMetadataStore;
import org.sonatype.nexus.blobstore.file.MetadataMetrics;
import org.sonatype.sisu.goodies.lifecycle.LifecycleSupport;

import com.google.common.collect.ImmutableMap;
import io.kazuki.v0.store.KazukiException;
import io.kazuki.v0.store.Key;
import io.kazuki.v0.store.index.SecondaryIndexStore;
import io.kazuki.v0.store.index.UniqueEntityDescription;
import io.kazuki.v0.store.index.query.ValueHolder;
import io.kazuki.v0.store.index.query.ValueType;
import io.kazuki.v0.store.keyvalue.KeyValueIterable;
import io.kazuki.v0.store.keyvalue.KeyValueStore;
import io.kazuki.v0.store.keyvalue.KeyValueStoreIteration.SortDirection;
import io.kazuki.v0.store.lifecycle.Lifecycle;
import io.kazuki.v0.store.schema.SchemaStore;
import io.kazuki.v0.store.schema.TypeValidation;
import io.kazuki.v0.store.schema.model.Attribute.Type;
import io.kazuki.v0.store.schema.model.AttributeTransform;
import io.kazuki.v0.store.schema.model.IndexAttribute;
import io.kazuki.v0.store.schema.model.Schema;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.Arrays.asList;

/**
 * A Kazuki-backed implementation of the {@link BlobMetadataStore}.
 *
 * @since 3.0
 */
public class KazukiBlobMetadataStore
    extends LifecycleSupport
    implements BlobMetadataStore
{
  public static final String BLOB_ID_INDEX = "uniqueBlobIdIndex";

  public static final String METADATA_TYPE = "fileblobstore.metadata";

  private Lifecycle lifecycle;

  private KeyValueStore kvStore;

  private SchemaStore schemaStore;

  private Schema metadataSchema;

  private SecondaryIndexStore secondaryIndexStore;

  // TODO: These injections imply that there is only one metadata store
  @Inject
  public KazukiBlobMetadataStore(final @Named("fileblobstore") Lifecycle lifecycle,
                                 @Named("fileblobstore") final KeyValueStore kvStore,
                                 @Named("fileblobstore") final SchemaStore schemaStore,
                                 @Named("fileblobstore") final SecondaryIndexStore secondaryIndexStore)
  {
    this.lifecycle = checkNotNull(lifecycle, "lifecycle");
    this.kvStore = checkNotNull(kvStore, "key value store");
    this.schemaStore = checkNotNull(schemaStore, "schema store");
    this.secondaryIndexStore = checkNotNull(secondaryIndexStore, "secondary index store");
  }

  @Override
  protected void doStart() throws Exception {
    lifecycle.init();
    lifecycle.start();

    if (schemaStore.retrieveSchema(METADATA_TYPE) == null) {
      Schema schema = new Schema.Builder()
          .addAttribute("blobId", Type.UTF8_SMALLSTRING, false)
          .addAttribute("blobMarkedForDeletion", Type.BOOLEAN, false)
          .addAttribute("headers", Type.MAP, false)
          .addAttribute("creationTime", Type.UTC_DATE_SECS, true)
          .addAttribute("sha1Hash", Type.UTF8_SMALLSTRING, true)
          .addAttribute("contentSize", Type.I64, false)
          .addIndex(BLOB_ID_INDEX,
              asList(new IndexAttribute("blobId", SortDirection.ASCENDING, AttributeTransform.NONE)), true)
          .build();

      log.info("Creating schema for file blob metadata");

      schemaStore.createSchema(METADATA_TYPE, schema);
      this.metadataSchema = schema;
    }
  }

  @Override
  protected void doStop() throws Exception {
    lifecycle.stop();
    lifecycle.shutdown();
  }

  @Override
  public void add(final BlobMetadata metadata) {
    final FlatBlobMetadata flat = flatten(metadata);
    System.err.println(flat);
    try {
      final Key key = kvStore
          .create(METADATA_TYPE, FlatBlobMetadata.class, flat, TypeValidation.STRICT);
      log.debug("Adding metadata for blob {} with KZ key {}", metadata.getBlobId(), key);
    }
    catch (KazukiException e) {
      throw new BlobStoreException(e, "unknown", metadata.getBlobId());
    }
  }

  @Nullable
  @Override
  public BlobMetadata get(final BlobId blobId) {
    try {
      final Key key = findKey(blobId);
      if (key == null) {
        return null;
      }
      return expand(findMetadata(key));
    }
    catch (KazukiException e) {
      throw new BlobStoreException(e, "unknown", blobId);
    }
  }

  @Override
  public void update(final BlobMetadata metadata) {
    Key key = findKey(metadata.getBlobId());
    try {
      final FlatBlobMetadata flat = flatten(metadata);
      System.err.println("About to update with" + flat);
      kvStore.update(key, FlatBlobMetadata.class, flat);
    }
    catch (KazukiException e) {
      throw new BlobStoreException(e, "unknown", metadata.getBlobId());
    }
  }

  @Override
  public void delete(final BlobId blobId) {
    try {
      kvStore.delete(findKey(blobId));
    }
    catch (KazukiException e) {
      throw new BlobStoreException(e, "unknown", blobId);
    }
  }

  @Override
  public MetadataMetrics getMetadataMetrics() {

    // TODO: Replace this brute force approach with a counter
    // TODO: Replace the statistics object with kazuki's eventual support for counters

    final KeyValueIterable<FlatBlobMetadata> values = kvStore.iterators()
        .values(METADATA_TYPE, FlatBlobMetadata.class, SortDirection.ASCENDING);
    long totalSize = 0;
    long blobCount = 0;
    for (FlatBlobMetadata metadata : values) {
      if (metadata == null) {
        // Concurrent modification can cause objects in an iterator to return null.
        continue;
      }
      blobCount++;
      totalSize += metadata.getContentSize();
    }

    return new MetadataMetrics(blobCount, totalSize);
  }

  private Key findKey(final BlobId blobId) {
    final UniqueEntityDescription<FlatBlobMetadata> blobQuery = new UniqueEntityDescription<>(
        METADATA_TYPE, FlatBlobMetadata.class, BLOB_ID_INDEX, metadataSchema,
        ImmutableMap.of("blobId", new ValueHolder(ValueType.STRING, blobId.getId())));

    final Map<UniqueEntityDescription, Key> keyMap = secondaryIndexStore
        .multiRetrieveUniqueKeys(Arrays.<UniqueEntityDescription>asList(blobQuery));

    final boolean keyFound = keyMap.containsKey(blobQuery);
    if (!keyFound) {
      throw new BlobStoreException("Metadata not found.", "unknown", blobId);
    }

    return keyMap.get(blobQuery);
  }

  public FlatBlobMetadata findMetadata(Key key) throws KazukiException {
    checkNotNull(key);
    return kvStore.retrieve(key, FlatBlobMetadata.class);
  }

  private FlatBlobMetadata flatten(final BlobMetadata metadata) {
    final FlatBlobMetadata flat = new FlatBlobMetadata();

    flat.setBlobId(metadata.getBlobId().getId());
    flat.setBlobMarkedForDeletion(metadata.isBlobMarkedForDeletion());
    final BlobMetrics metrics = metadata.getMetrics();
    if (metrics != null) {
      flat.setSha1Hash(metrics.getSHA1Hash());
      flat.setContentSize(metrics.getContentSize());

      // TODO: Uncomment this once the kazuki bug for date handling is fixed
      //flat.setCreationTime(metrics.getCreationTime());
    }
    flat.setHeaders(metadata.getHeaders());
    return flat;
  }

  private BlobMetadata expand(final FlatBlobMetadata flat) {
    final BlobMetadata metadata = new BlobMetadata(new BlobId(flat.getBlobId()), flat.getHeaders());
    metadata.setMetrics(new BlobMetrics(flat.getCreationTime(), flat.getSha1Hash(), flat.getContentSize()));
    metadata.setBlobMarkedForDeletion(flat.isBlobMarkedForDeletion());
    return metadata;
  }
}
