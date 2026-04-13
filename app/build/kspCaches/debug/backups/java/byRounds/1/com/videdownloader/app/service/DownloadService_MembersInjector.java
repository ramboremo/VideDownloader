package com.videdownloader.app.service;

import com.videdownloader.app.data.db.DownloadDao;
import com.videdownloader.app.data.preferences.AppPreferences;
import dagger.MembersInjector;
import dagger.internal.DaggerGenerated;
import dagger.internal.InjectedFieldSignature;
import dagger.internal.QualifierMetadata;
import javax.annotation.processing.Generated;
import javax.inject.Provider;
import okhttp3.OkHttpClient;

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
public final class DownloadService_MembersInjector implements MembersInjector<DownloadService> {
  private final Provider<OkHttpClient> okHttpClientProvider;

  private final Provider<DownloadDao> downloadDaoProvider;

  private final Provider<AppPreferences> appPreferencesProvider;

  public DownloadService_MembersInjector(Provider<OkHttpClient> okHttpClientProvider,
      Provider<DownloadDao> downloadDaoProvider, Provider<AppPreferences> appPreferencesProvider) {
    this.okHttpClientProvider = okHttpClientProvider;
    this.downloadDaoProvider = downloadDaoProvider;
    this.appPreferencesProvider = appPreferencesProvider;
  }

  public static MembersInjector<DownloadService> create(Provider<OkHttpClient> okHttpClientProvider,
      Provider<DownloadDao> downloadDaoProvider, Provider<AppPreferences> appPreferencesProvider) {
    return new DownloadService_MembersInjector(okHttpClientProvider, downloadDaoProvider, appPreferencesProvider);
  }

  @Override
  public void injectMembers(DownloadService instance) {
    injectOkHttpClient(instance, okHttpClientProvider.get());
    injectDownloadDao(instance, downloadDaoProvider.get());
    injectAppPreferences(instance, appPreferencesProvider.get());
  }

  @InjectedFieldSignature("com.videdownloader.app.service.DownloadService.okHttpClient")
  public static void injectOkHttpClient(DownloadService instance, OkHttpClient okHttpClient) {
    instance.okHttpClient = okHttpClient;
  }

  @InjectedFieldSignature("com.videdownloader.app.service.DownloadService.downloadDao")
  public static void injectDownloadDao(DownloadService instance, DownloadDao downloadDao) {
    instance.downloadDao = downloadDao;
  }

  @InjectedFieldSignature("com.videdownloader.app.service.DownloadService.appPreferences")
  public static void injectAppPreferences(DownloadService instance, AppPreferences appPreferences) {
    instance.appPreferences = appPreferences;
  }
}
