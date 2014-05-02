package org.sonatype.nexus.blobstore.file;

/**
* @since 3.0
*/
class StreamMetrics
{
  private long size;

  private String SHA1;

  StreamMetrics(final long size, final String SHA1) {
    this.size = size;
    this.SHA1 = SHA1;
  }

  public long getSize() {
    return size;
  }

  public String getSHA1() {
    return SHA1;
  }
}
