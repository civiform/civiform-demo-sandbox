import htmx from 'htmx.org'

// Make htmx globally available for inline attributes
declare global {
  interface Window {
    htmx: typeof htmx
  }
}
window.htmx = htmx

document.addEventListener('DOMContentLoaded', () => {
  console.log('Civiform Sandbox Builder frontend initialized.')
})
