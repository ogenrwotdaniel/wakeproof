# Offline water verification — plan

Status: **shipped.** Phase 1 is ported to Kotlin and bound as the default
observer; Phase 3's cutover is done and the `KimiResult.NoKey` honour-mode hold
is gone. Written 2026-08-10, updated 2026-08-30.

**Still never run against a real photograph.** The debug build now carries a
capture tool for the depth corpus (Home -> Developer testing -> Water corpus
capture, `verify/water/corpus/`), which writes the `shots.csv` manifest
`sequences.py` expands — so what remains is shooting, not tooling.

**Still never run against a real photograph.** Everything measured below is
synthetic, which tests "did I break the algorithm", not "does the algorithm
work". Shooting the depth corpus (`tools/water/README.md`) and clearing the gate
in `evaluate.py` remains the outstanding work, and until it is done the Settings
switch "Read the water level on this device" is the escape hatch back to the
cloud path.

## What shipped (2026-08-30)

| Piece | Where |
|---|---|
| The detector | `verify/water/WaterLineDetector.kt` — port of `detect.py` |
| Same-bottle check | `verify/water/ContainerMatch.kt` — port of `same_container` |
| The verdict, shared by both observers | `verify/water/WaterVerdictRules.kt` |
| Decode / rotate / resample | `verify/water/WaterImageSampler.kt` |
| The binding | `verify/water/WaterObserver.kt` — `LocalFirstWaterObserver` |

The port is held to the Python original by a **cross-language fixture**: a
renderer with an explicit LCG exists in both languages
(`tools/water/waterline/portable_synth.py` and `PortableScene.kt`), so the same
pixels go through both implementations and `WaterLineDetectorTest` asserts the
same numbers come out. Property tests alone would not catch a slipped variance
or a half-lost band search. Regenerate the expected constants with
`python -m waterline.portable_synth` whenever `detect.py` changes.

Two things this document got wrong, both now measured — see
`tools/water/README.md` for the numbers:

1. **Phase 1's step 3 below is wrong.** The water line is NOT reliably the
   largest step in a row profile. Averaging across the bottle's width destroys
   the cue that identifies it: a blotchy background seen through a clear bottle
   produces row-profile steps *larger* than the water line's own. What separates
   them is geometry, not photometry — a water line is horizontal across the whole
   bottle because gravity makes it so. Measuring across-column *coherence* of the
   vertical gradient instead cut fill error 11× (MAE 0.043 → 0.0039).
2. **Phase 0 is much cheaper than described.** Shoot sequences and label with a
   measuring jug, not single pairs labelled by the cloud model — `n` shots yield
   `n(n-1)/2` pairs, and the truth is exact instead of ±5%. 120 photographs
   gives 400+ pairs. `tools/water/sequences.py` does the expansion. Shooting the
   corpus is now the only thing blocking this work.

Water verification is the last feature that genuinely needs a model and is worth
keeping. Flashcard drafting can fall back to ML Kit Text Recognition plus
heuristics; study chat is a real casualty of going key-free and stays cloud-only.
Water is different: it is the thing that gets a half-asleep person out of bed at
06:00, it is on the critical path of an alarm the user cannot dismiss, and it is
the one place where "no API key" today means dropping to a 30 s honor-mode hold
that anyone can lie through. Making it work offline is what makes the app's
central promise — *an alarm you can't cheat* — true without a key.

## What is already in our favour

The current design deliberately separates **observation** from **verdict**, and
that seam is the whole reason this plan is cheap.

`KimiRepository.verifyWater` asks the model only for observations:

| Field | Meaning |
|---|---|
| `sameContainer` | is this the same bottle in both shots |
| `levelReadable` | is the water line locatable in both shots |
| `beforeFill` / `afterFill` | how full, as a fraction 0..1 |
| `scaleMlReadable` + `beforeLevelMl` / `afterLevelMl` | printed ml marks, when legible |
| `refilled` | topped up between the shots |

Everything that *decides* anything is then recomputed locally, in plain Kotlin:
ml = fill drop × the user's declared `bottleCapacityMl`, floored to `ROUND_TO_ML`
(10 ml), accepted only when same bottle AND readable AND not refilled AND
≥ `MIN_CONSUMED_ML` (30 ml) AND (a legible scale drop OR ≥ `MIN_FILL_DROP` = 5 %
fill drop). The running tally, the chaining of each accepted "after" into the
next "before", the rejection count and the emergency dismiss all live in
`WaterVerifyViewModel`.

