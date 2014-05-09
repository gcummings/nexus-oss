package org.sonatype.nexus.blobstore.file;

import org.sonatype.nexus.configuration.application.ApplicationDirectories;
import org.sonatype.nexus.configuration.application.ApplicationDirectoriesImpl;

import com.google.common.io.Files;
import com.google.inject.AbstractModule;
import com.google.inject.Provides;

/**
 * A simple Guice module that hooks the test up to temp directories.
 */
public class TempDirectoryModule
    extends AbstractModule
{
  @Override
  protected void configure() {
  }

  @Provides
  public ApplicationDirectories applicationDirectories() {
    return new ApplicationDirectoriesImpl(null, Files.createTempDir(), Files.createTempDir(), Files.createTempDir());
  }

  @Provides
  public FilePathPolicy filePathPolicy() {
    return new HashingSubdirFileLocationPolicy(Files.createTempDir().toPath());
  }
}
