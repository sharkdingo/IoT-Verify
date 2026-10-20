const neutralColors = Object.fromEntries(
  [50, 100, 200, 300, 400, 500, 600, 700, 800, 900, 950]
    .map(step => [step, `var(--iot-neutral-${step})`]),
)

/** @type {import('tailwindcss').Config} */
export default {
  darkMode: 'class',
  content: [
    "./index.html",
    "./src/**/*.{vue,js,ts,jsx,tsx}",
  ],
  theme: {
    extend: {
      colors: {
        slate: neutralColors,
        gray: neutralColors,
        zinc: neutralColors,
        neutral: neutralColors,
        stone: neutralColors,
        // Single source of truth lives in styles/base.css so Tailwind utilities and
        // hand-written CSS cannot drift into two different "primary" colors.
        // Tailwind opacity modifiers compile to color-mix; hand-written translucent
        // roles use color-mix against these same variables.
        primary: "var(--iot-color-accent)",
      },
      borderRadius: {
        DEFAULT: "0.75rem",
        '2xl': '1.5rem',
      },
    },
  },
  plugins: [],
}
