/** Tailwind v3 preset bridge */
module.exports = {
  theme: {
    extend: {
      fontFamily: {
        "display": ["var(--font-display)"],
        "body": ["var(--font-body)"],
        "mono": ["var(--font-mono)"]
      },
      colors: {
        background: "var(--background)",
        surface: "var(--surface)",
        foreground: "var(--foreground)",
        muted: "var(--muted)",
        accent: "var(--accent)",
        border: "var(--border)",
      },
      boxShadow: {
        "elevation-1": "var(--shadow-elevation-1)",
        "elevation-2": "var(--shadow-elevation-2)",
        "elevation-3": "var(--shadow-elevation-3)",
        "elevation-4": "var(--shadow-elevation-4)",
        "elevation-5": "var(--shadow-elevation-5)"
      },
    },
  },
};
