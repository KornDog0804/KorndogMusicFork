/** @type {import('tailwindcss').Config} */
module.exports = {
  darkMode: "class",
  content: ["./app/**/*.{js,jsx,ts,tsx}", "./components/**/*.{js,jsx,ts,tsx}"],
  presets: [require("nativewind/preset")],
  theme: {
    extend: {
      colors: {
        kd: {
          'deep':    '#1a0a2e',
          'purple':  '#2d1450',
          'mid':     '#3f1d6b',
          'light':   '#5c2d91',
          'green':   '#39ff14',
          'dim':     '#2bcc0f',
          'red':     '#ff2d2d',
          'bone':    '#e8e0d0',
          'grey':    '#8a7f99',
          'surface': '#110720',
          'raised':  '#1e0e35',
          'text':    '#f0eaf8',
          'muted':   '#a090b8',
        },
      },
    },
  },
  plugins: [],
};