So going offline does **not** mean re-deriving the policy. It means replacing one
producer of six observations. Everything downstream — the tally, the tolerance,
the strike count, the logging — is untouched.

## Architecture: mirror the `MotivationSource` move

`OfflineMotivation` is the template. It implements `MotivationSource`,
`NetworkModule.provideMotivationSource` binds the offline one unconditionally,
and `KimiRepository.motivationalMessage` was deleted outright.

Do the same shape here:

```kotlin
interface WaterObserver {
    suspend fun observe(before: ByteArray, after: ByteArray): KimiResult<WaterObservation>
}
```

- `WaterObservation` = the six fields above, with no `consumedMl` / `accepted`
  (those are the verdict, not an observation — move that arithmetic into a pure
  `WaterVerdictRules.decide(observation, bottleCapacityMl)` that both
  implementations share and that is unit-testable without any image at all).
- `CloudWaterObserver` — today's `verifyWater` body, unchanged.
- `OnDeviceWaterObserver` — the new one.

Unlike motivation, do **not** delete the cloud path on day one. Bind on-device
when it is confident and fall back to cloud only if a key exists (see Phase 3).
The offline path handling only the confident cases is already a win: it removes
the key requirement for the common case and cuts latency at 06:00 to nothing.

## Free wins that need no model at all

Two of the six observations should never have been a model's job:

1. **`refilled`** is `afterFill > beforeFill + noise`. The level going *up* is
   the definition of a refill. Compute it from the two fill numbers, whoever
   produced them. (The model already gets this right most of the time, but it is
   arithmetic, not perception.)
2. **`scaleMlReadable` / the ml marks** are OCR — ML Kit Text Recognition,
   on-device, free, already a dependency family we ship. Numbers printed on a
   bottle next to the water line are exactly what it is good at. Even offline
   this can stay: crop a vertical strip around the detected line, read the
   nearest legible numeral above and below, interpolate.

That leaves the genuinely perceptual part: **where is the water line, and where
are the top and bottom of the bottle** — plus **is it the same bottle**.

## Phase 0 — Capture a dataset before writing any CV (do this first)

The hard part is not the algorithm, it is having 500+ real photo pairs from real
bathrooms at 06:00 with the curtains shut. Build the collector *now*, while the
cloud path still ships, and let ordinary use label itself:

- Debug/opt-in only. A Settings toggle under a "Help improve verification"
  heading, **off by default**, visible text saying photos of your bottle stay on
  the device and are only exported when you tap Export. No silent upload, ever —
  the privacy policy currently promises photos are not retained, and this must
  not quietly contradict it.
- On each `verifyWater` call, when the toggle is on, write to app-private
  storage: `before.jpg`, `after.jpg`, and the cloud verdict JSON.
- The cloud verdict is the **weak label**. This is distillation: the expensive
  model that already runs labels the training set for the cheap one for free.
  Spot-check by hand — the fill fractions are eyeballed by the model too, so
  treat them as ±5 %, not truth.
- Export via SAF, same as the existing CSV export.

Target before moving on: **300 pairs, ≥ 5 distinct bottles, ≥ 3 lighting
conditions** (daylight, warm indoor lamp, dim). Your own two devices plus the
closed-test group is a plausible source if any tester opts in.

## Phase 1 — Classic CV baseline (try to stop here)

A water line in a translucent bottle is a strong, near-horizontal discontinuity:
refraction darkens and compresses what is behind the water, so the region below
the line has different mean intensity, saturation and local contrast from the
region above it.

Baseline, in order:

1. Locate the bottle: largest vertical convex blob, or grab-cut seeded from the
   centre column. A framing overlay in the capture UI (a bottle-shaped guide) is
   worth more than any algorithm here — constrain the input instead of solving
   the general case.
2. Collapse the bottle crop to a **1-D column profile**: for each row, mean
   intensity + mean saturation across the bottle's width.
3. ~~The water line is the row with the largest step in that profile.~~
   **SUPERSEDED — measured and rejected, see the status note above.** A
   background boundary seen through the bottle beats the water line on step
   magnitude. Instead, for each row take the vertical gradient across every
   column and measure whether the columns *agree*:
   `t[y] = mean_x(g[y]) · √W / std_x(g[y])`. A water line is horizontal, so the
   columns agree and `t` is large; arbitrary background structure disagrees, so
   the mean cancels against a large spread. Then damp edges that pair with an
   opposite-signed edge nearby (a label is a *band*; water is a *half-space*),
   and gate on the two-region split from step 2. Confidence is that `t`, so a
   weak peak means "can't see the line" — **this is what drives `levelReadable`**,
   which is exactly the honest answer. Implemented in
   `tools/water/waterline/detect.py`.
