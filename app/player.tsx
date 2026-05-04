import { useState } from "react";
import {
  Image,
  ImageBackground,
  Pressable,
  StyleSheet,
  Text,
  View,
} from "react-native";

export default function Player() {
  const [mode, setMode] = useState<"korndog" | "clean">("korndog");
  const [liked, setLiked] = useState(false);
  const [playing, setPlaying] = useState(false);

  const track = {
    title: "Parasites",
    artist: "Polaris",
    artwork: "https://upload.wikimedia.org/wikipedia/en/9/9f/Polaris_-_Fatalism.png",
  };

  const isKD = mode === "korndog";

  return (
    <ImageBackground source={{ uri: track.artwork }} style={styles.bg} blurRadius={24}>
      <View style={styles.dimmer} />
      <View style={[styles.overlay, isKD ? styles.kdOverlay : styles.cleanOverlay]}>
        <View style={styles.topRow}>
          <Pressable onPress={() => setMode(isKD ? "clean" : "korndog")} style={styles.modeButton}>
            <Text style={styles.modeText}>{isKD ? "KornDog Mode" : "Clean Mode"}</Text>
          </Pressable>
          <Text style={styles.menu}>⋮</Text>
        </View>

        <View style={[styles.artWrap, isKD && styles.kdGlow]}>
          <Image source={{ uri: track.artwork }} style={styles.art} />
        </View>

        <Text style={[styles.title, isKD && styles.kdTitle]}>{track.title}</Text>
        <Text style={styles.artist}>{track.artist}</Text>

        <View style={styles.progressRow}>
          <Text style={styles.time}>0:42</Text>
          <View style={styles.progressBar}>
            <View style={[styles.progressFill, isKD && styles.kdProgress]} />
          </View>
          <Text style={styles.time}>3:16</Text>
        </View>

        <View style={styles.controls}>
          <Pressable style={styles.iconButton}><Text style={styles.icon}>🔀</Text></Pressable>
          <Pressable style={styles.iconButton}><Text style={styles.icon}>⏮</Text></Pressable>

          <Pressable onPress={() => setPlaying(!playing)} style={[styles.playButton, isKD && styles.kdPlay]}>
            <Text style={styles.playText}>{playing ? "⏸" : "▶"}</Text>
          </Pressable>

          <Pressable style={styles.iconButton}><Text style={styles.icon}>⏭</Text></Pressable>

          <Pressable onPress={() => setLiked(!liked)} style={styles.iconButton}>
            <Text style={[styles.icon, liked && styles.starOn]}>{liked ? "⭐" : "☆"}</Text>
          </Pressable>
        </View>

        <View style={styles.bottomTabs}>
          <Text style={styles.tabActive}>Up Next</Text>
          <Text style={styles.tab}>Lyrics</Text>
          <Text style={styles.tab}>Queue</Text>
        </View>

        {isKD && <Text style={styles.tagline}>VINYL THERAPY NEVER DIES</Text>}
      </View>
    </ImageBackground>
  );
}

const styles = StyleSheet.create({
  bg: { flex: 1, backgroundColor: "#000" },
  dimmer: { ...StyleSheet.absoluteFillObject, backgroundColor: "rgba(0,0,0,0.48)" },
  overlay: { flex: 1, paddingHorizontal: 24, paddingTop: 58, paddingBottom: 34, alignItems: "center" },
  kdOverlay: { backgroundColor: "rgba(18,0,32,0.44)" },
  cleanOverlay: { backgroundColor: "rgba(0,0,0,0.36)" },
  topRow: { width: "100%", flexDirection: "row", justifyContent: "space-between", alignItems: "center", marginBottom: 26 },
  modeButton: { borderWidth: 1, borderColor: "#39ff14", borderRadius: 999, paddingHorizontal: 16, paddingVertical: 8, backgroundColor: "rgba(45,20,80,0.75)" },
  modeText: { color: "#39ff14", fontWeight: "900" },
  menu: { color: "#fff", fontSize: 30, fontWeight: "900" },
  artWrap: { width: 318, height: 318, borderRadius: 24, overflow: "hidden", marginBottom: 28, backgroundColor: "#111" },
  kdGlow: { shadowColor: "#39ff14", shadowOpacity: 0.9, shadowRadius: 22, elevation: 18, borderWidth: 1, borderColor: "rgba(57,255,20,0.45)" },
  art: { width: "100%", height: "100%" },
  title: { color: "#fff", fontSize: 34, fontWeight: "900", textAlign: "center" },
  kdTitle: { color: "#39ff14", textShadowColor: "rgba(57,255,20,0.8)", textShadowRadius: 10 },
  artist: { color: "#d9c7ff", fontSize: 20, fontWeight: "700", marginTop: 4, marginBottom: 26 },
  progressRow: { width: "100%", flexDirection: "row", alignItems: "center", gap: 10, marginBottom: 28 },
  time: { color: "#d6d6d6", fontSize: 13, width: 38 },
  progressBar: { flex: 1, height: 8, borderRadius: 999, backgroundColor: "rgba(255,255,255,0.22)", overflow: "hidden" },
  progressFill: { width: "32%", height: "100%", backgroundColor: "#fff" },
  kdProgress: { backgroundColor: "#39ff14" },
  controls: { width: "100%", flexDirection: "row", alignItems: "center", justifyContent: "space-between", marginBottom: 32 },
  iconButton: { width: 48, height: 48, borderRadius: 18, alignItems: "center", justifyContent: "center", backgroundColor: "rgba(255,255,255,0.08)" },
  icon: { color: "#fff", fontSize: 27, fontWeight: "900" },
  starOn: { color: "#39ff14" },
  playButton: { width: 78, height: 78, borderRadius: 999, backgroundColor: "#fff", alignItems: "center", justifyContent: "center" },
  kdPlay: { backgroundColor: "#39ff14", shadowColor: "#39ff14", shadowOpacity: 0.9, shadowRadius: 18, elevation: 15 },
  playText: { color: "#160020", fontSize: 34, fontWeight: "900" },
  bottomTabs: { width: "100%", flexDirection: "row", justifyContent: "space-around", borderTopWidth: 1, borderTopColor: "rgba(255,255,255,0.12)", paddingTop: 22 },
  tabActive: { color: "#39ff14", fontSize: 16, fontWeight: "900" },
  tab: { color: "#b7a9c9", fontSize: 16, fontWeight: "800" },
  tagline: { marginTop: 28, color: "#39ff14", letterSpacing: 2, fontSize: 11, fontWeight: "900", opacity: 0.85 },
});
