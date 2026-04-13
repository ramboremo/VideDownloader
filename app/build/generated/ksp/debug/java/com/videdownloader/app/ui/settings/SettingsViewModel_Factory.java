package com.videdownloader.app.ui.settings;

import com.videdownloader.app.data.db.HistoryDao;
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
public final class SettingsViewModel_Factory implements Factory<SettingsViewModel> {
  private final Provider<AppPreferences> preferencesProvider;

  private final Provider<HistoryDao> historyDaoProvider;

  public SettingsViewModel_Factory(Provider<AppPreferences> preferencesProvider,
      Provider<HistoryDao> historyDaoProvider) {
    this.preferencesProvider = preferencesProvider;
    this.historyDaoProvider = historyDaoProvider;
  }

  @Override
  public SettingsViewModel get() {
    return newInstance(preferencesProvider.get(), historyDaoProvider.get());
  }

  public static SettingsViewModel_Factory create(Provider<AppPreferences> preferencesProvider,
      Provider<HistoryDao> historyDaoProvider) {
    return new SettingsViewModel_Factory(preferencesProvider, historyDaoProvider);
  }

  public static SettingsViewModel newInstance(AppPreferences preferences, HistoryDao historyDao) {
    return new SettingsViewModel(preferences, historyDao);
  }
}
