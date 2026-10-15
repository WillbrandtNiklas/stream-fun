import { Component, OnInit, signal } from '@angular/core';

interface Video { id: string; title: string; channel: string; length: string; thumb: string; }

@Component({
  selector: 'app-root',
  template: `
    <header class="hd">
      <h1>Theo</h1>
      <span class="pill" [class.live]="talking() || listening()">{{ status() }}</span>
    </header>

    <section class="card talk">
      <button class="big" [class.on]="talking()" (contextmenu)="$event.preventDefault()"
        (pointerdown)="startTalk()" (pointerup)="stopTalk()" (pointerleave)="stopTalk()" (pointercancel)="stopTalk()">
        <span class="ico">🎤</span>
        <span>{{ talking() ? 'Du sendest…' : 'Halten zum Sprechen' }}</span>
      </button>
    </section>

    <section class="card">
      <span class="label">Steuerung</span>
      <div class="row">
        <button [class.on]="listening()" (click)="toggleListen()">
          {{ listening() ? '🔇 Zuhören beenden' : '👂 Zuhören' }}
        </button>
        <button [class.on]="live()" (click)="toggleLive()">
          {{ live() ? '⏹ Live stoppen' : '🎥 Live ansehen' }}
        </button>
        <button (click)="screenshot()">📸 Screenshot</button>
      </div>
      <div class="row">
        <button (click)="post('/api/close-tab', 'Tab geschlossen')">❌ Tab schließen</button>
        <button (click)="post('/api/close-window', 'Programm geschlossen')">🚪 Programm schließen</button>
      </div>
      <div class="row">
        <button (click)="post('/api/mouse/freeze?seconds=' + secs, 'Maus eingefroren')">🧊 Maus einfrieren</button>
        <span class="field"><input type="number" min="0" [value]="secs" (input)="secs = +$any($event.target).value"> Sek.</span>
        <button (click)="post('/api/mouse/unfreeze', 'Maus frei')">🔥 Freigeben</button>
      </div>
    </section>

    <section class="card">
      <span class="label">YouTube</span>
      <div class="row">
        <input class="search" type="search" placeholder="Video suchen…" [value]="query"
          (input)="query = $any($event.target).value" (keydown.enter)="search()">
        <button class="primary" (click)="search()">🔎 Suchen</button>
        <button (click)="post('/api/youtube/stop', 'Wiedergabe gestoppt')">⏹ Stop</button>
      </div>
    </section>
    @if (selected(); as v) {
      <section class="chosen">
        <img [src]="v.thumb" alt="">
        <div class="meta">
          <div class="t">{{ v.title }}</div>
          <div class="c">{{ v.channel }}</div>
          <div class="modes">
            <button (click)="play(v, true)">📺 Mit Bild (alle Bildschirme)</button>
            <button (click)="play(v, false)">🔊 Nur Ton (versteckt)</button>
            <button class="ghost" (click)="selected.set(null)">Abbrechen</button>
          </div>
        </div>
      </section>
    }
    <section class="results">
      @for (v of videos(); track v.id) {
        <button class="vid" [class.on]="selected()?.id === v.id" (click)="selected.set(v)">
          <img [src]="v.thumb" alt="">
          <span class="meta">
            <span class="t">{{ v.title }}</span>
            <span class="c">{{ v.channel }}{{ v.length ? ' · ' + v.length : '' }}</span>
          </span>
        </button>
      }
    </section>
    @if (live()) {
      <section class="card live-wrap">
        <span class="label">Live-Bild vom Server</span>
        <img class="live-view" [src]="liveSrc()" alt="Live-Bild">
      </section>
    }
    @if (shot()) { <img [src]="shot()!" alt="Screenshot" class="thumb" (click)="zoom.set(true)"> }
    @if (zoom() && shot()) {
      <div class="overlay" (click)="zoom.set(false)">
        <img [src]="shot()!" alt="Screenshot groß">
      </div>
    }
    <footer>
      <span>Version: {{ version() }}</span>
      @if (link()) { <a [href]="link()" target="_blank" rel="noopener">aktueller Link</a> }
      <button class="ghost" (click)="update()">⬆️ Auf neueste Version aktualisieren</button>
    </footer>
  `,
})
export class App implements OnInit {
  videos = signal<Video[]>([]);
  selected = signal<Video | null>(null);
  query = '';
  shot = signal<string | null>(null);
  live = signal(false);
  liveSrc = signal('');
  zoom = signal(false);
  status = signal('Bereit');
  version = signal('…');
  link = signal('');
  talking = signal(false);
  listening = signal(false);
  secs = 10;
  enc = encodeURIComponent;
  private ws?: WebSocket;
  private ctx?: AudioContext;
  private stream?: MediaStream;
  private proc?: ScriptProcessorNode;
  private micWs?: WebSocket;
  private micCtx?: AudioContext;
  private micTime = 0;

  async ngOnInit() {
    try { this.version.set((await (await fetch('/api/version')).text()) || 'dev'); } catch {}
    try { this.link.set((await (await fetch('/api/link')).text()).trim()); } catch {}
  }

