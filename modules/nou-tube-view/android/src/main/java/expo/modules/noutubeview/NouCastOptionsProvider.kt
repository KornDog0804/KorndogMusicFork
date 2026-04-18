package expo.modules.noutubeview

import android.content.Context
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider

class NouCastOptionsProvider : OptionsProvider {

  override fun getCastOptions(context: Context): CastOptions {
    // "CC1AD845" is the Default Media Receiver app ID
    return CastOptions.Builder()
      .setReceiverApplicationId("CC1AD845")
      .build()
  }

  override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
