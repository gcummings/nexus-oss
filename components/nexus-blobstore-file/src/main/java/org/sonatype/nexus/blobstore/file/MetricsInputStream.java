package org.sonatype.nexus.blobstore.file;

import java.io.FilterInputStream;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.sonatype.nexus.util.DigesterUtils;

import com.google.common.io.CountingInputStream;

/**
 * A utility to collect metrics about the content of an input stream.
 *
 * @since 3.0
 */
public class MetricsInputStream
    extends FilterInputStream
{
  private MessageDigest messageDigest;

  private CountingInputStream countingInputStream;

  public static MetricsInputStream metricsInputStream(final InputStream wrappedStream, final String algorithm)
      throws NoSuchAlgorithmException
  {
    final MessageDigest digest = MessageDigest.getInstance(algorithm);
    return new MetricsInputStream(new CountingInputStream(wrappedStream), digest);
  }

  public MetricsInputStream(final CountingInputStream countingStream, final MessageDigest messageDigest) {
    super(new DigestInputStream(countingStream, messageDigest));
    this.messageDigest = messageDigest;
  }

  public String getMessageDigest() {
    return DigesterUtils.getDigestAsString(messageDigest.digest());
  }

  public long getSize(){
    return countingInputStream.getCount();
  }

}
