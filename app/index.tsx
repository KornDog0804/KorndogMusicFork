import { BackHandler, Pressable, StyleSheet, Text } from 'react-native'
import { Link } from 'expo-router'
import { useEffect, useState } from 'react'
import { useObserveEffect } from '@legendapp/state/react'
import { ui$ } from '@/states/ui'
import { openSharedUrl } from '@/lib/page'
import { useShareIntent } from 'expo-share-intent'
import * as Linking from 'expo-linking'
import { MainPage } from '@/components/page/MainPage'
import { isAndroid } from '@/lib/utils'
import NouTubeViewModule from '@/modules/nou-tube-view'
import { sleepTimer$ } from '@/states/sleep-timer'
import { showToast } from '@/lib/toast'
import { t } from 'i18next'
import {
  addSleepTimerListener,
  getNativeSleepTimerRemainingMs,
  hasSleepTimerNativeSupport,
} from '@/lib/sleep-timer-native'

export default function HomeScreen() {
  const [scriptOnStart] = useState('')
  const { hasShareIntent, shareIntent } = useShareIntent()

  useEffect(() => {
    const url = shareIntent.webUrl || shareIntent.text
    if (hasShareIntent && url) {
      openSharedUrl(url)
    }
  }, [hasShareIntent, shareIntent])

  useEffect(() => {
    // Listener from Kotlin
    // @ts-expect-error
    NouTubeViewModule.addListener('log', (evt) => {
      console.log('[kotlin]', evt.msg)
    })

    let sleepTimerSubscription: { remove?: () => void } | undefined

    if (isAndroid && hasSleepTimerNativeSupport()) {
      void getNativeSleepTimerRemainingMs()
        .then((remainingMs) => sleepTimer$.setRemainingMs(remainingMs))
        .catch((error) => {
          console.error('getSleepTimerRemainingMs failed', error)
        })

      sleepTimerSubscription = addSleepTimerListener((evt) => {
        sleepTimer$.setRemainingMs(evt.remainingMs ?? null)

        if (evt.reason === 'expired') {
          showToast(t('sleepTimer.expiredToast'))
        }
      })
    } else {
      sleepTimer$.clear()
    }

    const backSubscription = BackHandler.addEventListener(
      'hardwareBackPress',
      function () {
        const webview = ui$.webview.get()
        webview?.goBack()
        return true
      }
    )

    return () => {
      sleepTimerSubscription?.remove?.()
      backSubscription.remove()
    }
  }, [])

  useEffect(() => {
    const subscription = Linking.addEventListener('url', (e) => {
      openSharedUrl(e.url)
    })

    return () => subscription.remove()
  }, [])

  useObserveEffect(ui$.url, () => {
    ui$.queueModalOpen.set(false)
  })

  return (
    <>
      <MainPage contentJs={scriptOnStart} />

      {/* 🎧 KORN DOG PLAYER BUTTON */}
      <Link href="/player" asChild>
        <Pressable style={styles.playerButton}>
          <Text style={styles.playerButtonText}>🎧 Player</Text>
        </Pressable>
      </Link>
    </>
  )
}

const styles = StyleSheet.create({
  playerButton: {
    position: 'absolute',
    top: 90, // 👈 sits up near top controls (adjust if needed)
    right: 12,
    backgroundColor: 'rgba(45, 20, 80, 0.92)',
    borderColor: '#39ff14',
    borderWidth: 1.5,
    borderRadius: 22,
    paddingHorizontal: 14,
    paddingVertical: 8,
    zIndex: 9999,

    // 🔥 Glow effect
    shadowColor: '#39ff14',
    shadowOpacity: 0.6,
    shadowRadius: 8,
    elevation: 10,
  },
  playerButtonText: {
    color: '#39ff14',
    fontSize: 14,
    fontWeight: '900',
    letterSpacing: 0.5,
  },
})
