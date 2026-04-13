package com.videdownloader.app;

import com.videdownloader.app.data.preferences.AppPreferences;
import dagger.MembersInjector;
import dagger.internal.DaggerGenerated;
import dagger.internal.InjectedFieldSignature;
import dagger.internal.QualifierMetadata;
import javax.annotation.processing.Generated;
import javax.inject.Provider;

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
public final class MainActivity_MembersInjector implements MembersInjector<MainActivity> {
  private final Provider<AppPreferences> preferencesProvider;

  public MainActivity_MembersInjector(Provider<AppPreferences> preferencesProvider) {
    this.preferencesProvider = preferencesProvider;
  }

  public static MembersInjector<MainActivity> create(Provider<AppPreferences> preferencesProvider) {
    return new MainActivity_MembersInjector(preferencesProvider);
  }

  @Override
  public void injectMembers(MainActivity instance) {
    injectPreferences(instance, preferencesProvider.get());
  }

  @InjectedFieldSignature("com.videdownloader.app.MainActivity.preferences")
  public static void injectPreferences(MainActivity instance, AppPreferences preferences) {
    instance.preferences = preferences;
  }
}