  async update() {
    if (!confirm('Server aktualisiert sich und startet neu. Für ein paar Sekunden ist die Seite weg. Fortfahren?')) return;
    await this.post('/api/update', 'Update läuft – Server startet neu…');
  }

  async search() {
    const q = this.query.trim();
    if (!q) return;
    this.status.set('Suche…');
    try {
      const r = await fetch('/api/youtube/search?q=' + encodeURIComponent(q));
      if (!r.ok) { this.status.set('Suche fehlgeschlagen (' + r.status + ')'); return; }
      const list = (await r.json()) as Video[];
      this.videos.set(list);
      this.selected.set(null);
      this.status.set(list.length ? list.length + ' Treffer' : 'Keine Treffer');
    } catch { this.status.set('Server nicht erreichbar'); }
  }

  async play(v: Video, video: boolean) {
    await this.post('/api/youtube/play?id=' + v.id + '&video=' + video,
      v.title + (video ? ' läuft (Vollbild)' : ' läuft (nur Ton)'));
    this.selected.set(null);
  }

  async post(url: string, msg: string) {
    try { const r = await fetch(url, { method: 'POST' }); this.status.set(r.ok ? msg : 'Fehler ' + r.status); }
    catch { this.status.set('Server nicht erreichbar'); }
  }

  toggleLive() {
    if (this.live()) {
      this.live.set(false);
      this.liveSrc.set(''); // stoppt den laufenden Stream
      this.status.set('Live beendet');
    } else {
      this.liveSrc.set('/api/screen-stream?t=' + Date.now());
      this.live.set(true);
      this.status.set('Live…');
    }
  }

  async screenshot() {
    try {
      const r = await fetch('/api/screenshot?' + Date.now());
      const old = this.shot();
      if (old) URL.revokeObjectURL(old);
      this.shot.set(URL.createObjectURL(await r.blob()));
      this.status.set('Screenshot aktualisiert');
    } catch { this.status.set('Screenshot fehlgeschlagen'); }
  }

  async startTalk() {
    if (this.talking()) return;
    this.talking.set(true);
    try {
      this.stream = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true } });
      if (!this.talking()) { this.cleanup(); return; } // schon losgelassen
      const ctx = (this.ctx = new AudioContext());
      const ws = (this.ws = new WebSocket(`${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/ws/audio`));
      ws.onopen = () => ws.send('rate:' + ctx.sampleRate);
      const proc = (this.proc = ctx.createScriptProcessor(2048, 1, 1));
      proc.onaudioprocess = ev => {
        if (ws.readyState !== WebSocket.OPEN) return;
        const f = ev.inputBuffer.getChannelData(0);
        const i16 = new Int16Array(f.length);
        for (let i = 0; i < f.length; i++) {
          const v = Math.max(-1, Math.min(1, f[i]));
          i16[i] = v < 0 ? v * 0x8000 : v * 0x7fff;
        }
        ws.send(i16.buffer);
      };
      ctx.createMediaStreamSource(this.stream).connect(proc);
      proc.connect(ctx.destination); // Output bleibt leer (stumm), wird aber für den Prozessor benötigt
    } catch {
      this.talking.set(false);
      this.status.set('Mikro nicht verfügbar (HTTPS und Erlaubnis nötig)');
      this.cleanup();
    }
  }

  stopTalk() {
    if (!this.talking()) return;
    this.talking.set(false);
    this.cleanup();
  }

  toggleListen() {
    if (this.listening()) { this.stopListen(); return; }
    this.listening.set(true);
    try {
      const ctx = (this.micCtx = new AudioContext());
      ctx.resume();
      this.micTime = 0;
      let rate = 44100;
      const ws = (this.micWs = new WebSocket(`${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/ws/mic`));
      ws.binaryType = 'arraybuffer';
      ws.onmessage = ev => {
        if (typeof ev.data === 'string') {
          if (ev.data.startsWith('rate:')) rate = +ev.data.slice(5);
          return;
        }
        const i16 = new Int16Array(ev.data as ArrayBuffer);
        const buf = ctx.createBuffer(1, i16.length, rate);
        const ch = buf.getChannelData(0);
        for (let i = 0; i < i16.length; i++) ch[i] = i16[i] / 0x8000;
        const src = ctx.createBufferSource();
        src.buffer = buf;
        src.connect(ctx.destination);
        const now = ctx.currentTime;
        if (this.micTime < now) this.micTime = now + 0.05; // kleiner Puffer gegen Ruckeln
        src.start(this.micTime);
        this.micTime += buf.duration;
      };
      ws.onclose = () => { if (this.listening()) this.stopListen(); };
      ws.onerror = () => this.status.set('Zuhören fehlgeschlagen');
      this.status.set('Zuhören…');
    } catch {
      this.listening.set(false);
      this.status.set('Zuhören fehlgeschlagen');
    }
  }

  private stopListen() {
    this.listening.set(false);
    this.micWs?.close();
    this.micCtx?.close();
    this.micWs = this.micCtx = undefined;
  }

  private cleanup() {
    this.proc?.disconnect();
    this.stream?.getTracks().forEach(t => t.stop());
    this.ctx?.close();
    this.ws?.close();
    this.proc = this.stream = this.ctx = this.ws = undefined;
  }
}
