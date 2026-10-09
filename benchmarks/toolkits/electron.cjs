const { app, BrowserWindow } = require('electron');
const fs = require('node:fs');
const path = require('node:path');
const readline = require('node:readline');
const crypto = require('node:crypto');

// Force a CPU Canvas2D backing store; completion is synchronized with getImageData.
app.disableHardwareAcceleration();
app.commandLine.appendSwitch('disable-background-networking');
app.commandLine.appendSwitch('disable-renderer-backgrounding');
app.commandLine.appendSwitch('disable-background-timer-throttling');
app.commandLine.appendSwitch('disable-dev-shm-usage');
app.setPath('userData', process.env.BYTEINK_ELECTRON_PROFILE);
const [scene, output, warmup, measured] = process.argv.slice(-4);
function message(value) { process.stdout.write(JSON.stringify(value) + '\n'); }
app.whenReady().then(async () => {
  const window = new BrowserWindow({ show: false, width: 64, height: 64,
    // This local, immutable fixture uses Node's monotonic nanosecond clock to avoid
    // the browser privacy timer's 100us quantization. No remote content is loaded.
    webPreferences: { nodeIntegration: true, contextIsolation: false, sandbox: false, backgroundThrottling: false } });
  await window.loadFile(path.join(__dirname, 'renderer.html'));
  const bytes = fs.readFileSync(scene);
  await window.webContents.executeJavaScript(`initialize(${JSON.stringify(bytes.toString('base64'))})`);
  message({ event: 'ready', runtime: process.versions.electron, chromium: process.versions.chrome,
    scene_sha256: crypto.createHash('sha256').update(bytes).digest('hex') });
  const input = readline.createInterface({ input: process.stdin, terminal: false });
  const lines = input[Symbol.asyncIterator]();
  while (true) {
    const next = await lines.next(); if (next.done) break;
    const line = next.value;
    if (line === 'quit') break;
    const [name, width, height, count, alpha, zoom] = line.split(',');
    const config = { name, width: +width, height: +height, count: +count, alpha: alpha === 'true', zoom: +zoom, warmup: +warmup, measured: +measured };
    const result = await window.webContents.executeJavaScript(`runCase(${JSON.stringify(config)})`);
    fs.writeFileSync(path.join(output, name + '.png'), Buffer.from(result.png, 'base64'));
    delete result.png;
    message({ event: 'case', ...result });
    // The next line is the parent's checkpoint acknowledgment; consume it here.
    await lines.next();
    await window.webContents.executeJavaScript('releaseCase()');
  }
  window.destroy(); app.quit();
}).catch(error => { process.stderr.write(error.stack + '\n'); app.exit(1); });
