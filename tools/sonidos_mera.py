"""Sonidos propios de la Mera Mera no Mi, sintetizados desde cero (ruido filtrado y senos): nada grabado ni copiado.
Uso: python3 sonidos.py <carpeta_wav> <carpeta_ogg>
"""
import numpy as np, wave, os, sys, subprocess

SR = 44100


def rng(seed):
    return np.random.default_rng(seed)


def secs(t):
    return int(round(t * SR))


def band(f, lo, hi, k=2):
    """Paso de banda suave (tipo Butterworth de orden k) sobre un eje de frecuencias."""
    ff = np.maximum(f, 1e-3)
    g = np.ones_like(ff)
    if lo:
        g = g / np.sqrt(1 + (lo / ff) ** (2 * k))
    if hi:
        g = g / np.sqrt(1 + (ff / hi) ** (2 * k))
    return g


def spec(x, gain):
    """Filtra en frecuencia (circular: lo que sale es periódico, vale para bucles)."""
    X = np.fft.rfft(x)
    f = np.fft.rfftfreq(len(x), 1 / SR)
    return np.fft.irfft(X * gain(f), len(x))


def colored(n, r, slope, lo=20, hi=18000, k=2):
    """Ruido con pendiente espectral (dB/octava en amplitud: −3 rosa, −6 marrón) entre lo y hi."""
    def g(f):
        ff = np.maximum(f, 1.0)
        return (ff / 1000.0) ** (slope / 6.0206) * band(ff, lo, hi, k)
    y = spec(r.standard_normal(n), g)
    return y / (np.std(y) + 1e-12)


def lfo(n, r, lo, hi):
    """Vaivén aleatorio de banda estrecha, periódico, con desviación 1."""
    y = spec(r.standard_normal(n), lambda f: band(f, lo, hi, 3))
    return y / (np.std(y) + 1e-12)


def stft_gain(x, gain_tf, nfft=1024, hop=256):
    """Filtro que cambia con el tiempo: gain_tf(t[:,None], f[None,:]) → ganancias por trama y frecuencia."""
    win = np.hanning(nfft + 1)[:-1]
    pad = nfft
    xp = np.concatenate([np.zeros(pad), x, np.zeros(pad + hop)])
    nfr = 1 + (len(xp) - nfft) // hop
    idx = np.arange(nfft)[None, :] + hop * np.arange(nfr)[:, None]
    X = np.fft.rfft(xp[idx] * win, axis=1)
    t = (np.arange(nfr) * hop + nfft / 2 - pad) / SR
    f = np.fft.rfftfreq(nfft, 1 / SR)
    X = X * gain_tf(t[:, None], f[None, :])
    y = np.fft.irfft(X, nfft, axis=1) * win
    out = np.zeros(len(xp))
    nrm = np.zeros(len(xp))
    np.add.at(out, idx, y)
    np.add.at(nrm, idx, np.broadcast_to(win ** 2, idx.shape))
    return (out / np.maximum(nrm, 1e-6))[pad:pad + len(x)]


def lp_t(fc):
    """Paso bajo de 2.º orden con corte fc(t)."""
    return lambda t, f: 1.0 / np.sqrt(1 + (f / np.maximum(fc(t), 20.0)) ** 4)


def bp_t(fc, width):
    """Paso de banda gaussiano en octavas alrededor de fc(t)."""
    return lambda t, f: np.exp(-0.5 * (np.log2(np.maximum(f, 20.0) / np.maximum(fc(t), 20.0)) / width) ** 2)


def tt(n):
    return np.arange(n) / SR


def attack(n, a):
    """Subida suave de 0 a 1 en a segundos."""
    t = tt(n)
    return np.clip(t / max(a, 1e-6), 0, 1) ** 2 * (3 - 2 * np.clip(t / max(a, 1e-6), 0, 1))


def fade_out(x, d):
    m = min(len(x), secs(d))
    y = x.copy()
    y[-m:] *= np.linspace(1, 0, m) ** 2
    return y


def fade_in(x, d):
    m = min(len(x), max(1, secs(d)))
    y = x.copy()
    y[:m] *= np.linspace(0, 1, m)
    return y


