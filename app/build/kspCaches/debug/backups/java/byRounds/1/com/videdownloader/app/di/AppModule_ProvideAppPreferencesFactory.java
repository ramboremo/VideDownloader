package com.videdownloader.app.di;

import android.content.Context;
import com.videdownloader.app.data.preferences.AppPreferences;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Preconditions;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;
import javax.inject.Provider;

@ScopeMetadata("javax.inject.Singleton")
@QualifierMetadata("dagger.hilt.android.qualifiers.ApplicationContext")
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
public final class AppModule_ProvideAppPreferencesFactory implements Factory<AppPreferences> {
  private final Provider<Context> contextProvider;

  public AppModule_ProvideAppPreferencesFactory(Provider<Context> contextProvider) {
    this.contextProvider = contextProvider;
  }

  @Override
  public AppPreferences get() {
    return provideAppPreferences(contextProvider.get());
  }

  public static AppModule_ProvideAppPreferencesFactory create(Provider<Context> contextProvider) {
    return new AppModule_ProvideAppPreferencesFactory(contextProvider);
  }

  public static AppPreferences provideAppPreferences(Context context) {
    return Preconditions.checkNotNullFromProvides(AppModule.INSTANCE.provideAppPreferences(context));
  }
}
