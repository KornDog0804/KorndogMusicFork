package expo.modules.noutubeview

import android.content.Context
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider

/**
 * Required by the Google Cast SDK.
 * Uses the default media receiver so no custom Cast app ID is needed.
 */
class NouCastOptionsProvider : OptionsProvider {

  override fun getCastOptions(context: Context): CastOptions {
    return CastOptions.Builder()
      // Default Media Receiver — works with any Chromecast / Google TV
      // without needing a registered Cast App ID
      .setReceiverApplicationId(
        com.google.android.gms.cast.framework.CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID
      )
      .build()
  }

  override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