def sweep(n, f0, f1, tau):
    """Seno cuya frecuencia cae de f0 a f1 con constante tau."""
    t = tt(n)
    f = f1 + (f0 - f1) * np.exp(-t / tau)
    return np.sin(2 * np.pi * np.cumsum(f) / SR)


def pops(n, r, rate, amp=1.0, circular=False, flo=700, fhi=6000):
    """Chasquidos de brasa: ráfagas cortísimas de ruido resonante, con tamaños muy desiguales."""
    out = np.zeros(n)
    T = n / SR
    rmax = max(rate(np.linspace(0, T, 400)))
    t = 0.0
    while True:
        t += r.exponential(1 / rmax)
        if t >= T:
            break
        if r.random() > rate(np.array([t]))[0] / rmax:
            continue
        L = secs(r.uniform(0.004, 0.03))
        tau = r.uniform(0.0007, 0.006)
        b = np.zeros(2 * L)
        b[:L] = r.standard_normal(L) * np.exp(-tt(L) / tau)
        f0 = np.exp(r.uniform(np.log(flo), np.log(fhi)))
        b = spec(b, lambda f: band(f, f0 / 1.7, f0 * 1.7, 2))[:L]
        b *= np.linspace(1, 0, L) ** 0.5
        a = amp * min(2.2, r.lognormal(-0.9, 0.7))
        b = a * b / (np.max(np.abs(b)) + 1e-9)
        i = int(t * SR)
        if circular:
            out[(i + np.arange(L)) % n] += b
        else:
            m = min(L, n - i)
            out[i:i + m] += b[:m]
    return out


def soft(x, drive=1.4):
    return np.tanh(x * drive) / np.tanh(drive)


def norm(x, peak=0.64):
    return x * (peak / (np.max(np.abs(x)) + 1e-12))


# ------------------------------------------------------------------ los sonidos

def rugido(seed, dur=1.9, bright=1.0):
    """La llamarada: «FWOOSH» que abre brillante y se queda en un rugido grave que aletea."""
    r = rng(seed)
    n = secs(dur)
    t = tt(n)
    pk = r.uniform(0.07, 0.11)
    tau = r.uniform(0.38, 0.52)
    fc = lambda tq: 420 + 5200 * bright * np.exp(-np.maximum(tq - pk, 0) / tau) * np.clip(tq / pk, 0.05, 1)
    x = stft_gain(colored(n, r, -3.6, 45, 11000), lp_t(fc))
    env = attack(n, pk) * (0.25 + 0.75 * np.exp(-np.maximum(t - pk, 0) / r.uniform(0.42, 0.55)))
    flap = 1 + 0.38 * np.clip(lfo(n, r, 6, 22), -2, 2) * np.clip((t - pk) / 0.25, 0, 1)
    x = x / (np.std(x) + 1e-9) * env * flap
    body = 0.9 * sweep(n, r.uniform(105, 125), 52, 0.22) * attack(n, 0.02) * np.exp(-t / 0.3)
    low = 0.5 * colored(n, r, -6, 35, 220) * attack(n, 0.06) * np.exp(-t / 0.7) * (1 + 0.4 * lfo(n, r, 3, 9))
    return norm(fade_out(soft(0.75 * x + 0.42 * body + 0.3 * low, 1.3), 0.25))


def disparo(seed, dur=0.85):
    """El silbido de una bala o lanza de fuego que sale."""
    r = rng(seed)
    n = secs(dur)
    t = tt(n)
    pk = r.uniform(0.13, 0.18)
    top = r.uniform(2300, 3000)
    fc = lambda tq: 500 + (top - 500) * np.exp(-((tq - pk) / 0.16) ** 2) + 250 * np.exp(-tq / 0.5)
    x = stft_gain(r.standard_normal(n), bp_t(fc, 0.55))
    env = attack(n, pk) ** 1.5 * np.exp(-np.maximum(t - pk, 0) / 0.19)
    x = x / (np.std(x) + 1e-9) * env * (1 + 0.15 * lfo(n, r, 30, 60))
    low = colored(n, r, -6, 60, 380) * attack(n, 0.05) * np.exp(-t / 0.22)
    thump = sweep(n, 150, 70, 0.05) * np.exp(-t / 0.06)
    return norm(fade_out(soft(0.8 * x + 0.45 * low + 0.5 * thump, 1.2), 0.15))


