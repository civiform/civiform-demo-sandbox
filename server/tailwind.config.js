/** @type {import('tailwindcss').Config} */
module.exports = {
  content: [
    './app/views/**/*.html',
    './app/views/**/*.java',
    './app/assets/javascripts/**/*.ts',
  ],
  theme: {
    extend: {
      colors: {
        'cf-blue': '#005ea2',
        'cf-blue-dark': '#1a4480',
        'cf-blue-light': '#d9e8f6',
      },
    },
  },
  plugins: [],
}
