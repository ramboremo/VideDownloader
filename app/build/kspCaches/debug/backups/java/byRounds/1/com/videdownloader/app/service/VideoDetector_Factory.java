package com.videdownloader.app.service;

import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;
import javax.inject.Provider;
import okhttp3.OkHttpClient;

@ScopeMetadata("javax.inject.Singleton")
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
public final class VideoDetector_Factory implements Factory<VideoDetector> {
  private final Provider<OkHttpClient> okHttpClientProvider;

  public VideoDetector_Factory(Provider<OkHttpClient> okHttpClientProvider) {
    this.okHttpClientProvider = okHttpClientProvider;
  }

  @Override
  public VideoDetector get() {
    return newInstance(okHttpClientProvider.get());
  }

  public static VideoDetector_Factory create(Provider<OkHttpClient> okHttpClientProvider) {
    return new VideoDetector_Factory(okHttpClientProvider);
  }

  public static VideoDetector newInstance(OkHttpClient okHttpClient) {
    return new VideoDetector(okHttpClient);
  }
}