def chorro_bucle(seed, dur=2.5):
    """El chorro del vuelo: rugido sostenido, sin costura al repetirse."""
    r = rng(seed)
    n = secs(dur)
    t = tt(n)
    low = colored(n, r, -5, 55, 1300) * (1 + 0.24 * np.clip(lfo(n, r, 8, 30), -2.5, 2.5))
    mid = 0.22 * colored(n, r, -2, 900, 5200) * (1 + 0.3 * np.clip(lfo(n, r, 12, 40), -2.5, 2.5))
    air = 0.06 * colored(n, r, 0, 4000, 12000)
    rough = 1 + 0.08 * np.sin(2 * np.pi * 44 * t)      # 44 Hz × 2,5 s = 110 ciclos justos
    x = (low + mid + air) * rough
    x = x / (np.sqrt(np.mean(x ** 2)) + 1e-9) * 0.13
    return np.clip(soft(x, 1.1), -0.85, 0.85)


def fuego_bucle(seed, dur=5.0):
    """Un incendio grande: rugido lento que lame, siseo y chasquidos; bucle sin costura."""
    r = rng(seed)
    n = secs(dur)
    lowv = colored(n, r, -5, 60, 800) * (1 + 0.45 * np.clip(lfo(n, r, 1, 6), -2, 2))
    hiss = 0.17 * colored(n, r, 0, 2200, 9000) * (1 + 0.5 * np.clip(lfo(n, r, 2, 9), -1.8, 1.8))
    mid = 0.5 * colored(n, r, -3, 250, 2600) * (1 + 0.35 * np.clip(lfo(n, r, 5, 18), -2, 2))
    x = lowv + hiss + mid
    x = x / (np.sqrt(np.mean(x ** 2)) + 1e-9) * 0.13
    x = x + 0.2 * pops(n, r, lambda q: np.full_like(q, 9.0), 1.0, True)
    return np.clip(soft(x, 1.1), -0.95, 0.95)


def crepitar(seed, dur=2.3):
    """Las brasas que quedan tras un golpe: chasquidos que se van espaciando."""
    r = rng(seed)
    n = secs(dur)
    t = tt(n)
    p = pops(n, r, lambda q: 4 + 30 * np.exp(-q / 0.55), 1.0, False)
    hiss = 0.05 * colored(n, r, -1, 1500, 8000) * np.exp(-t / 0.7)
    low = 0.08 * colored(n, r, -6, 60, 500) * np.exp(-t / 0.5) * (1 + 0.4 * lfo(n, r, 4, 12))
    return norm(fade_out(fade_in(p * (0.35 + 0.65 * np.exp(-t / 1.2)) + hiss + low, 0.002), 0.3), 0.8)


def estallido(seed, big=False):
    """La explosión de fuego: golpe seco, grave que cae, cuerpo de ruido que se cierra y cola de rugido con brasas."""
    r = rng(seed)
    dur = 3.7 if big else 2.5
    n = secs(dur)
    t = tt(n)
    f0, f1 = (82, 30) if big else (112, 42)
    sub = sweep(n, f0, f1, 0.16 if big else 0.12) * np.exp(-t / (0.85 if big else 0.48))
    sub = sub + 0.3 * sweep(n, 2 * f0, 2 * f1, 0.1) * np.exp(-t / 0.25)
    click = np.zeros(n)
    m = secs(0.004)
    click[:m] = spec(np.concatenate([r.standard_normal(m), np.zeros(m)]), lambda f: band(f, 300, 5000))[:m] * np.linspace(1, 0, m)
    fc = lambda tq: 250 + 9500 * np.exp(-np.maximum(tq, 0) / (0.16 if big else 0.11))
    body = stft_gain(r.standard_normal(n), lp_t(fc), 512, 128)
    body = body / (np.std(body[:secs(0.2)]) + 1e-9) * attack(n, 0.004) * np.exp(-t / (0.6 if big else 0.36))
    rum = colored(n, r, -6, 28, 210) * attack(n, 0.12) * np.exp(-t / (1.5 if big else 0.9)) * (1 + 0.5 * np.clip(lfo(n, r, 3, 9), -1.8, 1.8))
    fcr = lambda tq: 500 + 2500 * np.exp(-np.maximum(tq - 0.1, 0) / 0.5)
    roar = stft_gain(colored(n, r, -3.5, 60, 9000), lp_t(fcr))
    roar = roar / (np.std(roar) + 1e-9) * attack(n, 0.1) * np.exp(-t / (1.1 if big else 0.7)) * (1 + 0.35 * np.clip(lfo(n, r, 6, 20), -2, 2))
    emb = pops(n, r, lambda q: 14 * np.exp(-np.maximum(q - 0.25, 0) / 0.9) * (q > 0.25), 1.0, False) * 0.12
    x = 1.0 * sub + 0.9 * click + 0.95 * body + 0.45 * rum + 0.4 * roar + emb
    x = soft(x, 1.25) * (0.45 + 0.55 * np.exp(-t / 0.28))
    return norm(fade_out(fade_in(x, 0.0005), 0.5), 0.66)


