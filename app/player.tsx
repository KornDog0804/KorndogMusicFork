import { View, Text, Image, TouchableOpacity, StyleSheet } from "react-native";
import { useState } from "react";

export default function Player() {
  const [isPlaying, setIsPlaying] = useState(false);
  const [liked, setLiked] = useState(false);

  const track = {
    title: "Parasites",
    artist: "Polaris",
    artwork: "https://i.scdn.co/image/ab67616d0000b273d8f8c1d6e6dcb4e6c5d5f5c1"
  };

  return (
    <View style={styles.container}>

      <Image source={{ uri: track.artwork }} style={styles.art} />

      <Text style={styles.title}>{track.title}</Text>
      <Text style={styles.artist}>{track.artist}</Text>

      <View style={styles.progressBar}>
        <View style={styles.progressFill} />
      </View>

      <View style={styles.controls}>

        <TouchableOpacity>
          <Text style={styles.control}>🔀</Text>
        </TouchableOpacity>

        <TouchableOpacity>
          <Text style={styles.control}>⏮️</Text>
        </TouchableOpacity>

        <TouchableOpacity onPress={() => setIsPlaying(!isPlaying)}>
          <Text style={styles.play}>
            {isPlaying ? "⏸️" : "▶️"}
          </Text>
        </TouchableOpacity>

        <TouchableOpacity>
          <Text style={styles.control}>⏭️</Text>
        </TouchableOpacity>

        <TouchableOpacity onPress={() => setLiked(!liked)}>
          <Text style={styles.control}>
            {liked ? "⭐" : "☆"}
          </Text>
        </TouchableOpacity>

      </View>

    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: "#000",
    alignItems: "center",
    justifyContent: "center",
    padding: 20
  },
  art: {
    width: 300,
    height: 300,
    borderRadius: 12,
    marginBottom: 20
  },
  title: {
    color: "#00ff66",
    fontSize: 22,
    fontWeight: "bold"
  },
  artist: {
    color: "#aaa",
    marginBottom: 20
  },
  progressBar: {
    width: "90%",
    height: 4,
    backgroundColor: "#333",
    borderRadius: 2,
    marginBottom: 20
  },
  progressFill: {
    width: "30%",
    height: 4,
    backgroundColor: "#00ff66"
  },
  controls: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
    width: "80%"
  },
  control: {
    fontSize: 24,
    color: "#fff"
  },
  play: {
    fontSize: 36,
    color: "#fff"
  }
});
