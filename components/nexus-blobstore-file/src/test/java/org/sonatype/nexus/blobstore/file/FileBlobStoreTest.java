
package org.sonatype.nexus.blobstore.file;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.HashMap;

import org.sonatype.nexus.blobstore.api.Blob;
import org.sonatype.nexus.blobstore.api.BlobId;
import org.sonatype.nexus.blobstore.api.BlobMetrics;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.id.BlobIdFactory;
import org.sonatype.sisu.litmus.testsupport.TestSupport;

import com.google.common.collect.ImmutableMap;
import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Test;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class FileBlobStoreTest
    extends TestSupport
{
  private BlobIdFactory idFactory;

  private FilePathPolicy pathPolicy;

  private FileOperations fileOps;

  private BlobMetadataStore metadataStore;

  private FileBlobStore fileBlobStore;

  @Before
  public void initMocks() {
    idFactory = mock(BlobIdFactory.class);
    pathPolicy = mock(FilePathPolicy.class);
    fileOps = mock(FileOperations.class);
    metadataStore = mock(BlobMetadataStore.class);

    fileBlobStore = new FileBlobStore("testStore", idFactory, pathPolicy, fileOps, metadataStore);
  }

  @Test(expected = IllegalArgumentException.class)
  public void createRequiresHeaders() {
    final ByteArrayInputStream inputStream = new ByteArrayInputStream(new byte[100]);
    final HashMap<String, String> headers = new HashMap<String, String>();
    fileBlobStore.create(inputStream, headers);
  }

  @Test
  public void successfulCreation() throws Exception {
    final BlobId fakeId = new BlobId("testId");
    final Path fakePath = mock(Path.class);
    final long contentSize = 200L;
    final String fakeSHA1 = "3757y5abc234cfgg";
    final InputStream inputStream = mock(InputStream.class);
    final ImmutableMap<String, String> headers = ImmutableMap
        .of(BlobStore.BLOB_NAME_HEADER, "my blob", BlobStore.AUDIT_INFO_HEADER, "John did this");

    when(idFactory.createBlobId()).thenReturn(fakeId);
    when(pathPolicy.forContent(fakeId)).thenReturn(fakePath);
    when(fileOps.create(fakePath, inputStream)).thenReturn(new StreamMetrics(contentSize, fakeSHA1));

    final Blob blob = fileBlobStore.create(inputStream, headers);

    final BlobMetrics metrics = blob.getMetrics();

    assertThat(metrics.getSHA1Hash(), is(equalTo(fakeSHA1)));
    assertThat(metrics.getContentSize(), is(equalTo(contentSize)));

    assertTrue("Creation time should be very recent",
        metrics.getCreationTime().isAfter(new DateTime().minusSeconds(2)));
  }
}