def encender(seed, dur=0.55):
    """El «fwomp» de algo que prende."""
    r = rng(seed)
    n = secs(dur)
    t = tt(n)
    fc = lambda tq: 280 + 2900 * np.clip(tq / 0.11, 0, 1) ** 1.5 * np.exp(-np.maximum(tq - 0.11, 0) / 0.14)
    x = stft_gain(r.standard_normal(n), bp_t(fc, 0.8), 512, 128)
    x = x / (np.std(x) + 1e-9) * attack(n, 0.07) * np.exp(-np.maximum(t - 0.07, 0) / 0.13)
    th = sweep(n, 145, 68, 0.05) * attack(n, 0.008) * np.exp(-t / 0.08)
    low = colored(n, r, -6, 50, 400) * attack(n, 0.05) * np.exp(-t / 0.16)
    return norm(fade_out(soft(0.8 * x + 0.7 * th + 0.45 * low, 1.2), 0.12))


def vapor(seed, dur=1.7):
    """El siseo del agua que hierve al tocar el fuego."""
    r = rng(seed)
    n = secs(dur)
    t = tt(n)
    hi = colored(n, r, -1.5, 1700, 11000) * (1 + 0.5 * np.clip(lfo(n, r, 25, 70), -1.8, 1.8))
    fc = lambda tq: 900 + 1500 * np.exp(-tq / 0.5)
    midn = stft_gain(r.standard_normal(n), bp_t(fc, 0.6))
    midn = midn / (np.std(midn) + 1e-9)
    env = attack(n, 0.012) * (0.15 + 0.85 * np.exp(-t / r.uniform(0.42, 0.58)))
    bub = pops(n, r, lambda q: 25 * np.exp(-q / 0.6), 0.5, False, 300, 1500) * 0.5
    return norm(fade_out((hi + 0.7 * midn) * env + bub * env, 0.4), 0.8)


def golpe(seed, dur=0.65):
    """El puñetazo de fuego que conecta: golpe sordo, chasquido y una lengua de llama."""
    r = rng(seed)
    n = secs(dur)
    t = tt(n)
    th = sweep(n, r.uniform(150, 175), 58, 0.07) * attack(n, 0.003) * np.exp(-t / 0.11)
    cr = colored(n, r, -1, 900, 5500) * attack(n, 0.002) * np.exp(-t / 0.022)
    fc = lambda tq: 600 + 3200 * np.exp(-np.maximum(tq - 0.03, 0) / 0.12)
    fl = stft_gain(colored(n, r, -3, 80, 9000), lp_t(fc), 512, 128)
    fl = fl / (np.std(fl) + 1e-9) * attack(n, 0.03) * np.exp(-np.maximum(t - 0.03, 0) / 0.17) * (1 + 0.3 * lfo(n, r, 10, 30))
    return norm(fade_out(fade_in(soft(1.0 * th + 0.55 * cr + 0.42 * fl, 1.6), 0.0005), 0.12))


