package org.sonatype.nexus.blobstore.file;

import org.sonatype.nexus.configuration.application.ApplicationDirectories;
import org.sonatype.nexus.configuration.application.ApplicationDirectoriesImpl;

import com.google.common.io.Files;
import com.google.inject.AbstractModule;
import com.google.inject.Provider;

/**
 * A simple Guice module that provides a stub ApplicationDirectories implementation that points to temp directories.
 */
class TestApplicationDirectoriesProvider
    extends AbstractModule
{
  @Override
  protected void configure() {
    bind(ApplicationDirectories.class).toProvider(new Provider<ApplicationDirectories>()
    {
      @Override
      public ApplicationDirectories get() {
        return new ApplicationDirectoriesImpl(null, Files.createTempDir(), Files.createTempDir());
      }
    });

    bind(FilePathPolicy.class).toProvider(new Provider<FilePathPolicy>()
    {
      @Override
      public FilePathPolicy get() {
        return new HashingSubdirFileLocationPolicy(Files.createTempDir().toPath());
      }
    });
  }
}
