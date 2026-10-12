# Hill Roller

2D physics ball game for Android (Kotlin, no external libraries).

- **Start:** hold the screen to push the ball off the first hill.
- **After launch:** holding makes the ball heavier (gravity x4). Hold on downhills to gain speed, release on uphills.
- You're stuck when the ball stops for 2 s. Score = metres travelled.

## Build without a PC
Every push runs `.github/workflows/build.yml`, which builds the APK and publishes it to the
**Releases → "Latest build"** page. Open that page on your phone, download `HillRoller.apk`, and install it
(allow "install unknown apps" for your browser when asked).

## Modes
- **Standard** - roll as far as you can, collect coins, beat your records.
- **You vs Bot** (Easy / Medium / Hard) - race a computer ball to 5000 m on the same hills. Rolling backwards or getting stuck loses.
- **You vs Friend** - split screen. Left half controls player 1 (blue ball), right half player 2 (red ball). Same rules.
