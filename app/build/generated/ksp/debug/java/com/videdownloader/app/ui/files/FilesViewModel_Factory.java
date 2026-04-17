package com.videdownloader.app.ui.files;

import android.app.Application;
import com.videdownloader.app.data.db.DownloadDao;
import com.videdownloader.app.data.preferences.AppPreferences;
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
public final class FilesViewModel_Factory implements Factory<FilesViewModel> {
  private final Provider<Application> applicationProvider;

  private final Provider<DownloadDao> downloadDaoProvider;

  private final Provider<AppPreferences> appPreferencesProvider;

  public FilesViewModel_Factory(Provider<Application> applicationProvider,
      Provider<DownloadDao> downloadDaoProvider, Provider<AppPreferences> appPreferencesProvider) {
    this.applicationProvider = applicationProvider;
    this.downloadDaoProvider = downloadDaoProvider;
    this.appPreferencesProvider = appPreferencesProvider;
  }

  @Override
  public FilesViewModel get() {
    return newInstance(applicationProvider.get(), downloadDaoProvider.get(), appPreferencesProvider.get());
  }

  public static FilesViewModel_Factory create(Provider<Application> applicationProvider,
      Provider<DownloadDao> downloadDaoProvider, Provider<AppPreferences> appPreferencesProvider) {
    return new FilesViewModel_Factory(applicationProvider, downloadDaoProvider, appPreferencesProvider);
  }

  public static FilesViewModel newInstance(Application application, DownloadDao downloadDao,
      AppPreferences appPreferences) {
    return new FilesViewModel(application, downloadDao, appPreferences);
  }
}
