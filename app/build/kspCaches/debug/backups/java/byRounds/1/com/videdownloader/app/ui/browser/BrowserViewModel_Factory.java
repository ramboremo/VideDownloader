package com.videdownloader.app.ui.browser;

import android.app.Application;
import com.videdownloader.app.data.db.BookmarkDao;
import com.videdownloader.app.data.db.DownloadDao;
import com.videdownloader.app.data.db.HistoryDao;
import com.videdownloader.app.data.preferences.AppPreferences;
import com.videdownloader.app.service.AdBlocker;
import com.videdownloader.app.service.VideoDetector;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;
import javax.inject.Provider;

@ScopeMetadata
@QualifierMetadata
@DaggerGenerated
@Generated(
    value = "dagger.internal.codegen.ComponentProcessor",
    comments = "https://dagger.dev"
)
@SuppressWarnings({
    "unchecked",
    "rawtypes",
    "KotlinInternal",
    "KotlinInternalInJava",
    "cast",
    "deprecation",
    "nullness:initialization.field.uninitialized"
})
public final class BrowserViewModel_Factory implements Factory<BrowserViewModel> {
  private final Provider<Application> applicationProvider;

  private final Provider<VideoDetector> videoDetectorProvider;

  private final Provider<AdBlocker> adBlockerProvider;

  private final Provider<BookmarkDao> bookmarkDaoProvider;

  private final Provider<HistoryDao> historyDaoProvider;

  private final Provider<DownloadDao> downloadDaoProvider;

  private final Provider<AppPreferences> preferencesProvider;

  public BrowserViewModel_Factory(Provider<Application> applicationProvider,
      Provider<VideoDetector> videoDetectorProvider, Provider<AdBlocker> adBlockerProvider,
      Provider<BookmarkDao> bookmarkDaoProvider, Provider<HistoryDao> historyDaoProvider,
      Provider<DownloadDao> downloadDaoProvider, Provider<AppPreferences> preferencesProvider) {
    this.applicationProvider = applicationProvider;
    this.videoDetectorProvider = videoDetectorProvider;
    this.adBlockerProvider = adBlockerProvider;
    this.bookmarkDaoProvider = bookmarkDaoProvider;
    this.historyDaoProvider = historyDaoProvider;
    this.downloadDaoProvider = downloadDaoProvider;
    this.preferencesProvider = preferencesProvider;
  }

  @Override
  public BrowserViewModel get() {
    return newInstance(applicationProvider.get(), videoDetectorProvider.get(), adBlockerProvider.get(), bookmarkDaoProvider.get(), historyDaoProvider.get(), downloadDaoProvider.get(), preferencesProvider.get());
  }

  public static BrowserViewModel_Factory create(Provider<Application> applicationProvider,
      Provider<VideoDetector> videoDetectorProvider, Provider<AdBlocker> adBlockerProvider,
      Provider<BookmarkDao> bookmarkDaoProvider, Provider<HistoryDao> historyDaoProvider,
      Provider<DownloadDao> downloadDaoProvider, Provider<AppPreferences> preferencesProvider) {
    return new BrowserViewModel_Factory(applicationProvider, videoDetectorProvider, adBlockerProvider, bookmarkDaoProvider, historyDaoProvider, downloadDaoProvider, preferencesProvider);
  }

  public static BrowserViewModel newInstance(Application application, VideoDetector videoDetector,
      AdBlocker adBlocker, BookmarkDao bookmarkDao, HistoryDao historyDao, DownloadDao downloadDao,
      AppPreferences preferences) {
    return new BrowserViewModel(application, videoDetector, adBlocker, bookmarkDao, historyDao, downloadDao, preferences);
  }
}
