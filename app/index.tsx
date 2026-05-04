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
  const { hasShareIntent, shareIntent } = useShareIntent()

  // 🔥 THIS is the brain — pulls REAL data from YouTube Music
  const [scriptOnStart] = useState(`
    (function() {
      function parseTime(t) {
        if (!t) return 0;
        const parts = t.trim().split(':').map(Number);
        if (parts.length === 2) return parts[0]*60 + parts[1];
        if (parts.length === 3) return parts[0]*3600 + parts[1]*60 + parts[2];
        return 0;
      }

      function sendNowPlaying() {
        try {
          const titleEl = document.querySelector('ytmusic-player-bar .title');
          const artistEl = document.querySelector('ytmusic-player-bar .byline');
          const imgEl = document.querySelector('ytmusic-player-bar img');
          const timeEl = document.querySelector('ytmusic-player-bar .time-info');

          const title = titleEl ? titleEl.textContent : '';
          const artist = artistEl ? artistEl.textContent : '';
          const thumbnail = imgEl ? imgEl.src : '';

          let current = 0;
          let duration = 0;

          if (timeEl && timeEl.textContent.includes('/')) {
            const parts = timeEl.textContent.split('/');
            current = parseTime(parts[0]);
            duration = parseTime(parts[1]);
          }

          window.ReactNativeWebView?.postMessage(JSON.stringify({
            type: 'NOW_PLAYING',
            title,
            artist,
            thumbnail,
            current,
            duration
          }));
        } catch (e) {}
      }

      setInterval(sendNowPlaying, 1000);
    })();
  `)

  useEffect(() => {
    const url = shareIntent.webUrl || shareIntent.text
    if (hasShareIntent && url) {
      openSharedUrl(url)
    }
  }, [hasShareIntent, shareIntent])

  useEffect(() => {
    NouTubeViewModule.addListener('log', (evt) => {
      console.log('[kotlin]', evt.msg)
    })

    let sleepTimerSubscription

    if (isAndroid && hasSleepTimerNativeSupport()) {
      getNativeSleepTimerRemainingMs()
        .then((remainingMs) => sleepTimer$.setRemainingMs(remainingMs))
        .catch(() => {})

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

      {/* 🔥 KornDog Player Button — locked position */}
      <Link href="/player" asChild>
        <Pressable style={styles.playerButton}>
          <Text style={styles.playerButtonText}>🎧</Text>
        </Pressable>
      </Link>
    </>
  )
}

const styles = StyleSheet.create({
  playerButton: {
    position: 'absolute',
    right: 16,
    bottom: 280,

    width: 52,
    height: 52,
    borderRadius: 26,

    backgroundColor: 'rgba(45, 20, 80, 0.92)',
    borderColor: '#39ff14',
    borderWidth: 1.5,

    alignItems: 'center',
    justifyContent: 'center',

    zIndex: 9999,
    elevation: 12,

    shadowColor: '#39ff14',
    shadowOpacity: 0.7,
    shadowRadius: 12,
  },

  playerButtonText: {
    color: '#39ff14',
    fontSize: 22,
    fontWeight: '900',
  },
})