4. `beforeFill` / `afterFill` = (bottleBottom − line) / (bottleBottom − bottleTop).
5. `sameContainer`: compare a cheap descriptor of the two crops — aspect ratio,
   dominant hue of the cap region, colour histogram correlation. Threshold it.
   This does not need to be clever; it needs to reject "a different bottle" and
   tolerate "same bottle, moved 10 cm and rotated".

Implement it in pure Kotlin over a downscaled greyscale+HSV array, no OpenCV
dependency (OpenCV's Android AAR is ~40 MB and the AAB is already 39 MB). Keep it
a pure function from pixel arrays to observation so it unit-tests against the
Phase 0 corpus with no device and no camera.

**Accept Phase 1 and ship it** if, on a held-out slice of the corpus:
- fill fraction within ±5 % of the cloud label on ≥ 80 % of pairs, and
- `levelReadable = false` on the pairs a human agrees are unreadable (recall on
  the reject class matters more than the regression error — a wrong *number* only
  miscredits some ml, a wrong *readable* silently accepts noise).

## Phase 2 — Small trained model (only if Phase 1 misses)

If the profile method is beaten by dark bottles, labels wrapped across the water
line, or busy backgrounds:

- Task: **keypoint regression**, not segmentation. Three scalars from a bottle
  crop — `topY`, `bottomY`, `lineY`, normalised to the crop — plus one confidence
  logit for "line visible".
- Backbone: MobileNetV3-Small or smaller at 224×224. This is a 3-number
  regression on a constrained subject; it does not want a big model. Expect
  < 3 MB quantised.
- Train off-device in PyTorch, export to LiteRT (`.tflite`), int8 quantised, run
  via the LiteRT Android runtime. New dep; measure the AAB delta before
  committing to it.
- Same-bottle check can stay classic (Phase 1 step 5) — don't spend a model on it.

## Phase 3 — Cutover ✅ done 2026-08-30

1. ~~Bind `OnDeviceWaterObserver` as the default `WaterObserver`.~~ Done, via
   `LocalFirstWaterObserver`.
2. The cloud retry is triggered by **any non-acceptance**, not only low
   confidence. Broader than planned, and deliberately so: the mistake that
   annoys someone at 06:00 is a rejection, so every user-visible rejection gets
   the stronger opinion first when a key exists. A confident *acceptance* is
   answered on-device with no network at all.
3. ~~The 30 s honor-mode hold for `KimiResult.NoKey` disappears.~~ Done. It is
   now reachable only when the photos cannot be decoded at all, or when the
   Settings switch has forced the cloud path and the network is down.
4. ~~Update `CLAUDE.md`, the privacy policy, the Play Data safety answers.~~ Done.

One thing this does **not** do, still: printed ml marks (`scaleMlReadable`).
That is ML Kit Text Recognition's job, on-device and free, and the app already
falls back to the fill fraction when marks are not legible — so reporting
`false` unconditionally is safe, just not free millilitres.

## Risks and non-goals

- **Opaque bottles are unsolvable.** Steel flasks have no visible line at any
  price. The honest answer is `levelReadable = false` forever, and Settings
  should say so: verification needs a translucent bottle.
- **This is not anti-adversarial.** Neither is the cloud version. Someone
  determined to pour water down the sink to beat their own alarm will succeed.
  The bar is "harder than pressing snooze", not "tamper-proof".
- **Don't regress the tally UX.** The chaining in `WaterVerifyViewModel` is what
  makes a single mouthful count; a noisier local estimator makes the tolerance
  (`TARGET_TOLERANCE_ML` = 50) matter more, not less. Revisit it with real
  numbers from Phase 1, don't guess.
- **Don't collect photos without an explicit, off-by-default opt-in.** Getting
  this wrong is a Play policy problem and a privacy-policy contradiction, not
  just a bad look.

## Rough effort

| Phase | Work |
|---|---|
| 0 — collector | half a day, then wall-clock waiting for pairs |
| 1 — CV baseline | 2–3 days including the test harness |
| 2 — trained model | a week, only if Phase 1 fails |
| 3 — cutover | half a day |

Phase 0 is the one that blocks everything and costs the least. Build it next time
the water path is open regardless of whether Phases 1–2 ever happen.
