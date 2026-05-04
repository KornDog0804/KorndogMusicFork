import { useEffect, useRef, useState } from "react";
import {
  Image,
  ImageBackground,
  Pressable,
  StyleSheet,
  Text,
  View,
} from "react-native";
import { ui$ } from "@/states/ui";

type PlayerState = {
  title: string;
  artist: string;
  artwork: string;
  position: number;
  duration: number;
  playing: boolean;
};

export default function NouTubePlayerOverlay({ onClose }: { onClose: () => void }) {
  const [liked, setLiked] = useState(false);
  const [player, setPlayer] = useState<PlayerState>({
    title: "Now Playing",
    artist: "NouTube",
    artwork: "https://picsum.photos/800/800",
    position: 0,
    duration: 300,
    playing: false,
  });

  const mounted = useRef(true);

  function getWebView(): any {
    return ui$.webview.get() as any;
  }

  async function runJs(js: string) {
    try {
      const webview = getWebView();
      if (!webview?.executeJavaScript) return null;
      return await webview.executeJavaScript(js);
    } catch {
      return null;
    }
  }

  async function refreshPlayerState() {
    const result = await runJs(`
      (function(){
        try{
          function clean(t){
            return (t||'')
              .replace(/\\s+/g,' ')
              .replace(/Explicit|Album|Song|Video/g,'')
              .trim();
          }

          function upgradeImage(u){
            if(!u)return '';
            return u
              .replace(/=w[0-9]+-h[0-9]+.*$/i,'=w800-h800-l90-rj')
              .replace(/\\/s[0-9]+\\//i,'/s800/');
          }

          var media=document.querySelector('video,audio');

          var titleEl =
            document.querySelector('ytmusic-player-bar .title') ||
            document.querySelector('.content-info-wrapper .title') ||
            document.querySelector('ytmusic-player-page .title');

          var artistEl =
            document.querySelector('ytmusic-player-bar .subtitle') ||
            document.querySelector('.content-info-wrapper .subtitle') ||
            document.querySelector('ytmusic-player-page .subtitle');

          var title = clean(titleEl ? (titleEl.innerText || titleEl.textContent) : '');
          var artist = clean(artistEl ? (artistEl.innerText || artistEl.textContent) : '');

          if(artist.indexOf(' • ') > -1) artist = artist.split(' • ')[0].trim();
          if(artist.indexOf(' - ') > -1) artist = artist.split(' - ')[0].trim();

          var art = '';
          var imgs = Array.from(document.querySelectorAll('ytmusic-player-bar img, ytmusic-player-page img, img'));
          for(var i=0;i<imgs.length;i++){
            var src = imgs[i].currentSrc || imgs[i].src || '';
            if(src && (src.indexOf('ytimg') > -1 || src.indexOf('googleusercontent') > -1)){
              var r = imgs[i].getBoundingClientRect();
              if(r.width >= 24 && r.height >= 24){
                art = upgradeImage(src);
                break;
              }
            }
          }

          try{
            var q = JSON.parse(localStorage.getItem('korndog_queue') || '[]');
            if(q && q.length){
              if(!title && q[0].title) title = q[0].title;
              if(!artist && q[0].artist) artist = q[0].artist;
              if(!art && q[0].thumb) art = q[0].thumb;
            }
          }catch(e){}

          var position = media ? Math.floor(media.currentTime || 0) : 0;
          var duration = media ? Math.floor(media.duration || 0) : 0;
          var playing = media ? !media.paused : false;

          return JSON.stringify({
            title: title || 'Now Playing',
            artist: artist || 'NouTube',
            artwork: art || 'https://picsum.photos/800/800',
            position: position || 0,
            duration: duration || 300,
            playing: playing
          });
        }catch(e){
          return JSON.stringify({
            title:'Now Playing',
            artist:'NouTube',
            artwork:'https://picsum.photos/800/800',
            position:0,
            duration:300,
            playing:false
          });
        }
      })();
    `);

    if (!mounted.current || !result) return;

    try {
      const parsed = JSON.parse(String(result).replace(/^"|"$/g, "").replace(/\\"/g, '"'));
      setPlayer(parsed);
    } catch {}
  }

  function playPause() {
    const nextPlaying = !player.playing;

    setPlayer((p) => ({ ...p, playing: nextPlaying }));

    runJs(`
      (function(){
        try{
          window._kdUserPaused=${nextPlaying ? "false" : "true"};
          window._kdShouldBePlaying=${nextPlaying ? "true" : "false"};
          localStorage.setItem('kd_user_paused','${nextPlaying ? "false" : "true"}');

          var media=document.querySelector('video,audio');
          if(media){
            if(${nextPlaying}) {
              var p=media.play();
              if(p&&p.catch)p.catch(function(){});
            } else {
              media.pause();
            }
            return true;
          }

          var label=${nextPlaying ? "'Play'" : "'Pause'"};
          var btn=document.querySelector(
            'button[aria-label*="'+label+'"],[aria-label*="'+label+'"],[title*="'+label+'"]'
          );
          if(btn){btn.click();return true;}
        }catch(e){}
        return false;
      })();
    `);

    setTimeout(refreshPlayerState, 500);
  }

  function nextTrack() {
    runJs(`
      (function(){
        try{
          var selectors=[
            'ytmusic-player-bar tp-yt-paper-icon-button[title="Next"]',
            'ytmusic-player-bar button[aria-label*="Next"]',
            'button[aria-label*="Next"]',
            '[aria-label*="Next"]',
            '[title*="Next"]'
          ];
          for(var i=0;i<selectors.length;i++){
            var btn=document.querySelector(selectors[i]);
            if(btn){btn.click();return true;}
          }
        }catch(e){}
        return false;
      })();
    `);
    setTimeout(refreshPlayerState, 900);
  }

  function previousTrack() {
    runJs(`
      (function(){
        try{
          var selectors=[
            'ytmusic-player-bar tp-yt-paper-icon-button[title="Previous"]',
            'ytmusic-player-bar button[aria-label*="Previous"]',
            'button[aria-label*="Previous"]',
            '[aria-label*="Previous"]',
            '[title*="Previous"]'
          ];
          for(var i=0;i<selectors.length;i++){
            var btn=document.querySelector(selectors[i]);
            if(btn){btn.click();return true;}
          }
        }catch(e){}
        return false;
      })();
    `);
    setTimeout(refreshPlayerState, 900);
  }

  function toggleLike() {
    setLiked((v) => !v);
    runJs(`
      (function(){
        try{
          var selectors=[
            'button[aria-label*="Like"]',
            'button[aria-label*="like"]',
            '[aria-label*="Like"]',
            '[aria-label*="like"]',
            '[title*="Like"]',
            '[title*="like"]'
          ];
          for(var i=0;i<selectors.length;i++){
            var btn=document.querySelector(selectors[i]);
            if(btn){btn.click();return true;}
          }
        }catch(e){}
        return false;
      })();
    `);
  }

  function toggleShuffle() {
    runJs(`
      (function(){
        try{
          var selectors=[
            'button[aria-label*="Shuffle"]',
            'button[aria-label*="shuffle"]',
            '[aria-label*="Shuffle"]',
            '[aria-label*="shuffle"]',
            '[title*="Shuffle"]',
            '[title*="shuffle"]'
          ];
          for(var i=0;i<selectors.length;i++){
            var btn=document.querySelector(selectors[i]);
            if(btn){btn.click();return true;}
          }
        }catch(e){}
        return false;
      })();
    `);
  }

  useEffect(() => {
    mounted.current = true;
    refreshPlayerState();

    const timer = setInterval(refreshPlayerState, 1000);

    return () => {
      mounted.current = false;
      clearInterval(timer);
    };
  }, []);

  const progress =
    player.duration > 0 ? Math.min(100, Math.max(0, (player.position / player.duration) * 100)) : 0;

  function fmt(sec: number) {
    const m = Math.floor(sec / 60);
    const s = Math.floor(sec % 60);
    return `${m}:${s.toString().padStart(2, "0")}`;
  }

  return (
    <View style={styles.fullscreen}>
      <ImageBackground
        source={{ uri: player.artwork }}
        style={styles.bg}
        blurRadius={28}
        resizeMode="cover"
      >
        <View style={styles.dimmer} />

        <View style={styles.safe}>
          <View style={styles.topRow}>
            <Pressable onPress={onClose} style={styles.backButton}>
              <Text style={styles.backText}>‹ Back</Text>
            </Pressable>

            <View style={styles.modeButton}>
              <Text style={styles.modeText}>KornDog Mode</Text>
            </View>

            <Text style={styles.menu}>⋮</Text>
          </View>

          <View style={styles.artWrap}>
            <Image source={{ uri: player.artwork }} style={styles.art} resizeMode="cover" />
          </View>

          <View style={styles.infoPanel}>
            <Text style={styles.title} numberOfLines={1}>
              {player.title}
            </Text>
            <Text style={styles.artist} numberOfLines={1}>
              {player.artist}
            </Text>

            <View style={styles.progressRow}>
              <Text style={styles.time}>{fmt(player.position)}</Text>
              <View style={styles.progressBar}>
                <View style={[styles.progressFill, { width: `${progress}%` }]} />
              </View>
              <Text style={styles.time}>{fmt(player.duration)}</Text>
            </View>
          </View>

          <View style={styles.controlsGlass}>
            <Pressable onPress={toggleShuffle} style={styles.iconButton}>
              <Text style={styles.icon}>↝</Text>
            </Pressable>

            <Pressable onPress={previousTrack} style={styles.iconButton}>
              <Text style={styles.icon}>⏮</Text>
            </Pressable>

            <Pressable onPress={playPause} style={styles.playButton}>
              <Text style={styles.playText}>{player.playing ? "Ⅱ" : "▶"}</Text>
            </Pressable>

            <Pressable onPress={nextTrack} style={styles.iconButton}>
              <Text style={styles.icon}>⏭</Text>
            </Pressable>

            <Pressable onPress={toggleLike} style={styles.iconButton}>
              <Text style={[styles.icon, liked && styles.starOn]}>{liked ? "★" : "☆"}</Text>
            </Pressable>
          </View>

          <View style={styles.bottomTabs}>
            <Text style={styles.tabActive}>Up Next</Text>
            <Text style={styles.tab}>Lyrics</Text>
            <Text style={styles.tab}>Queue</Text>
          </View>

          <Text style={styles.tagline}>MUSIC THERAPY NEVER DIES</Text>
        </View>
      </ImageBackground>
    </View>
  );
}

const styles = StyleSheet.create({
  fullscreen: {
    ...StyleSheet.absoluteFillObject,
    zIndex: 99999,
    elevation: 99999,
    backgroundColor: "#000",
  },
  bg: {
    flex: 1,
    backgroundColor: "#000",
  },
  dimmer: {
    ...StyleSheet.absoluteFillObject,
    backgroundColor: "rgba(8,0,18,0.68)",
  },
  safe: {
    flex: 1,
    paddingHorizontal: 22,
    paddingTop: 46,
    paddingBottom: 42,
    alignItems: "center",
  },
  topRow: {
    width: "100%",
    flexDirection: "row",
    justifyContent: "space-between",
    alignItems: "center",
    marginBottom: 22,
  },
  backButton: {
    paddingHorizontal: 12,
    paddingVertical: 8,
    borderRadius: 999,
    backgroundColor: "rgba(255,255,255,0.12)",
  },
  backText: {
    color: "#fff",
    fontWeight: "900",
    fontSize: 16,
  },
  modeButton: {
    borderWidth: 1,
    borderColor: "#39ff14",
    borderRadius: 999,
    paddingHorizontal: 18,
    paddingVertical: 9,
    backgroundColor: "rgba(45,20,80,0.82)",
  },
  modeText: {
    color: "#39ff14",
    fontWeight: "900",
    fontSize: 16,
  },
  menu: {
    color: "#fff",
    fontSize: 31,
    fontWeight: "900",
  },
  artWrap: {
    width: "86%",
    aspectRatio: 1,
    borderRadius: 26,
    overflow: "hidden",
    marginBottom: 24,
    backgroundColor: "#101010",
    borderWidth: 1,
    borderColor: "rgba(57,255,20,0.72)",
    shadowColor: "#39ff14",
    shadowOpacity: 0.95,
    shadowRadius: 24,
    elevation: 20,
  },
  art: {
    width: "100%",
    height: "100%",
  },
  infoPanel: {
    width: "100%",
    borderRadius: 28,
    paddingHorizontal: 18,
    paddingVertical: 18,
    backgroundColor: "rgba(10,0,20,0.55)",
    borderWidth: 1,
    borderColor: "rgba(255,255,255,0.12)",
    marginBottom: 18,
  },
  title: {
    color: "#39ff14",
    fontSize: 34,
    fontWeight: "900",
    textAlign: "center",
    textShadowColor: "rgba(57,255,20,0.95)",
    textShadowRadius: 14,
  },
  artist: {
    color: "#d9c7ff",
    fontSize: 21,
    fontWeight: "800",
    textAlign: "center",
    marginTop: 4,
    marginBottom: 22,
  },
  progressRow: {
    width: "100%",
    flexDirection: "row",
    alignItems: "center",
    gap: 10,
  },
  time: {
    color: "#eeeeee",
    fontSize: 13,
    width: 38,
    fontWeight: "800",
  },
  progressBar: {
    flex: 1,
    height: 9,
    borderRadius: 999,
    backgroundColor: "rgba(255,255,255,0.25)",
    overflow: "hidden",
  },
  progressFill: {
    height: "100%",
    backgroundColor: "#39ff14",
  },
  controlsGlass: {
    width: "100%",
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
    borderRadius: 30,
    paddingHorizontal: 12,
    paddingVertical: 14,
    backgroundColor: "rgba(255,255,255,0.1)",
    borderWidth: 1,
    borderColor: "rgba(255,255,255,0.15)",
    marginBottom: 22,
  },
  iconButton: {
    width: 50,
    height: 50,
    borderRadius: 18,
    alignItems: "center",
    justifyContent: "center",
    backgroundColor: "rgba(15,8,25,0.8)",
  },
  icon: {
    color: "#fff",
    fontSize: 31,
    fontWeight: "900",
  },
  starOn: {
    color: "#39ff14",
    textShadowColor: "rgba(57,255,20,0.95)",
    textShadowRadius: 10,
  },
  playButton: {
    width: 82,
    height: 82,
    borderRadius: 999,
    backgroundColor: "#39ff14",
    alignItems: "center",
    justifyContent: "center",
    shadowColor: "#39ff14",
    shadowOpacity: 1,
    shadowRadius: 20,
    elevation: 18,
  },
  playText: {
    color: "#160020",
    fontSize: 34,
    fontWeight: "900",
  },
  bottomTabs: {
    width: "100%",
    flexDirection: "row",
    justifyContent: "space-around",
    borderTopWidth: 1,
    borderTopColor: "rgba(255,255,255,0.16)",
    paddingTop: 18,
  },
  tabActive: {
    color: "#39ff14",
    fontSize: 17,
    fontWeight: "900",
  },
  tab: {
    color: "#c7b7d8",
    fontSize: 17,
    fontWeight: "800",
  },
  tagline: {
    marginTop: 16,
    marginBottom: 10,
    color: "#39ff14",
    letterSpacing: 3,
    fontSize: 11,
    fontWeight: "900",
    opacity: 0.95,
  },
});
