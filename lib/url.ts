import { isWeb } from './utils'

export function normalizeUrl(url: string) {
  if (!url) {
    return url
  }

  const newURL = new URL(url)

  // Force EVERYTHING into YouTube Music
  newURL.host = 'music.youtube.com'

  // Remove weird app params
  newURL.searchParams.delete('app')

  return newURL.href
}

export function unnormalizeUrl(url: string) {
  if (!isWeb || !url) {
    return url
  }

  const newURL = new URL(url)

  // Keep browser/web consistency
  if (
    newURL.host === 'm.youtube.com' ||
    newURL.host === 'www.youtube.com'
  ) {
    newURL.host = 'music.youtube.com'
  }

  return newURL.href
}
