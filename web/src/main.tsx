import React from 'react'
import ReactDOM from 'react-dom/client'
// Smart Gurukul brand fonts: Poppins (headings, buttons, UI) + Mukta (body and all Hindi/Devanagari).
import '@fontsource/poppins/400.css'
import '@fontsource/poppins/500.css'
import '@fontsource/poppins/600.css'
import '@fontsource/poppins/700.css'
import '@fontsource/mukta/devanagari-400.css'
import '@fontsource/mukta/devanagari-500.css'
import '@fontsource/mukta/devanagari-600.css'
import '@fontsource/mukta/devanagari-700.css'
import '@fontsource/noto-sans-devanagari/devanagari-400.css'
import '@fontsource/noto-sans-devanagari/devanagari-500.css'
import '@fontsource/noto-sans-devanagari/devanagari-600.css'
import '@fontsource/noto-sans-devanagari/devanagari-700.css'
import './theme.css'
import './index.css'
import './platform.css'
import './playful.css'
import { isPublicCardHash } from './lib/emergencyPublic'
import { initTheme } from './lib/theme'

initTheme()

// The emergency card link (#/e/<token>) opens a tiny standalone page for strangers: it must never load the caregiver
// app, its login redirects or its data. So the choice is made before anything of the app is imported.
const isPublicCard = () => isPublicCardHash(window.location.hash)
const publicAtLoad = isPublicCard()
// Pasting a link into a tab that is already open only changes the hash: reload so the right program starts.
// popstate is listened to as well, and FIRST: the app's own router also listens to it and would rewrite an unknown hash
// (such as #/e/<token>) to #/welcome before the hashchange event arrives.
const reloadIfSwitched = () => { if (isPublicCard() !== publicAtLoad) window.location.reload() }
window.addEventListener('popstate', reloadIfSwitched)
window.addEventListener('hashchange', reloadIfSwitched)

async function start() {
  let Root: React.ComponentType
  if (publicAtLoad) {
    Root = (await import('./screens/PublicEmergency')).default
  } else {
    Root = (await import('./App')).default
  }
  ReactDOM.createRoot(document.getElementById('root')!).render(
    <React.StrictMode>
      <Root />
    </React.StrictMode>,
  )
}
void start()
