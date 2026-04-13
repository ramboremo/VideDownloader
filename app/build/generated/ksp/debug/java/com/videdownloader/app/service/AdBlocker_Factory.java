package com.videdownloader.app.service;

import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;

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
public final class AdBlocker_Factory implements Factory<AdBlocker> {
  @Override
  public AdBlocker get() {
    return newInstance();
  }

  public static AdBlocker_Factory create() {
    return InstanceHolder.INSTANCE;
  }

  public static AdBlocker newInstance() {
    return new AdBlocker();
  }

  private static final class InstanceHolder {
    private static final AdBlocker_Factory INSTANCE = new AdBlocker_Factory();
  }
}
