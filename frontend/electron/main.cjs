const { app, BrowserWindow, session, shell } = require('electron')
const path = require('path')

const APP_URL = process.env.VISIONARY_TUTOR_APP_URL || 'https://zhiyexueye.top'
const APP_ORIGIN = new URL(APP_URL).origin

function isTrustedUrl(rawUrl) {
  try {
    return new URL(rawUrl).origin === APP_ORIGIN
  } catch {
    return false
  }
}

function createWindow() {
  const window = new BrowserWindow({
    width: 1440,
    height: 900,
    minWidth: 1024,
    minHeight: 700,
    show: false,
    autoHideMenuBar: true,
    backgroundColor: '#f5f2ff',
    icon: path.join(__dirname, '..', 'build-resources', 'icon.png'),
    webPreferences: {
      nodeIntegration: false,
      contextIsolation: true,
      sandbox: true,
      webSecurity: true,
    },
  })

  window.webContents.setWindowOpenHandler(({ url }) => {
    if (url.startsWith('https://')) shell.openExternal(url)
    return { action: 'deny' }
  })

  window.webContents.on('will-navigate', (event, url) => {
    if (!isTrustedUrl(url)) {
      event.preventDefault()
      if (url.startsWith('https://')) shell.openExternal(url)
    }
  })

  window.once('ready-to-show', () => window.show())
  window.loadURL(APP_URL).catch(() => {
    window.loadFile(path.join(__dirname, '..', 'dist', 'index.html'))
  })
}

app.whenReady().then(() => {
  session.defaultSession.setPermissionRequestHandler((webContents, permission, callback) => {
    callback(isTrustedUrl(webContents.getURL()) && permission === 'media')
  })

  createWindow()
  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow()
  })
})

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit()
})
