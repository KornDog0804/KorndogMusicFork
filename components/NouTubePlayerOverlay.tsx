import { useState } from "react";
import {
  View,
  Text,
  Image,
  Pressable,
  StyleSheet,
  ImageBackground,
} from "react-native";
import { ui$ } from "@/states/ui";

export default function NouTubePlayerOverlay({ onClose }: any) {
  const [playing, setPlaying] = useState(false);
  const [liked, setLiked] = useState(false);

  const track = {
    title: "Now Playing",
    artist: "NouTube",
    artwork: "https://picsum.photos/800/800",
  };

  function runJs(js: string) {
    try {
      const webview: any = ui$.webview.get();
      webview?.executeJavaScript?.(js);
    } catch {}
  }

  function playPause() {
    const next = !playing;
    setPlaying(next);

    runJs(`
      (function(){
        var media=document.querySelector('video,audio');
        if(media){
          ${next ? "media.play();" : "media.pause();"}
        }
      })();
    `);
  }

  function nextTrack() {
    runJs(`document.querySelector('[aria-label*="Next"]')?.click();`);
  }

  function prevTrack() {
    runJs(`document.querySelector('[aria-label*="Previous"]')?.click();`);
  }

  function like() {
    setLiked(!liked);
    runJs(`document.querySelector('[aria-label*="Like"]')?.click();`);
  }

  return (
    <ImageBackground source={{ uri: track.artwork }} style={styles.bg} blurRadius={25}>
      <View style={styles.overlay} />

      <View style={styles.container}>
        <Pressable onPress={onClose}>
          <Text style={styles.back}>‹ Back</Text>
        </Pressable>

        <Image source={{ uri: track.artwork }} style={styles.art} />

        <Text style={styles.title}>{track.title}</Text>
        <Text style={styles.artist}>{track.artist}</Text>

        <View style={styles.controls}>
          <Pressable onPress={prevTrack}><Text style={styles.btn}>⏮</Text></Pressable>
          <Pressable onPress={playPause}><Text style={styles.play}>{playing ? "⏸" : "▶"}</Text></Pressable>
          <Pressable onPress={nextTrack}><Text style={styles.btn}>⏭</Text></Pressable>
        </View>

        <Pressable onPress={like}>
          <Text style={styles.like}>{liked ? "★" : "☆"}</Text>
        </Pressable>

        <Text style={styles.tag}>MUSIC THERAPY NEVER DIES</Text>
      </View>
    </ImageBackground>
  );
}

const styles = StyleSheet.create({
  bg: { flex: 1 },
  overlay: {
    ...StyleSheet.absoluteFillObject,
    backgroundColor: "rgba(0,0,0,0.7)",
  },
  container: {
    flex: 1,
    alignItems: "center",
    justifyContent: "center",
  },
  back: {
    color: "#fff",
    fontSize: 18,
    marginBottom: 20,
  },
  art: {
    width: 260,
    height: 260,
    borderRadius: 20,
    marginBottom: 20,
  },
  title: {
    color: "#39ff14",
    fontSize: 28,
    fontWeight: "bold",
  },
  artist: {
    color: "#ccc",
    marginBottom: 20,
  },
  controls: {
    flexDirection: "row",
    gap: 30,
    marginBottom: 20,
  },
  btn: {
    fontSize: 30,
    color: "#fff",
  },
  play: {
    fontSize: 42,
    color: "#39ff14",
  },
  like: {
    fontSize: 30,
    color: "#fff",
    marginBottom: 20,
  },
  tag: {
    color: "#39ff14",
    fontSize: 12,
    letterSpacing: 2,
  },
});
