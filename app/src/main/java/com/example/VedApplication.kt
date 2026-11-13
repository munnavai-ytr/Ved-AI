package com.example

import android.app.Application
import com.example.di.AppContainer

/**
 * Application class holding lazy singleton dependencies for Ved.
 */
class VedApplication : Application() {
  val container: AppContainer by lazy { AppContainer(this) }
}
