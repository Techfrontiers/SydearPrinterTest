# SYDEAR Printer Test

Android test app for Bluetooth printing with the **EasyPrint ES-9910UB**
(Bluetooth name `ES-9910UB`, MAC `66:32:8E:84:F6:84`, SPP UUID
`00001101-0000-1000-8000-00805F9B34FB`)

- Package: `com.sydear.printertest`
- Transport: Bluetooth Classic RFCOMM/SPP UUID `00001101-0000-1000-8000-00805F9B34FB`
  (secure first, falls back to insecure RFCOMM)
- Target device: `ES-9910UB` / MAC `66:32:8E:84:F6:84`
- Self-updater: checks `updates/latest.json` on launch + manual "เช็คอัพเดท" button;
  downloads via DownloadManager and opens the system installer through a custom
  `ApkProvider` (no androidx dependency)

> ES-9910UB is a sticker/label printer — it speaks **TSPL**, not ESC/POS
> (verified: ESC/POS commands are silently ignored)

## App menus

| Menu | Contents |
|------|----------|
| 🖨️ พิมพ์ (Print) | Text box + formatting toolbar (bold / italic / underline / size S·M·L / align left·center·right) → print button |
| 🔵 เครื่องพิมพ์ (Printer) | Device select / connect / profile / **Webhook** / app update |
| 📋 Log | Full activity log + clear button |

## Text printing

Text is rendered with Android's text layout into a 1-bit bitmap
(correct **Thai** shaping + bold/italic/underline/size/alignment),
then sent as a TSPL `BITMAP` command:

```
BITMAP X,Y,width,height,mode,data…
```
- `width` in **bytes**, `height` in **dots** (per TSC TSPL2 manual);
  binary data follows the comma immediately (no CRLF before it)
- bit `1` = black dot, MSB first

## Webhook — print over the network

Enable from 🔵 เครื่องพิมพ์ → **เปิด Webhook**.
The phone becomes an HTTP server on port `8080`:

```
GET  /status   → {ok, connected, printer, version}
POST /print    → JSON body {text, bold, italic, underline, size, align}
```

Pass the key via `?key=` or the `X-Webhook-Key` header
(the app shows the key and the phone's IPs — works over same-WiFi or Tailscale).

Example:

```bash
curl -X POST "http://<phone>:8080/print?key=<key>" \
  -H "Content-Type: application/json" \
  -d '{"text":"สวัสดี","bold":true,"size":"L","align":"center"}'
```

## Printer Profile: 80mm Continuous Thermal

Matches the Windows driver stock settings:

| Setting    | Value                 |
|------------|-----------------------|
| Name       | 80mm Continuous Thermal |
| Width      | 80 mm                 |
| Height     | **auto** — calculated from content (no fixed height) |
| Media      | Continuous            |
| GAP        | 0 mm                  |
| GAP Offset | 0                     |
| Speed      | 8                     |
| Density    | 15                    |
| Direction  | 0                     |
| Top margin | 10 mm                 |
| Bottom margin | 10 mm              |
| Peel       | OFF                   |
| Cutter     | OFF                   |
| Tear       | OFF                   |

### Auto paper height

The renderer (`TsplLabel`) places each element and records its **real bounding
box** (top/bottom edges in dots). Content height = the box covering all
elements — never guessed from line count. No trailing padding is added after
the last element.

```
TOTAL_HEIGHT = 10 mm (top) + CONTENT_HEIGHT + 10 mm (bottom)
```

Examples: content 42 mm → `SIZE 80 mm,62 mm`; content 137 mm → `SIZE 80 mm,157 mm`.
If the total isn't a whole mm it rounds **up** (never clips content).
The app logs the calculation before sending:

```
TSPL AUTO HEIGHT
Content: 42 mm
Top: 10 mm
Bottom: 10 mm
Total: 62 mm
Speed: 8
Density: 15
```

> Do NOT use Label/GAP 3 mm settings — this printer runs 80mm **continuous**
> thermal paper, like the Windows driver stock `80mm Thermal`
> (Type=Label, Method=Continuous, Gap=0mm, Gap Offset=0, Speed=8, Density=8,
> Post-Print=None, Orientation=Portrait).

### TSPL configuration block (send before every print job)

```
SIZE 80 mm,100 mm
GAP 0,0
SPEED 8
DENSITY 8
DIRECTION 0
REFERENCE 0,0
SET PEEL OFF
SET CUTTER OFF
SET TEAR OFF
CLS
```

Then the TSPL content, then `PRINT 1`.

## Build

Manual build (the Gradle daemon is blocked in this sandbox):

```sh
~/workspace/tools/build-apk.sh   # aapt2 + kotlinc + d8 + apksigner
```

Signing key: `~/workspace/tools/sydear-debug.keystore` (persistent — keep it,
otherwise updates require an uninstall first).

If the phone is Android 12+, the app requests `BLUETOOTH_CONNECT` and
`BLUETOOTH_SCAN` at runtime.

## Test procedure

1. Pair `ES-9910UB` in Android Bluetooth settings.
2. Launch the app, select `ES-9910UB` (the known MAC is highlighted with ★).
3. Tap `เชื่อมต่อ`.
4. Tap `TEST: TSPL 80mm CONTINUOUS` — prints the profile test label.
5. Record what prints.

## updates/

`updates/latest.json` + versioned APKs feed the in-app self-updater.