def sol_bucle(seed, dur=4.0):
    """El sol de Entei: un zumbido grave que late, rugido lejano y un brillo que tiembla. Bucle sin costura."""
    r = rng(seed)
    n = secs(dur)
    t = tt(n)
    dr = np.zeros(n)
    for f, a in ((55.0, 1.0), (55.5, 0.8), (82.5, 0.5), (83.25, 0.4), (110.25, 0.4), (164.75, 0.3), (220.5, 0.22), (330.25, 0.12)):
        dr += a * np.sin(2 * np.pi * f * t + r.uniform(0, 6.28))      # todas múltiplos de 0,25 Hz: encajan en 4 s
    dr = dr / np.max(np.abs(dr))
    roar = colored(n, r, -4.5, 45, 1500) * (1 + 0.4 * np.clip(lfo(n, r, 1, 5), -2, 2))
    shim = colored(n, r, 0, 4200, 9500) * (0.6 + 0.4 * np.sin(2 * np.pi * 8.0 * t)) * (1 + 0.4 * np.clip(lfo(n, r, 1, 4), -2, 2))
    x = 0.5 * dr + 0.4 * roar / np.std(roar) + 0.07 * shim / np.std(shim)
    x = x / (np.sqrt(np.mean(x ** 2)) + 1e-9) * 0.14
    return np.clip(soft(x, 1.1), -0.85, 0.85)


SOUNDS = [
    ("rugido1", lambda: rugido(11)), ("rugido2", lambda: rugido(12, 2.0, 0.85)), ("rugido3", lambda: rugido(13, 1.7, 1.15)),
    ("disparo1", lambda: disparo(21)), ("disparo2", lambda: disparo(22)), ("disparo3", lambda: disparo(23, 0.75)),
    ("chorro_bucle", lambda: chorro_bucle(31)),
    ("fuego_bucle", lambda: fuego_bucle(41)),
    ("crepitar1", lambda: crepitar(51)), ("crepitar2", lambda: crepitar(52)), ("crepitar3", lambda: crepitar(53, 2.0)),
    ("estallido1", lambda: estallido(61)), ("estallido2", lambda: estallido(62)),
    ("estallido_grande1", lambda: estallido(71, True)), ("estallido_grande2", lambda: estallido(72, True)),
    ("encender1", lambda: encender(81)), ("encender2", lambda: encender(82)),
    ("vapor1", lambda: vapor(91)), ("vapor2", lambda: vapor(92)),
    ("golpe1", lambda: golpe(101)), ("golpe2", lambda: golpe(102)), ("golpe3", lambda: golpe(103)),
    ("sol_bucle", lambda: sol_bucle(111)),
]


def write_wav(path, x):
    d = (np.clip(x, -1, 1) * 32767).astype("<i2")
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(d.tobytes())


def stats(name, x):
    X = np.abs(np.fft.rfft(x)) ** 2
    f = np.fft.rfftfreq(len(x), 1 / SR)
    cen = float(np.sum(f * X) / np.sum(X))
    lowf = float(np.sum(X[f < 200]) / np.sum(X))
    rms = 20 * np.log10(np.sqrt(np.mean(x ** 2)) + 1e-12)
    seam = abs(x[0] - x[-1]) / (np.mean(np.abs(np.diff(x))) + 1e-12)
    print(f"{name:20s} {len(x)/SR:5.2f}s pico {np.max(np.abs(x)):.2f} rms {rms:6.1f} dB centro {cen:6.0f} Hz <200Hz {lowf*100:4.1f}% costura {seam:5.1f}")


if __name__ == "__main__":
    wav, ogg = sys.argv[1], sys.argv[2]
    os.makedirs(wav, exist_ok=True)
    os.makedirs(ogg, exist_ok=True)
    for name, fn in SOUNDS:
        x = fn()
        assert np.all(np.isfinite(x)), name
        stats(name, x)
        p = os.path.join(wav, name + ".wav")
        write_wav(p, x)
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", p, "-ac", "1", "-ar", str(SR), "-c:a", "libvorbis",
                        "-q:a", "7", "-map_metadata", "-1", "-fflags", "+bitexact", os.path.join(ogg, name + ".ogg")], check=True)
