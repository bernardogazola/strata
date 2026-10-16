package br.pucpr.tcc.strata.analyzer.repository;

import java.util.concurrent.TimeUnit;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.SystemReader;

public final class HermeticSystemReader extends SystemReader.Delegate {

  private static final long TIMESTAMP_RESOLUTION_SECONDS = 2;

  private HermeticSystemReader(SystemReader delegate) {
    super(delegate);
  }

  public static synchronized void install() {
    if (!isInstalled()) {
      SystemReader.setInstance(new HermeticSystemReader(SystemReader.getInstance()));
    }
  }

  public static boolean isInstalled() {
    return SystemReader.getInstance() instanceof HermeticSystemReader;
  }

  @Override
  public String getenv(String variable) {
    return null;
  }

  @Override
  public String getHostname() {
    return "localhost";
  }

  @Override
  public FileBasedConfig openSystemConfig(Config parent, FS fs) {
    return new InMemoryConfig(parent, fs, false);
  }

  @Override
  public FileBasedConfig openJGitConfig(Config parent, FS fs) {
    return new InMemoryConfig(parent, fs, false);
  }

  @Override
  public FileBasedConfig openUserConfig(Config parent, FS fs) {
    return new InMemoryConfig(parent, fs, true);
  }

  private static final class InMemoryConfig extends FileBasedConfig {

    private final boolean answersTimestampResolution;

    InMemoryConfig(Config parent, FS fs, boolean answersTimestampResolution) {
      super(parent, null, fs);
      this.answersTimestampResolution = answersTimestampResolution;
    }

    @Override
    public void load() {}

    @Override
    public void save() {
      // Não grava: o JGit salvaria o FileStoreAttributes em ~/.config/jgit/config.
    }

    @Override
    public boolean isOutdated() {
      return false;
    }

    @Override
    public long getTimeUnit(
        String section, String subsection, String name, long defaultValue, TimeUnit wantUnit) {
      if (answersTimestampResolution
          && "filesystem".equals(section)
          && "timestampResolution".equals(name)) {
        return wantUnit.convert(TIMESTAMP_RESOLUTION_SECONDS, TimeUnit.SECONDS);
      }
      return super.getTimeUnit(section, subsection, name, defaultValue, wantUnit);
    }
  }
}
