import { useState } from "react";
import {
  Image,
  ImageBackground,
  Pressable,
  SafeAreaView,
  StyleSheet,
  Text,
  View,
} from "react-native";
import { useRouter } from "expo-router";

export default function Player() {
  const router = useRouter();
  const [mode, setMode] = useState<"korndog" | "clean">("korndog");
  const [liked, setLiked] = useState(false);
  const [playing, setPlaying] = useState(false);
  const [artIndex, setArtIndex] = useState(0);

  const artworkList = [
    "https://upload.wikimedia.org/wikipedia/en/thumb/9/9f/Polaris_-_Fatalism.png/600px-Polaris_-_Fatalism.png",
    "https://upload.wikimedia.org/wikipedia/en/9/9f/Polaris_-_Fatalism.png",
    "https://picsum.photos/800/800",
  ];

  const track = {
    title: "Parasites",
    artist: "Polaris",
    artwork: artworkList[artIndex],
  };

  const isKD = mode === "korndog";

  function handleImageError() {
    if (artIndex < artworkList.length - 1) {
      setArtIndex(artIndex + 1);
    }
  }

  return (
    <ImageBackground
      source={{ uri: track.artwork }}
      style={styles.bg}
      blurRadius={26}
      resizeMode="cover"
      onError={handleImageError}
    >
      <View style={styles.dimmer} />

      <SafeAreaView style={styles.safe}>
        <View style={styles.topRow}>
          <Pressable onPress={() => router.back()} style={styles.backButton}>
            <Text style={styles.backText}>‹ Back</Text>
          </Pressable>

          <Pressable
            onPress={() => setMode(isKD ? "clean" : "korndog")}
            style={styles.modeButton}
          >
            <Text style={styles.modeText}>
              {isKD ? "KornDog Mode" : "Clean Mode"}
            </Text>
          </Pressable>

          <Text style={styles.menu}>⋮</Text>
        </View>

        <View style={[styles.artWrap, isKD && styles.kdGlow]}>
          <Image
            source={{ uri: track.artwork }}
            style={styles.art}
            resizeMode="cover"
            onError={handleImageError}
          />
        </View>

        <View style={styles.infoPanel}>
          <Text style={[styles.title, isKD && styles.kdTitle]}>
            {track.title}
          </Text>
          <Text style={styles.artist}>{track.artist}</Text>

          <View style={styles.progressRow}>
            <Text style={styles.time}>0:42</Text>
            <View style={styles.progressBar}>
              <View style={[styles.progressFill, isKD && styles.kdProgress]} />
            </View>
            <Text style={styles.time}>3:16</Text>
          </View>
        </View>

        <View style={styles.controlsGlass}>
          <Pressable style={styles.iconButton}>
            <Text style={styles.icon}>↝</Text>
          </Pressable>

          <Pressable style={styles.iconButton}>
            <Text style={styles.icon}>⏮</Text>
          </Pressable>

          <Pressable
            onPress={() => setPlaying(!playing)}
            style={[styles.playButton, isKD && styles.kdPlay]}
          >
            <Text style={styles.playText}>{playing ? "Ⅱ" : "▶"}</Text>
          </Pressable>

          <Pressable style={styles.iconButton}>
            <Text style={styles.icon}>⏭</Text>
          </Pressable>

          <Pressable onPress={() => setLiked(!liked)} style={styles.iconButton}>
            <Text style={[styles.icon, liked && styles.starOn]}>
              {liked ? "★" : "☆"}
            </Text>
          </Pressable>
        </View>

        <View style={styles.bottomTabs}>
          <Text style={styles.tabActive}>Up Next</Text>
          <Text style={styles.tab}>Lyrics</Text>
          <Text style={styles.tab}>Queue</Text>
        </View>

        {isKD && <Text style={styles.tagline}>VINYL THERAPY NEVER DIES</Text>}
      </SafeAreaView>
    </ImageBackground>
  );
}

const styles = StyleSheet.create({
  bg: {
    flex: 1,
    backgroundColor: "#000",
  },
  dimmer: {
    ...StyleSheet.absoluteFillObject,
    backgroundColor: "rgba(8,0,18,0.62)",
  },
  safe: {
    flex: 1,
    paddingHorizontal: 22,
    paddingTop: 18,
    paddingBottom: 28,
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
    backgroundColor: "rgba(255,255,255,0.08)",
  },
  backText: {
    color: "#fff",
    fontWeight: "900",
    fontSize: 15,
  },
  modeButton: {
    borderWidth: 1,
    borderColor: "#39ff14",
    borderRadius: 999,
    paddingHorizontal: 16,
    paddingVertical: 8,
    backgroundColor: "rgba(45,20,80,0.8)",
  },
  modeText: {
    color: "#39ff14",
    fontWeight: "900",
    fontSize: 15,
  },
  menu: {
    color: "#fff",
    fontSize: 30,
    fontWeight: "900",
  },
  artWrap: {
    width: "86%",
    aspectRatio: 1,
    borderRadius: 26,
    overflow: "hidden",
    marginBottom: 26,
    backgroundColor: "#101010",
    borderWidth: 1,
    borderColor: "rgba(255,255,255,0.16)",
  },
  kdGlow: {
    shadowColor: "#39ff14",
    shadowOpacity: 0.95,
    shadowRadius: 26,
    elevation: 20,
    borderColor: "rgba(57,255,20,0.7)",
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
    backgroundColor: "rgba(10,0,20,0.48)",
    borderWidth: 1,
    borderColor: "rgba(255,255,255,0.1)",
    marginBottom: 18,
  },
  title: {
    color: "#fff",
    fontSize: 36,
    fontWeight: "900",
    textAlign: "center",
  },
  kdTitle: {
    color: "#39ff14",
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
    width: "32%",
    height: "100%",
    backgroundColor: "#fff",
  },
  kdProgress: {
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
    backgroundColor: "rgba(255,255,255,0.08)",
    borderWidth: 1,
    borderColor: "rgba(255,255,255,0.12)",
    marginBottom: 24,
  },
  iconButton: {
    width: 50,
    height: 50,
    borderRadius: 18,
    alignItems: "center",
    justifyContent: "center",
    backgroundColor: "rgba(15,8,25,0.76)",
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
    backgroundColor: "#fff",
    alignItems: "center",
    justifyContent: "center",
  },
  kdPlay: {
    backgroundColor: "#39ff14",
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
    paddingTop: 20,
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
    marginTop: 22,
    color: "#39ff14",
    letterSpacing: 3,
    fontSize: 11,
    fontWeight: "900",
    opacity: 0.9,
  },
});
